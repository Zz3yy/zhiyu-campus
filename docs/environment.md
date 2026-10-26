# Development Environment

The project runtime is kept under `D:\Pro` to avoid using the system Java, Python, or Node installations.

## Required tools

- Java 21 at `D:\Pro\jdk\jdk21`
- Maven 3.9.16 at `D:\Pro\maven\apache-maven-3.9.16`
- Node.js 20+ at `D:\Pro`
- pnpm 11+
- Python 3.12 at `D:\Pro\python312`
- Docker Desktop for PostgreSQL/pgvector and Redis

## PowerShell setup

```powershell
. .\scripts\env.ps1
java -version
mvn -version
python --version
```

## Project setup

```powershell
cd zhiyu-web
pnpm install
pnpm build

cd ..\zhiyu-ai-service
python -m pip install -r requirements.txt
python -m pytest -q

cd ..\deploy
docker compose up -d postgres redis
```

Pip, npm, pnpm, and uv caches are configured under `D:\Pro`. Docker Desktop is not installed automatically because its WSL2 data disk requires a system-level choice; after installation set its WSL data root to `D:\Pro\Docker\wsl`.
