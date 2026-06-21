@echo off
echo Building Better Audio debug APK...
pushd "%~dp0.."
call gradlew.bat :app:assembleDebug %*
if %ERRORLEVEL% equ 0 (
    echo.
    echo APK: app\build\outputs\apk\debug\app-debug.apk
)
popd
