@echo off
rem Launcher for jartree-compare; build first with: mvn package
if "%JAVA_OPTS%"=="" set JAVA_OPTS=-Xmx4g
java %JAVA_OPTS% -jar "%~dp0target\jartree-compare.jar" %*
