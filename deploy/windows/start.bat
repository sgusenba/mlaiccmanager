@echo off
rem Double-click to start MLAICC Manager. See start.ps1 for what it does.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1"
if errorlevel 1 pause
