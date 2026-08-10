@echo off
REM jugg.cmd - Windows wrapper for Jugg CLI (Python version)
REM Place this in PATH or call directly: jugg.cmd compile --console=json

setlocal
set "SCRIPT_DIR=%~dp0"
set "PYTHON="
for %%P in (python3 python) do (
  if not defined PYTHON (
    where %%P >nul 2>nul
    if not errorlevel 1 (
      %%P -c "import sys; raise SystemExit(sys.version_info ^< (3, 7))" >nul 2>nul
      if not errorlevel 1 set "PYTHON=%%P"
    )
  )
)
if not defined PYTHON (
  echo jugg: Python 3.7+ was not found. Install Python or add python3/python to PATH. 1>&2
  exit /b 127
)
"%PYTHON%" "%SCRIPT_DIR%jugg.py" --console=rich %*
exit /b %ERRORLEVEL%
