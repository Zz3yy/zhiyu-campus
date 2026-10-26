$env:PATH = 'D:\Pro;' + $env:PATH
Set-Location $PSScriptRoot
& 'D:\Pro\pnpm\pnpm.cmd' dev --host 127.0.0.1
