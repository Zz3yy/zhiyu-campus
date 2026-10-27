# Optional settings for the maintainer's D:\Pro installation. Dot-source this file.
# Only the current PowerShell process is affected; no credentials are accessed.
$zhiyuToolDirs = @(
    'D:\Pro\jdk\jdk21\bin',
    'D:\Pro\maven\apache-maven-3.9.16\bin',
    'D:\Pro\pnpm',
    'D:\Pro',
    'D:\Pro\python312'
)
foreach ($zhiyuToolDir in $zhiyuToolDirs) {
    if (-not (Test-Path -LiteralPath $zhiyuToolDir -PathType Container)) {
        throw "Optional D-drive environment is unavailable: $zhiyuToolDir. Use tools installed on PATH instead."
    }
}
$env:JAVA_HOME = 'D:\Pro\jdk\jdk21'
$env:PATH = ($zhiyuToolDirs -join ';') + ';' + $env:PATH
if ($env:MAVEN_OPTS -notmatch '-Dmaven\.repo\.local=') {
    $env:MAVEN_OPTS = ($env:MAVEN_OPTS + ' -Dmaven.repo.local=D:/Pro/m2').Trim()
}
Write-Host 'D-drive tools enabled for this terminal. For pnpm installs, optionally add --store-dir D:/Pro/pnpm-store.'
