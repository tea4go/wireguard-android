@echo off
rem Builds an unsigned release APK to verify that compilation succeeds.

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\build-unsigned.ps1" %*
pause
