@echo off
rem Builds the debug APK and installs it on every connected device via adb.

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\deploy.ps1" %*
pause
