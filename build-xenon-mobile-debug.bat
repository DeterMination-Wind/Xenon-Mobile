@echo off
setlocal

set "BUILD_DRIVE=%XENON_BUILD_DRIVE%"
if "%BUILD_DRIVE%"=="" set "BUILD_DRIVE=X:"
if not "%BUILD_DRIVE:~-1%"==":" set "BUILD_DRIVE=%BUILD_DRIVE%:"

set "ROOT=%~dp0"
set "ROOT=%ROOT:~0,-1%"

subst %BUILD_DRIVE% "%ROOT%" >nul
if errorlevel 1 (
    echo Failed to map %BUILD_DRIVE% to "%ROOT%".
    echo Set XENON_BUILD_DRIVE=Y: if the default drive is already in use.
    exit /b 1
)

pushd %BUILD_DRIVE%\
call gradlew.bat -g .gradle-user-home-local :ZalithLauncher:assembleDebug %*
set "RESULT=%ERRORLEVEL%"
popd

subst %BUILD_DRIVE% /D >nul 2>nul
exit /b %RESULT%
