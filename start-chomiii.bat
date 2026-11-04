@echo off
setlocal
title chomiii local backend
cd /d "%~dp0"

where node >nul 2>nul || (echo Node.js is required. Install Node.js 22 or newer. & pause & exit /b 1)
where npm >nul 2>nul || (echo npm is required. Install Node.js with npm. & pause & exit /b 1)
where java >nul 2>nul || (echo Java 21 is required. Install a Java 21 JDK. & pause & exit /b 1)

if not exist "tools\launcher\node_modules\embedded-postgres\package.json" (
    echo Installing the local PostgreSQL launcher...
    call npm --prefix "%~dp0tools\launcher" ci
    if errorlevel 1 (echo Launcher installation failed. & pause & exit /b 1)
)
if not exist "frontend\node_modules\vite\package.json" (
    echo Installing frontend dependencies...
    call npm --prefix "%~dp0frontend" ci
    if errorlevel 1 (echo Frontend installation failed. & pause & exit /b 1)
)

echo Starting Chomiii. Backend messages appear in this window.
echo Close this window or press Ctrl+C to stop the local services.
node "%~dp0tools\launcher\start.mjs"
if errorlevel 1 (echo Chomiii could not start. Read the messages above. & pause & exit /b 1)
endlocal
