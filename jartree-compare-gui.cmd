@echo off
rem Starts the jartree-compare GUI; build first with: mvn package
if "%JAVA_OPTS%"=="" set JAVA_OPTS=-Xmx4g
start "" javaw %JAVA_OPTS% -jar "%~dp0target\jartree-compare.jar" --gui %*
