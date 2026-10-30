@echo off
rem Fixed helper: ANGLE Vulkan Steam UI with web helper variants, menu band noise and CPU per variant (about 10 minutes, tablet untouched), see tools\ezs.ps1.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\ezs.ps1" whtest
pause
