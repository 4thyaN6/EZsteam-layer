@echo off
rem Fixed helper: save a report in the running session and pull it over adb (see tools\ezs.ps1).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\ezs.ps1" report
pause
