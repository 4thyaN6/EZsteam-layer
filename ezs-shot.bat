@echo off
rem Fixed helper: three-step pictures right now; run it while the fault is on the tablet screen (see tools\ezs.ps1).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\ezs.ps1" shot
pause
