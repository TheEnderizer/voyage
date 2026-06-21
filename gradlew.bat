@echo off
setlocal enabledelayedexpansion

set "DIRNAME=%~dp0"
if "%DIRNAME%"=="" set "DIRNAME=."
set "APP_HOME=%DIRNAME%"
for %%i in ("%APP_HOME%") do set "APP_HOME=%%~fi"

set "WRAPPER_JAR=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar"

:: ── Bootstrap: download wrapper jar if missing ────────────────────────────
if not exist "%WRAPPER_JAR%" (
    echo [gradlew] gradle-wrapper.jar not found - downloading...
    powershell -NoProfile -Command ^
        "try { Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/gradle/gradle/v8.7.0/gradle/wrapper/gradle-wrapper.jar' -OutFile '%WRAPPER_JAR%' -UseBasicParsing; Write-Output 'OK' } catch { Write-Output ('FAIL: ' + $_.Exception.Message) }"
    if not exist "%WRAPPER_JAR%" (
        echo.
        echo ERROR: Could not download gradle-wrapper.jar.
        echo        Open the project in Android Studio once and it will be generated automatically.
        echo.
        exit /b 1
    )
    echo [gradlew] Downloaded gradle-wrapper.jar
)

:: ── Locate Java ───────────────────────────────────────────────────────────
if defined JAVA_HOME goto :useJavaHome

:: Try Android Studio's bundled JBR (most common on Windows)
for %%p in (
    "%PROGRAMFILES%\Android\Android Studio\jbr"
    "%LOCALAPPDATA%\Programs\Android Studio\jbr"
    "%PROGRAMFILES(X86)%\Android\Android Studio\jbr"
) do (
    if exist "%%~p\bin\java.exe" (
        set "JAVA_HOME=%%~p"
        goto :useJavaHome
    )
)

:: Fall back to java on PATH
where java >nul 2>&1
if %ERRORLEVEL% equ 0 (
    set "JAVA_EXE=java"
    goto :execute
)

echo.
echo ERROR: Java not found.
echo        Install Android Studio (includes a bundled JDK) from:
echo        https://developer.android.com/studio
echo.
exit /b 1

:useJavaHome
set "JAVA_HOME=%JAVA_HOME:"=%"
set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not exist "%JAVA_EXE%" (
    echo ERROR: JAVA_HOME is set but java.exe not found: %JAVA_EXE%
    exit /b 1
)

:execute
set "DEFAULT_JVM_OPTS=-Xmx2048m -Xms512m -Dfile.encoding=UTF-8"
set "CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar"

"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% ^
    "-Dorg.gradle.appname=%~n0" ^
    -classpath "%CLASSPATH%" ^
    org.gradle.wrapper.GradleWrapperMain %*

:end
if %ERRORLEVEL% equ 0 goto :mainEnd
:fail
set "EXIT_CODE=%ERRORLEVEL%"
if %EXIT_CODE% equ 0 set "EXIT_CODE=1"
if not ""=="%GRADLE_EXIT_CONSOLE%" exit %EXIT_CODE%
exit /b %EXIT_CODE%
:mainEnd
endlocal
