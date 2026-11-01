@echo off
rem Fixed helper: Steam UI with PRoot tracing every close, then only netlink closes (proot-09 switch); CPU, PRoot stops and broken pipe lines each, about 8 minutes, see tools\ezs.ps1.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\ezs.ps1" closetest
pause
