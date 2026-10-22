@echo off
rem Runs one of the portal's server commands with the bundled Java, on the data in portal-data\:
rem   portal-cli.bat --add-organiser stefan
rem   portal-cli.bat --set-password stefan
rem   portal-cli.bat --reset-totp stefan
rem Double-clicked (no arguments) it asks for a name and creates that organiser account.
rem The portal may keep running.
setlocal
cd /d "%~dp0"
set "JAVA=%~dp0jre\bin\java.exe"
if not exist "%JAVA%" set "JAVA=java"

if not "%~1"=="" goto run
set /p "NAME=New organiser account, name: "
"%JAVA%" -jar mlaic-portal.jar --add-organiser "%NAME%"
set "RC=%ERRORLEVEL%"
pause
exit /b %RC%

:run
"%JAVA%" -jar mlaic-portal.jar %*
