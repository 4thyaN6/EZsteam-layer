@echo off
rem Fixed helper: tag the pushed commit as a test release (version from app\build.gradle, see tools\ezs.ps1).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\ezs.ps1" release
pause
