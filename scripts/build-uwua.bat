@echo off
REM ==============================================================
REM  MindustryX UwUA server build launcher (double-click friendly)
REM  Pulls latest, patches, applies UwUA/300, builds, verifies.
REM  Pass extra flags after the file, e.g.:  build-uwua.bat -SkipPatch
REM ==============================================================
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-uwua.ps1" -Pull %*
set RC=%ERRORLEVEL%
echo.
if "%RC%"=="0" (echo Done.) else (echo FAILED with code %RC%.)
pause
endlocal
exit /b %RC%
