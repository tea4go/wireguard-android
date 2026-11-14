@echo off
rem Builds a signed release APK (increments the patch version first).

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\build-signed.ps1" %*
pause
