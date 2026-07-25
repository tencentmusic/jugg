@echo off
REM jugg.cmd - Windows wrapper for Jugg CLI (Python version)
REM Place this in PATH or call directly: jugg.cmd compile --console=json

setlocal
set "SCRIPT_DIR=%~dp0"
where python3 >nul 2>nul
if not errorlevel 1 (
  set "PYTHON=python3"
) else (
  where python >nul 2>nul
  if errorlevel 1 (
    echo jugg: Python 3.7+ was not found. Install Python or add python3/python to PATH. 1>&2
    exit /b 127
  )
  set "PYTHON=python"
)
"%PYTHON%" "%SCRIPT_DIR%jugg.py" --console=rich %*
set "EXIT_CODE=%ERRORLEVEL%"
endlocal
exit /b %EXIT_CODE%
