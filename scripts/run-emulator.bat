@echo off
setlocal enabledelayedexpansion
echo.
echo ============================================================
echo  Better Audio - Build + Run on Emulator
echo ============================================================
echo.

set "SDK=%LOCALAPPDATA%\Android\Sdk"
set "ADB=%SDK%\platform-tools\adb.exe"
set "EMU=%SDK%\emulator\emulator.exe"
set "APK=%~dp0..\app\build\outputs\apk\debug\app-debug.apk"
set "PKG=com.betteraudio"
set "ACTIVITY=com.betteraudio.MainActivity"

:: ── Check tools ──────────────────────────────────────────────
if not exist "%ADB%" ( echo ERROR: ADB not found. Install Android Studio. & exit /b 1 )
if not exist "%EMU%" ( echo ERROR: Emulator not found. Install Android Studio. & exit /b 1 )

:: ── Pick AVD ─────────────────────────────────────────────────
set "FIRST_AVD="
for /f "tokens=*" %%a in ('"%EMU%" -list-avds 2^>nul') do (
    if not defined FIRST_AVD set "FIRST_AVD=%%a"
)

if not defined FIRST_AVD (
    echo ERROR: No AVDs found.
    echo.
    echo Create one in Android Studio:
    echo   Tools ^> Device Manager ^> + (Create Device)
    echo   Recommended: Pixel 6, API 33 or 34, x86_64 image
    echo.
    exit /b 1
)

:: ── If AVD already running, skip start ───────────────────────
"%ADB%" devices 2>nul | findstr "emulator" >nul
if %ERRORLEVEL% equ 0 (
    echo Emulator already running - reusing it.
    goto :build
)

echo Starting AVD: %FIRST_AVD%
start "" "%EMU%" -avd "%FIRST_AVD%" -no-snapshot-load
echo.
echo Waiting for emulator to boot (may take 60-120 seconds on first cold start)...

"%ADB%" wait-for-device >nul 2>&1

:wait_boot
"%ADB%" shell getprop sys.boot_completed 2>nul | findstr "1" >nul
if %ERRORLEVEL% neq 0 (
    timeout /t 4 /nobreak >nul
    <nul set /p "=."
    goto :wait_boot
)
echo.
echo Emulator ready!
echo.

:build
:: ── Build ────────────────────────────────────────────────────
echo Building debug APK...
pushd "%~dp0.."
call gradlew.bat :app:assembleDebug
if %ERRORLEVEL% neq 0 ( echo Build failed! & popd & exit /b 1 )
popd
echo Build OK.
echo.

:: ── Install + Launch ─────────────────────────────────────────
echo Installing...
"%ADB%" install -r "%APK%"
echo.
echo Launching Better Audio...
"%ADB%" shell am start -n "%PKG%/%ACTIVITY%"
echo.
echo Done! Check the emulator window.
echo.
echo Tip: run scripts\logcat.bat to see live logs.
