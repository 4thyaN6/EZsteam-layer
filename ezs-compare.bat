@echo off
rem Fixed helper: compare Steam UI GPU modes 1-3 on Xwayland: idle, arrow-key moves, menu band check (about 10 minutes, tablet untouched) (see tools\ezs.ps1).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\ezs.ps1" compare
pause
