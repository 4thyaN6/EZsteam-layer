@echo off
rem Fixed helper: Steam with Zink GL + GPU device + Xwayland (switches stay set), report over adb (see tools\ezs.ps1).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\ezs.ps1" steam -Path xwayland
pause
