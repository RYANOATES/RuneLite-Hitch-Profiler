@echo off
setlocal
cd /d "%~dp0"
set "GRADLE_USER_HOME=%~dp0.gradle"
call gradlew.bat --no-daemon run
if errorlevel 1 pause
endlocal
