@echo off
rem Fixed helper: ANGLE Vulkan Steam UI per web helper variant from tools\whtest-variants.txt, CPU and menu band check (about 3 minutes per variant, tablet untouched), see tools\ezs.ps1.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\ezs.ps1" whtest
pause
