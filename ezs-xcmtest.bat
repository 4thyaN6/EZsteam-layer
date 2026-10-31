@echo off
rem Fixed helper: ANGLE Vulkan Steam UI without and with the X compositing manager test, menu pictures and CPU (about 6 minutes, watch the tablet), see tools\ezs.ps1.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\ezs.ps1" xcmtest
pause
