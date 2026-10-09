@echo off
setlocal EnableExtensions
set ROOT=%~dp0
set JAR=%ROOT%gradle\wrapper\gradle-wrapper.jar
if exist "%JAR%" (
  java -Dorg.gradle.appname=gradlew -classpath "%JAR%" org.gradle.wrapper.GradleWrapperMain %*
  exit /b %ERRORLEVEL%
)
set VER=9.7.1
set SHA256=acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a
if "%GRADLE_USER_HOME%"=="" (set CACHE=%USERPROFILE%\.gradle\nirmalam-bootstrap) else (set CACHE=%GRADLE_USER_HOME%\nirmalam-bootstrap)
set HOME_DIR=%CACHE%\gradle-%VER%
if not exist "%HOME_DIR%\bin\gradle.bat" (
  if not exist "%CACHE%" mkdir "%CACHE%"
  set ZIP=%CACHE%\gradle-%VER%-bin.zip
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -UseBasicParsing 'https://services.gradle.org/distributions/gradle-%VER%-bin.zip' -OutFile '%CACHE%\gradle-%VER%-bin.zip'; $actual=(Get-FileHash -Algorithm SHA256 '%CACHE%\gradle-%VER%-bin.zip').Hash.ToLowerInvariant(); if ($actual -ne '%SHA256%') { Remove-Item -Force '%CACHE%\gradle-%VER%-bin.zip'; throw 'Gradle distribution checksum mismatch.' }; Expand-Archive -Force '%CACHE%\gradle-%VER%-bin.zip' '%CACHE%'"
  if errorlevel 1 exit /b 1
)
call "%HOME_DIR%\bin\gradle.bat" %*
exit /b %ERRORLEVEL%
