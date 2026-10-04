@echo off
REM ==============================================================
REM  MindustryX upstream update (double-click friendly)
REM  Runs updateUpstream.sh in Git Bash with JDK 17:
REM    fetch upstream -> commit pins -> applyPatches -> genPatches -> syncPackets
REM  No argument = newest upstream release tag. Or pass one:
REM    update-upstream.bat v160.6
REM  Do not click inside this window while it runs: console QuickEdit
REM  selection pauses the script until you press Esc.
REM ==============================================================
setlocal

REM Git Bash next to git.exe (Git\cmd\git.exe -> Git\bin\bash.exe); not WSL's bash
set "BASH="
for /f "delims=" %%i in ('where git 2^>nul') do if not defined BASH if exist "%%~dpi..\bin\bash.exe" set "BASH=%%~dpi..\bin\bash.exe"
if not defined BASH (echo Git Bash not found. Install Git for Windows. & set "RC=1" & goto end)

REM JDK 17 for gradle; override with MINDUSTRYX_JDK
if defined MINDUSTRYX_JDK (set "JAVA_HOME=%MINDUSTRYX_JDK%") else (
    for /d %%d in ("D:\java\*jdk-17*" "C:\Program Files\Java\*jdk-17*" "C:\Program Files\Eclipse Adoptium\*jdk-17*") do set "JAVA_HOME=%%~d"
)
if not exist "%JAVA_HOME%\bin\java.exe" (echo JDK 17 not found. Set MINDUSTRYX_JDK. & set "RC=1" & goto end)
echo Using JAVA_HOME=%JAVA_HOME%

"%BASH%" "%~dp0updateUpstream.sh" %*
set RC=%ERRORLEVEL%

:end
echo.
if "%RC%"=="0" (echo Done.) else (echo FAILED with code %RC%.)
pause
endlocal & exit /b %RC%
