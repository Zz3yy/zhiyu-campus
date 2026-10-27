Push-Location $PSScriptRoot
try {
    & pnpm dev --host 127.0.0.1
} finally {
    Pop-Location
}
