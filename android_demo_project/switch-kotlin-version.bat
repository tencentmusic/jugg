@echo off
REM Kotlin version switch script (Windows)
REM Usage: switch-kotlin-version.bat [2.1|1.7|legacy]

setlocal enabledelayedexpansion

set GRADLE_PROPERTIES=gradle.properties
set GRADLE_WRAPPER=gradle\wrapper\gradle-wrapper.properties
set ROOT_BUILD_GRADLE=build.gradle
set APP_BUILD_GRADLE=app\build.gradle
set LIBRARY1_BUILD_GRADLE=library1\build.gradle

REM Version config - Kotlin 2.1
set KOTLIN_2_1_kotlinVersion=2.1.0
set KOTLIN_2_1_kspVersion=2.1.0-1.0.29
set KOTLIN_2_1_composeCompilerVersion=1.5.15
set KOTLIN_2_1_agpVersion=8.0.2
set KOTLIN_2_1_gradleVersion=8.0

REM Version config - Kotlin 1.7
set KOTLIN_1_7_kotlinVersion=1.7.21
set KOTLIN_1_7_kspVersion=1.7.21-1.0.8
set KOTLIN_1_7_composeCompilerVersion=1.4.0-alpha02
set KOTLIN_1_7_agpVersion=7.2.2
set KOTLIN_1_7_gradleVersion=7.3.3

REM Show current version
if "%1"=="" goto show_version
if "%1"=="show" goto show_version
if "%1"=="current" goto show_version
if "%1"=="2.1" goto update_2_1
if "%1"=="kotlin2.1" goto update_2_1
if "%1"=="latest" goto update_2_1
if "%1"=="1.7" goto update_1_7
if "%1"=="kotlin1.7" goto update_1_7
if "%1"=="legacy" goto update_1_7
if "%1"=="help" goto show_help
if "%1"=="-h" goto show_help
if "%1"=="--help" goto show_help

echo Error: unknown option '%1'
echo Run '%~nx0 help' for help
exit /b 1

:show_version
echo Current version configuration:
findstr /R "^kotlinVersion= ^kspVersion= ^composeCompilerVersion= ^agpVersion=" %GRADLE_PROPERTIES% 2>nul
if errorlevel 1 echo Version configuration not found
goto end

:update_2_1
echo Switching to Kotlin 2.1.0...
echo.
call :update_properties KOTLIN_2_1 kotlin2.1
goto end

:update_1_7
echo Switching to Kotlin 1.7.21...
echo.
call :update_properties KOTLIN_1_7 kotlin1.7
goto end

:update_properties
set PREFIX=%1
set VERSION_SUFFIX=%2

REM Create temp file
set TEMP_FILE=%GRADLE_PROPERTIES%.tmp
if exist %TEMP_FILE% del %TEMP_FILE%

REM Update version properties
echo Updating gradle.properties...
set "properties=kotlinVersion kspVersion composeCompilerVersion agpVersion"
for %%p in (%properties%) do (
    call :update_property %%p !%PREFIX%_%%p!
)

REM Update Gradle wrapper version
echo Updating Gradle wrapper to !%PREFIX%_gradleVersion!...
call :update_gradle_wrapper !%PREFIX%_gradleVersion!

REM Switch build.gradle files
echo Switching build.gradle files to %VERSION_SUFFIX%...
call :switch_build_gradle_files %VERSION_SUFFIX%

echo.
echo Version switch successful!
echo.
echo Current version configuration:
findstr /R "^kotlinVersion= ^kspVersion= ^composeCompilerVersion= ^agpVersion=" %GRADLE_PROPERTIES%
echo   Gradle: !%PREFIX%_gradleVersion!
echo.
echo Tip: run the following commands to clean build cache:
echo   gradlew clean
echo   rmdir /s /q .gradle build app\build library1\build buildSrc\build
goto :eof

:update_property
set PROP_NAME=%1
set PROP_VALUE=%2

