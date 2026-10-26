[CmdletBinding()]
param(
    [switch] $StartInfrastructure,
    [switch] $RestartProjectBackend,
    [ValidateRange(30, 600)]
    [int] $InfrastructureWaitSeconds = 120
)

$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = 'Stop'

$apiKeySecure = $null
$apiKey = $null
$oldEnvironment = @{}
$environmentWasSet = $false
$locationWasPushed = $false

function Get-ListeningProcessIds {
    param([Parameter(Mandatory)][int] $Port)

    $netstatPath = Join-Path $env:WINDIR 'System32\netstat.exe'
    if (-not (Test-Path -LiteralPath $netstatPath)) {
        $netstatPath = 'netstat.exe'
    }

    $ids = @()
    foreach ($line in (& $netstatPath -ano -p tcp)) {
        if ($line -match "^\s*TCP\s+\S+:$Port\s+\S+\s+LISTENING\s+(\d+)\s*$") {
            $ids += [int] $Matches[1]
        }
    }
    return @($ids | Sort-Object -Unique)
}

try {
    $projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
    $serverDir = Join-Path $projectRoot 'zhiyu-server'
    $composeFile = Join-Path $projectRoot 'deploy\docker-compose.yml'

    $apiKeySecure = Read-Host 'Ark API Key (hidden input)' -AsSecureString
    if ($null -eq $apiKeySecure -or $apiKeySecure.Length -eq 0) {
        throw 'API Key is required; infrastructure and backend were not started.'
    }

    $model = (Read-Host 'Ark model value (model or endpoint ID)').Trim()
    if ([string]::IsNullOrWhiteSpace($model)) {
        throw 'Model value is required; infrastructure and backend were not started.'
    }

    $apiKey = [System.Net.NetworkCredential]::new('', $apiKeySecure).Password
    $baseUrl = $env:ARK_BASE_URL
    if ([string]::IsNullOrWhiteSpace($baseUrl)) {
        $baseUrl = 'https://ark.cn-beijing.volces.com/api/v3'
    }
    $parsedBaseUrl = $null
    if (-not [Uri]::TryCreate($baseUrl, [UriKind]::Absolute, [ref] $parsedBaseUrl)) {
        throw 'ARK_BASE_URL must be an absolute URL.'
    }

    $mavenCommand = 'D:\Pro\maven\apache-maven-3.9.16\bin\mvn.cmd'
    if (-not (Test-Path -LiteralPath $mavenCommand)) {
        $mavenOnPath = Get-Command mvn.cmd -ErrorAction SilentlyContinue
        if ($null -eq $mavenOnPath) {
            throw 'Maven was not found. Install Maven or check D:\Pro\maven\apache-maven-3.9.16\bin\mvn.cmd.'
        }
        $mavenCommand = $mavenOnPath.Source
    }

    $javaHome = $env:JAVA_HOME
    if ([string]::IsNullOrWhiteSpace($javaHome) -or
        -not (Test-Path -LiteralPath (Join-Path $javaHome 'bin\java.exe'))) {
        $javaHome = 'D:\Pro\jdk\jdk21'
    }
    $javaPath = Join-Path $javaHome 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $javaPath)) {
        throw 'JDK 21 was not found. Set JAVA_HOME or install it at D:\Pro\jdk\jdk21.'
    }
    $javaStartInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $javaStartInfo.FileName = $javaPath
    $javaStartInfo.Arguments = '-version'
    $javaStartInfo.UseShellExecute = $false
    $javaStartInfo.CreateNoWindow = $true
    $javaStartInfo.RedirectStandardOutput = $true
    $javaStartInfo.RedirectStandardError = $true
    $javaProcess = [System.Diagnostics.Process]::new()
    $javaProcess.StartInfo = $javaStartInfo
    [void] $javaProcess.Start()
    $javaVersion = $javaProcess.StandardOutput.ReadToEnd() + $javaProcess.StandardError.ReadToEnd()
    $javaProcess.WaitForExit()
    if ($javaProcess.ExitCode -ne 0 -or $javaVersion -notmatch 'version "21[.]|openjdk version "21[.]') {
        throw 'This project requires JDK 21; check JAVA_HOME.'
    }

    if ($StartInfrastructure) {
        $docker = Get-Command docker -ErrorAction SilentlyContinue
        if ($null -eq $docker) {
            throw 'Docker was not found. Start Docker Desktop, or start PostgreSQL/Redis manually and omit -StartInfrastructure.'
        }
        Write-Host 'Starting PostgreSQL and Redis; waiting for health checks...'
        & docker compose -f $composeFile up -d --wait --wait-timeout $InfrastructureWaitSeconds postgres redis
        if ($LASTEXITCODE -ne 0) {
            throw 'Docker Compose could not start PostgreSQL/Redis; the backend was not started.'
        }
    }

    $listenerIds = @(Get-ListeningProcessIds -Port 8080)
    foreach ($listenerId in $listenerIds) {
        $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $listenerId" -ErrorAction Stop
        $commandLine = if ($null -eq $processInfo) { '' } else { [string] $processInfo.CommandLine }
        if ([string]::IsNullOrWhiteSpace($commandLine) -or
            $commandLine.IndexOf($serverDir, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
            throw "Port 8080 is occupied by another process (PID $listenerId). It was not stopped."
        }
        if (-not $RestartProjectBackend) {
            throw 'This project already owns port 8080. Add -RestartProjectBackend to restart the verified project backend.'
        }
    }

    if ($RestartProjectBackend -and $listenerIds.Count -gt 0) {
        foreach ($listenerId in $listenerIds) {
            Write-Host "Stopping verified project backend (PID $listenerId)..."
            Stop-Process -Id $listenerId -Force
        }
        for ($attempt = 0; $attempt -lt 20 -and (Get-ListeningProcessIds -Port 8080).Count -gt 0; $attempt++) {
            Start-Sleep -Milliseconds 250
        }
        if ((Get-ListeningProcessIds -Port 8080).Count -gt 0) {
            throw 'Port 8080 is still occupied; the new backend was not started.'
        }
    }

    foreach ($name in @('ARK_API_KEY', 'ARK_MODEL', 'ARK_BASE_URL', 'JAVA_HOME', 'PATH')) {
        $oldEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }

    [Environment]::SetEnvironmentVariable('ARK_API_KEY', $apiKey, 'Process')
    [Environment]::SetEnvironmentVariable('ARK_MODEL', $model, 'Process')
    [Environment]::SetEnvironmentVariable('ARK_BASE_URL', $baseUrl, 'Process')
    [Environment]::SetEnvironmentVariable('JAVA_HOME', $javaHome, 'Process')
    [Environment]::SetEnvironmentVariable('PATH', "$javaHome\bin;$env:PATH", 'Process')
    $environmentWasSet = $true

    Write-Host 'Starting the database-backed service. The API Key is not written to project files; keep this terminal open.'
    Push-Location $serverDir
    $locationWasPushed = $true
    & $mavenCommand -B spring-boot:run
    if ($LASTEXITCODE -ne 0) {
        throw "Backend exited with Maven code $LASTEXITCODE."
    }
}
finally {
    if ($locationWasPushed) {
        Pop-Location
    }
    if ($environmentWasSet) {
        foreach ($name in $oldEnvironment.Keys) {
            [Environment]::SetEnvironmentVariable($name, $oldEnvironment[$name], 'Process')
        }
    }
    if ($null -ne $apiKeySecure) {
        $apiKeySecure.Dispose()
    }
    $apiKey = $null
    $model = $null
    $ErrorActionPreference = $previousErrorActionPreference
}
