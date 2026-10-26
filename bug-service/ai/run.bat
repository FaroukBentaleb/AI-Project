@echo off
rem Starts the bug triage model server on http://localhost:8001 (no Docker).
rem First run creates .venv and installs the dependencies (CPU PyTorch included, a few hundred MB).
cd /d "%~dp0"
if not exist .venv\Scripts\python.exe (
    echo Creating .venv and installing dependencies...
    python -m venv .venv || exit /b 1
    .venv\Scripts\python -m pip install -r requirements.txt || exit /b 1
    .venv\Scripts\python -m pip install torch --index-url https://download.pytorch.org/whl/cpu || exit /b 1
    .venv\Scripts\python -m pip install -r requirements-model.txt || exit /b 1
)
.venv\Scripts\python -m uvicorn app:app --host 127.0.0.1 --port 8001