REM Read file and update property
set FOUND=0
(for /f "usebackq delims=" %%a in ("%GRADLE_PROPERTIES%") do (
    set "line=%%a"
    echo !line! | findstr /B /C:"%PROP_NAME%=" >nul
    if !errorlevel! equ 0 (
        echo %PROP_NAME%=%PROP_VALUE%
        set FOUND=1
    ) else (
        echo !line!
    )
)) > %TEMP_FILE%

REM Add property if it does not exist
if !FOUND! equ 0 (
    echo %PROP_NAME%=%PROP_VALUE% >> %TEMP_FILE%
)

REM Replace original file
move /Y %TEMP_FILE% %GRADLE_PROPERTIES% >nul
goto :eof

:update_gradle_wrapper
set GRADLE_VERSION=%1
set GRADLE_URL=https\\://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-all.zip

REM Create temp file
set TEMP_FILE=%GRADLE_WRAPPER%.tmp
if exist %TEMP_FILE% del %TEMP_FILE%

REM Read file and update distributionUrl
(for /f "usebackq delims=" %%a in ("%GRADLE_WRAPPER%") do (
    set "line=%%a"
    echo !line! | findstr /B /C:"distributionUrl=" >nul
    if !errorlevel! equ 0 (
        echo distributionUrl=%GRADLE_URL%
    ) else (
        echo !line!
    )
)) > %TEMP_FILE%

REM Replace original file
move /Y %TEMP_FILE% %GRADLE_WRAPPER% >nul
goto :eof

:switch_build_gradle_files
set VERSION_SUFFIX=%1

REM Switch root build.gradle
if exist "%ROOT_BUILD_GRADLE%.%VERSION_SUFFIX%" (
    copy /Y %ROOT_BUILD_GRADLE%.%VERSION_SUFFIX% %ROOT_BUILD_GRADLE% >nul
    echo   Switched %ROOT_BUILD_GRADLE%
) else (
    echo   Warning: %ROOT_BUILD_GRADLE%.%VERSION_SUFFIX% not found
)

REM Switch app/build.gradle
if exist "%APP_BUILD_GRADLE%.%VERSION_SUFFIX%" (
    copy /Y %APP_BUILD_GRADLE%.%VERSION_SUFFIX% %APP_BUILD_GRADLE% >nul
    echo   Switched %APP_BUILD_GRADLE%
) else (
    echo   Warning: %APP_BUILD_GRADLE%.%VERSION_SUFFIX% not found
)

REM Switch library1/build.gradle
if exist "%LIBRARY1_BUILD_GRADLE%.%VERSION_SUFFIX%" (
    copy /Y %LIBRARY1_BUILD_GRADLE%.%VERSION_SUFFIX% %LIBRARY1_BUILD_GRADLE% >nul
    echo   Switched %LIBRARY1_BUILD_GRADLE%
) else (
    echo   Warning: %LIBRARY1_BUILD_GRADLE%.%VERSION_SUFFIX% not found
)

goto :eof

:show_help
echo Kotlin version switch script
echo.
echo Usage: %~nx0 [option]
echo.
echo Options:
echo   2.1, latest       Switch to Kotlin 2.1.0 (use new Compose plugin)
echo   1.7, legacy       Switch to Kotlin 1.7.21 (legacy version)
echo   show, current     Show current version
echo   help              Show this help message
echo.
echo Examples:
echo   %~nx0 2.1         # Switch to Kotlin 2.1
echo   %~nx0 legacy      # Switch back to Kotlin 1.7.21
echo   %~nx0 show        # Show current version
echo.
echo Notes:
echo   - Kotlin 2.1.0 uses the new Compose compiler plugin (org.jetbrains.kotlin.plugin.compose)
echo   - Kotlin 1.7.21 uses the legacy composeOptions.kotlinCompilerExtensionVersion setting
echo   - Switching versions automatically replaces the corresponding build.gradle files
goto end

:end
endlocal
