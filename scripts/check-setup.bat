@echo off
setlocal
echo.
echo ============================================================
echo  Better Audio - Setup Checker
echo ============================================================
echo.

set "SDK=%LOCALAPPDATA%\Android\Sdk"
set "ADB=%SDK%\platform-tools\adb.exe"
set "EMU=%SDK%\emulator\emulator.exe"
set "OK=[ OK ]"
set "FAIL=[FAIL]"

:: ── Java ─────────────────────────────────────────────────────
echo --- Java ---
set "JAVA_EXE="
for %%p in (
    "%PROGRAMFILES%\Android\Android Studio\jbr\bin\java.exe"
    "%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\java.exe"
) do ( if exist %%p if not defined JAVA_EXE set "JAVA_EXE=%%~p" )
if not defined JAVA_EXE where java >nul 2>&1 && set "JAVA_EXE=java"
if defined JAVA_EXE (
    echo %OK% Java: %JAVA_EXE%
    "%JAVA_EXE%" -version 2>&1 | findstr /i "version"
) else (
    echo %FAIL% Java not found
    echo         Install Android Studio: https://developer.android.com/studio
)
echo.

:: ── Android SDK ──────────────────────────────────────────────
echo --- Android SDK ---
if exist "%SDK%" (
    echo %OK% SDK root: %SDK%
) else (
    echo %FAIL% SDK not found at %SDK%
    echo         Install Android Studio and it will appear automatically.
)
echo.

:: ── ADB ──────────────────────────────────────────────────────
echo --- ADB (needed for both emulator and USB device) ---
if exist "%ADB%" (
    echo %OK% ADB found
    "%ADB%" version 2>&1 | findstr "Android Debug Bridge"
) else (
    echo %FAIL% ADB not found at %ADB%
)
echo.

:: ── Emulator ─────────────────────────────────────────────────
echo --- Android Emulator ---
if exist "%EMU%" (
    echo %OK% Emulator found
    echo      Available AVDs:
    "%EMU%" -list-avds 2>nul | findstr /r "." && echo         (none yet - create one in Android Studio AVD Manager) >nul 2>&1
) else (
    echo %FAIL% Emulator not found
    echo         Open Android Studio ^> Tools ^> Device Manager ^> Create Device
)
echo.

:: ── Connected USB devices ────────────────────────────────────
echo --- Connected USB devices ---
if exist "%ADB%" (
    "%ADB%" devices 2>nul
    echo.
    echo      To enable USB debugging on your phone:
    echo      Settings ^> About Phone ^> tap Build Number 7x ^> Developer Options ^> USB Debugging ON
) else (
    echo      (ADB not available)
)
echo.

:: ── Gradle wrapper jar ───────────────────────────────────────
echo --- Gradle wrapper ---
if exist "%~dp0..\gradle\wrapper\gradle-wrapper.jar" (
    echo %OK% gradle-wrapper.jar present
) else (
    echo      gradle-wrapper.jar missing - run gradlew.bat once to auto-download it,
    echo      or open the project in Android Studio.
)
echo.

echo ============================================================
echo  If items are FAIL: install Android Studio and reopen.
echo  https://developer.android.com/studio
echo ============================================================
echo.
pause
