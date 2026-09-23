@echo off
rem Builds a Windows application with jpackage (JDK 17+).
rem
rem   packaging\package.cmd            -^> target\dist\jartree-compare\jartree-compare.exe (no installer needed)
rem   packaging\package.cmd msi        -^> installer (needs the WiX toolset on the PATH)
rem
rem Run "mvn package" first: the shaded jar contains the JavaFX binaries of this platform.
setlocal
cd /d "%~dp0.."

set TYPE=%1
if "%TYPE%"=="" set TYPE=app-image

if not exist target\jartree-compare.jar (
    echo target\jartree-compare.jar is missing; run "mvn package" first
    exit /b 1
)

rem jpackage only accepts numeric versions, and copies the whole --input directory
for /f "usebackq delims=" %%v in (`powershell -NoProfile -ExecutionPolicy Bypass -File packaging\version.ps1`) do set VERSION=%%v
if "%VERSION%"=="" set VERSION=1.0.0

if exist target\jpackage-input rmdir /s /q target\jpackage-input
if exist target\dist rmdir /s /q target\dist
mkdir target\jpackage-input
copy /y target\jartree-compare.jar target\jpackage-input >nul

jpackage ^
    --type %TYPE% ^
    --name jartree-compare ^
    --app-version %VERSION% ^
    --description "Compares two hierarchies of jar files and shows the decompiled code changes" ^
    --vendor "jartree-compare" ^
    --copyright "MIT License" ^
    --icon packaging\jartree-compare.ico ^
    --input target\jpackage-input ^
    --main-jar jartree-compare.jar ^
    --main-class io.jartree.Main ^
    --java-options "-Xmx4g" ^
    --add-launcher jartree-compare-cli=packaging\cli-launcher.properties ^
    --dest target\dist
if errorlevel 1 exit /b 1

echo.
echo created target\dist\jartree-compare\jartree-compare.exe
endlocal
