@echo off
title Zoom Autonomous Meeting Recorder & Web Studio
echo ========================================================
echo   Zoom Autonomous Meeting Recorder Suite (v3.0.0)
echo   Backend Engine ^& Web Studio Dashboard
echo ========================================================
echo.

cd /d "%~dp0backend"

if not exist "node_modules" (
    echo [Setup] Installing backend dependencies...
    call npm install
)

echo [Server] Starting Zoom Recorder Engine on http://localhost:3000 ...
echo [Browser] Opening Web Studio Dashboard in default browser...
start http://localhost:3000/

node src/index.js
pause
