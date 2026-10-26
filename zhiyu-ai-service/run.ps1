$python = 'D:\Pro\venvs\zhiyu-ai\Scripts\python.exe'
if (-not (Test-Path $python)) {
    throw 'AI service virtual environment is missing. Run: D:\Pro\python312\python.exe -m venv D:\Pro\venvs\zhiyu-ai'
}
& $python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
