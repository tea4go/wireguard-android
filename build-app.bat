@echo off
rem Builds the release AAB for Google Play upload.

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\build-app.ps1" %*
pause
