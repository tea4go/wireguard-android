@echo off
rem Entry point for building the WireGuard Android APK on Windows 11.
rem Delegates to scripts\build-win.ps1 so the build logic lives in one place.
rem Arguments are passed through, e.g.:
rem     build_win.bat -Task :ui:assembleRelease -Clean

setlocal
set "PS1=%~dp0scripts\build-win.ps1"

if not exist "%PS1%" (
    echo ERROR: "%PS1%" not found.
    echo Run this script from a checkout of the repository.
    exit /b 1
)

set "PS_EXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if not exist "%PS_EXE%" set "PS_EXE=powershell.exe"

"%PS_EXE%" -NoProfile -ExecutionPolicy Bypass -File "%PS1%" %*
exit /b %errorlevel%
