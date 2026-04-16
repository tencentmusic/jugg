@echo off
REM jugg.cmd — Windows wrapper for Jugg CLI (Python version)
REM Place this in PATH or call directly: jugg.cmd compile --json

setlocal
set "SCRIPT_DIR=%~dp0"
python3 "%SCRIPT_DIR%jugg.py" %*
endlocal
