param([string] $PythonCommand = 'python')

# Activate a virtual environment first, or pass -PythonCommand with its executable.
Push-Location $PSScriptRoot
try {
    & $PythonCommand -m uvicorn app.main:app --host 127.0.0.1 --port 8000
} finally {
    Pop-Location
}
