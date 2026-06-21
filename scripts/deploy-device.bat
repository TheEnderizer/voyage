@echo off
setlocal
echo.
echo ============================================================
echo  Better Audio - Build + Deploy to USB Device
echo ============================================================
echo.

set "SDK=%LOCALAPPDATA%\Android\Sdk"
set "ADB=%SDK%\platform-tools\adb.exe"
set "APK=%~dp0..\app\build\outputs\apk\debug\app-debug.apk"
set "PKG=com.betteraudio"
set "ACTIVITY=com.betteraudio.MainActivity"

:: ── Check ADB ────────────────────────────────────────────────
if not exist "%ADB%" (
    echo ERROR: ADB not found at %ADB%
    echo        Install Android Studio first.
    exit /b 1
)

:: ── Check a device is connected ──────────────────────────────
echo Checking for connected device...
"%ADB%" devices
for /f "skip=1 tokens=1,2" %%a in ('"%ADB%" devices 2^>nul') do (
    if "%%b"=="device" set "DEVICE=%%a"
)
if not defined DEVICE (
    echo.
    echo ERROR: No device found in 'adb devices'.
    echo.
    echo Steps to connect your phone:
    echo  1. Settings ^> About Phone ^> tap Build Number 7 times
    echo  2. Settings ^> Developer Options ^> USB Debugging = ON
    echo  3. Connect phone via USB cable
    echo  4. Accept the "Allow USB debugging?" prompt on the phone
    echo  5. Run this script again
    echo.
    exit /b 1
)
echo Found device: %DEVICE%
echo.

:: ── Build debug APK ──────────────────────────────────────────
echo Building debug APK...
pushd "%~dp0.."
call gradlew.bat :app:assembleDebug
if %ERRORLEVEL% neq 0 (
    echo.
    echo ERROR: Build failed. Fix the errors above and try again.
    popd
    exit /b 1
)
popd
echo Build OK.
echo.

:: ── Install ──────────────────────────────────────────────────
echo Installing on %DEVICE%...
"%ADB%" -s "%DEVICE%" install -r "%APK%"
if %ERRORLEVEL% neq 0 (
    echo ERROR: Install failed.
    exit /b 1
)
echo.

:: ── Launch ───────────────────────────────────────────────────
echo Launching Better Audio...
"%ADB%" -s "%DEVICE%" shell am start -n "%PKG%/%ACTIVITY%"
echo.
echo Done! The app should be open on your phone.
echo.
echo Tip: run scripts\logcat.bat to see live logs.
