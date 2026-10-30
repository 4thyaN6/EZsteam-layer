@echo off
rem Fixed helper: compare Steam UI GPU modes on Xwayland (about 9 minutes, tablet untouched), reports over adb (see tools\ezs.ps1).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\ezs.ps1" compare
pause
