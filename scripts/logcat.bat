@echo off
setlocal
echo.
echo ============================================================
echo  Better Audio - Live Logcat  (Ctrl+C to stop)
echo ============================================================
echo.

set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
set "PKG=com.betteraudio"

if not exist "%ADB%" (
    echo ERROR: ADB not found. Install Android Studio first.
    exit /b 1
)

:: Clear old log first
"%ADB%" logcat -c

:: Filter to our app only (pid-based is most reliable)
echo Filtering logs for package: %PKG%
echo.
"%ADB%" logcat --pid=$("%ADB%" shell pidof -s %PKG% 2>nul) 2>nul
if %ERRORLEVEL% neq 0 (
    :: Fallback: tag filter
    "%ADB%" logcat -s BetterAudio:V AndroidRuntime:E System.err:W *:S
)
