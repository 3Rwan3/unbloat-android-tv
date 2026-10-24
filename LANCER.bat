@echo off
chcp 65001 >nul
title Kit TV Android
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\kit.ps1"
if errorlevel 1 pause
