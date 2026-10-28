@echo off
rem Fixed helper: fresh Steam start on the tablet, wait 90 s, pull the report over adb (see tools\ezs.ps1).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\ezs.ps1" steam
pause
