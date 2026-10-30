@echo off
rem Fixed helper: Steam menu pictured at three steps in each Steam UI GPU mode (about 7 minutes, tablet untouched), see tools\ezs.ps1.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\ezs.ps1" menushot
pause
