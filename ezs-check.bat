@echo off
rem Fixed helper: GPU path check on the tablet, then pull the report over adb (see tools\ezs.ps1).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\ezs.ps1" check
pause
