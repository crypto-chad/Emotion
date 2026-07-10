@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
where bash >nul 2>&1
if %ERRORLEVEL% equ 0 (
  bash "%SCRIPT_DIR%gradlew" %*
  exit /b %ERRORLEVEL%
)
echo [ERROR] bash not found in PATH. Install Git for Windows ^(adds bash^) or build from Android Studio.
exit /b 1
