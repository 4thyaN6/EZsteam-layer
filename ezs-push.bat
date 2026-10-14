@echo off
rem Fixed helper: push (see tools\ezs.ps1). Double-click to run.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\ezs.ps1" push
pause
