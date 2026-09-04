@echo off
REM jugg.cmd - Windows wrapper for Jugg CLI (Python version)
REM Place this in PATH or call directly: jugg.cmd compile --console=json

setlocal
set "SCRIPT_DIR=%~dp0"
set "PYTHON_ARGS="
python3 -c "import sys; sys.exit(0 if sys.version_info >= (3, 7) else 1)" >nul 2>nul
if not errorlevel 1 (
  set "PYTHON=python3"
  goto run
)
python -c "import sys; sys.exit(0 if sys.version_info >= (3, 7) else 1)" >nul 2>nul
if not errorlevel 1 (
  set "PYTHON=python"
  goto run
)
py -3 -c "import sys; sys.exit(0 if sys.version_info >= (3, 7) else 1)" >nul 2>nul
if not errorlevel 1 (
  set "PYTHON=py"
  set "PYTHON_ARGS=-3"
  goto run
)
echo jugg: Python 3.7+ was not found. Install Python or add python3/python/py to PATH. 1>&2
exit /b 127

:run
"%PYTHON%" %PYTHON_ARGS% "%SCRIPT_DIR%jugg.py" --console=rich %*
set "EXIT_CODE=%ERRORLEVEL%"
endlocal
exit /b %EXIT_CODE%
