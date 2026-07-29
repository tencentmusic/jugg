@echo off
setlocal enabledelayedexpansion

set GRADLE_PROPERTIES=gradle.properties
set GRADLE_WRAPPER=gradle\wrapper\gradle-wrapper.properties

set KOTLIN_1_7_kotlinVersion=1.7.21
set KOTLIN_1_7_kspVersion=1.7.21-1.0.8
set KOTLIN_1_7_composeCompilerVersion=1.4.0-alpha02
set KOTLIN_1_7_agpVersion=7.2.2
set KOTLIN_1_7_gradleVersion=7.3.3
set KOTLIN_1_7_excludeKmpCompose=true

set KOTLIN_1_9_kotlinVersion=1.9.22
set KOTLIN_1_9_kspVersion=1.9.22-1.0.17
set KOTLIN_1_9_composeVersion=1.6.0
set KOTLIN_1_9_composeCompilerVersion=1.5.8
set KOTLIN_1_9_agpVersion=7.3.1
set KOTLIN_1_9_gradleVersion=7.4
set KOTLIN_1_9_excludeKmpCompose=false

set KOTLIN_2_1_kotlinVersion=2.1.0
set KOTLIN_2_1_kspVersion=2.1.0-1.0.29
set KOTLIN_2_1_composeVersion=1.7.3
set KOTLIN_2_1_composeCompilerVersion=1.5.15
set KOTLIN_2_1_agpVersion=8.0.2
set KOTLIN_2_1_gradleVersion=8.0
set KOTLIN_2_1_excludeKmpCompose=false

set KOTLIN_2_3_kotlinVersion=2.3.20
set KOTLIN_2_3_kspVersion=2.3.9
set KOTLIN_2_3_composeVersion=1.10.3
set KOTLIN_2_3_composeCompilerVersion=2.3.20
set KOTLIN_2_3_agpVersion=8.13.2
set KOTLIN_2_3_gradleVersion=8.13
set KOTLIN_2_3_excludeKmpCompose=false

set KOTLIN_2_3_AGP9_kotlinVersion=2.3.0
set KOTLIN_2_3_AGP9_kspVersion=2.3.4
set KOTLIN_2_3_AGP9_composeCompilerVersion=2.3.0
set KOTLIN_2_3_AGP9_agpVersion=9.0.0
set KOTLIN_2_3_AGP9_gradleVersion=9.4.0
set KOTLIN_2_3_AGP9_excludeKmpCompose=true

if "%1"=="" goto show_version
if "%1"=="show" goto show_version
if "%1"=="current" goto show_version
if "%1"=="1.7" goto update_1_7
if "%1"=="kotlin1.7" goto update_1_7
if "%1"=="legacy" goto update_1_7
if "%1"=="1.9" goto update_1_9
if "%1"=="kotlin1.9" goto update_1_9
if "%1"=="2.1" goto update_2_1
if "%1"=="kotlin2.1" goto update_2_1
if "%1"=="2.3" goto update_2_3
if "%1"=="kotlin2.3" goto update_2_3
if "%1"=="latest" goto update_2_3
if "%1"=="2.3-agp9" goto update_2_3_agp9
if "%1"=="kotlin2.3-agp9" goto update_2_3_agp9
if "%1"=="help" goto show_help
if "%1"=="-h" goto show_help
if "%1"=="--help" goto show_help

echo Error: unknown option '%1'
exit /b 1

:update_1_7
call :update_profile KOTLIN_1_7 kotlin1.7 false
goto end

:update_1_9
call :update_profile KOTLIN_1_9 kotlin1.9 true
goto end

:update_2_1
call :update_profile KOTLIN_2_1 kotlin2.1 true
goto end

:update_2_3
call :update_profile KOTLIN_2_3 kotlin2.3 true
goto end

:update_2_3_agp9
call :update_profile KOTLIN_2_3_AGP9 kotlin2.3-agp9 false
goto end

:update_profile
set PREFIX=%1
set SUFFIX=%2
set INCLUDE_KMP_COMPOSE=%3
for %%p in (kotlinVersion kspVersion composeCompilerVersion agpVersion excludeKmpCompose) do (
    call :update_property %%p !%PREFIX%_%%p!
)
if "%INCLUDE_KMP_COMPOSE%"=="true" (
    call :update_property composeVersion !%PREFIX%_composeVersion!
) else (
    call :remove_property composeVersion
)
call :update_gradle_wrapper !%PREFIX%_gradleVersion!
for %%f in (build.gradle app\build.gradle library1\build.gradle) do (
    if not exist "%%f.%SUFFIX%" (
        echo Error: missing Gradle template %%f.%SUFFIX%
        exit /b 1
    )
    copy /Y "%%f.%SUFFIX%" "%%f" >nul
    echo Switched %%f
)
if "%INCLUDE_KMP_COMPOSE%"=="true" (
    if not exist "kmpCompose\build.gradle.%SUFFIX%" (
        echo Error: missing Gradle template kmpCompose\build.gradle.%SUFFIX%
        exit /b 1
    )
    copy /Y "kmpCompose\build.gradle.%SUFFIX%" "kmpCompose\build.gradle" >nul
    echo Switched kmpCompose\build.gradle
)
call :show_version
echo Gradle: !%PREFIX%_gradleVersion!
goto :eof

:remove_property
set KEY=%1
set TEMP_FILE=%GRADLE_PROPERTIES%.tmp
(for /f "usebackq delims=" %%a in ("%GRADLE_PROPERTIES%") do (
    set "line=%%a"
    echo !line! | findstr /B /C:"%KEY%=" >nul
    if !errorlevel! neq 0 echo !line!
)) > "%TEMP_FILE%"
move /Y "%TEMP_FILE%" "%GRADLE_PROPERTIES%" >nul
goto :eof

:update_property
set KEY=%1
set VALUE=%2
set TEMP_FILE=%GRADLE_PROPERTIES%.tmp
set FOUND=0
(for /f "usebackq delims=" %%a in ("%GRADLE_PROPERTIES%") do (
    set "line=%%a"
    echo !line! | findstr /B /C:"%KEY%=" >nul
    if !errorlevel! equ 0 (
        echo %KEY%=%VALUE%
        set FOUND=1
    ) else (
        echo !line!
    )
)) > "%TEMP_FILE%"
if !FOUND! equ 0 echo %KEY%=%VALUE%>>"%TEMP_FILE%"
move /Y "%TEMP_FILE%" "%GRADLE_PROPERTIES%" >nul
goto :eof

:update_gradle_wrapper
set GRADLE_VERSION=%1
set TEMP_FILE=%GRADLE_WRAPPER%.tmp
(for /f "usebackq delims=" %%a in ("%GRADLE_WRAPPER%") do (
    set "line=%%a"
    echo !line! | findstr /B /C:"distributionUrl=" >nul
    if !errorlevel! equ 0 (
        echo distributionUrl=https\://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-all.zip
    ) else (
        echo !line!
    )
)) > "%TEMP_FILE%"
move /Y "%TEMP_FILE%" "%GRADLE_WRAPPER%" >nul
goto :eof

:show_version
echo Current version configuration:
findstr /R "^kotlinVersion= ^kspVersion= ^composeVersion= ^composeCompilerVersion= ^agpVersion= ^excludeKmpCompose=" %GRADLE_PROPERTIES%
goto :eof

:show_help
echo Usage: %~nx0 [1.7^|1.9^|2.1^|2.3^|2.3-agp9^|show]
goto end

:end
endlocal
