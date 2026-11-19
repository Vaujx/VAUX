@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"
REM  VAUX builder - no Android Studio needed.
REM  Usage:  build.bat [debug|release|install|clean]     (default: debug)
set "SDK=%USERPROFILE%\vaux-sdk"
set "ANDROID_HOME=%SDK%"
set "ANDROID_SDK_ROOT=%SDK%"
set "PATH=%SDK%\cmdline-tools\latest\bin;%SDK%\platform-tools;%PATH%"
set "CMD=%~1"
if "%CMD%"=="" set "CMD=debug"

if not exist settings.gradle.kts (echo [!] settings.gradle.kts is missing. Extract the full VAUX.zip again, keeping folders. & exit /b 1)
if not exist app\build.gradle.kts (echo [!] app\build.gradle.kts is missing. Extract the full VAUX.zip again, keeping folders. & exit /b 1)
if not exist app\src\main\java\app\vaux\MainActivity.kt (echo [!] Source files missing. Extract the full VAUX.zip again. & exit /b 1)
if not exist app\src\main\res\drawable mkdir app\src\main\res\drawable
where java >nul 2>nul || (echo [!] JDK 17 not found. Install it from https://adoptium.net then reopen this window. & exit /b 1)

if not exist "%SDK%\platforms\android-35" (
  echo [1/3] Installing Android SDK command-line tools ^(one time^)...
  if not exist "%SDK%\cmdline-tools\latest" (
    mkdir "%SDK%\cmdline-tools" 2>nul
    powershell -NoProfile -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip -OutFile $env:TEMP\clt.zip; Expand-Archive $env:TEMP\clt.zip $env:TEMP\clt -Force" || exit /b 1
    move "%TEMP%\clt\cmdline-tools" "%SDK%\cmdline-tools\latest" >nul
  )
  (for /l %%i in (1,1,30) do @echo y) | call sdkmanager --licenses >nul
  call sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0" || exit /b 1
)

if not exist gradlew.bat (
  echo [2/3] Creating Gradle wrapper ^(one time^)...
  if not exist "%TEMP%\gradle-8.10.2\bin\gradle.bat" powershell -NoProfile -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest https://services.gradle.org/distributions/gradle-8.10.2-bin.zip -OutFile $env:TEMP\gradle.zip; Expand-Archive $env:TEMP\gradle.zip $env:TEMP -Force" || exit /b 1
  rmdir /s /q "%TEMP%\vaux-w" 2>nul
  mkdir "%TEMP%\vaux-w"
  echo // temp> "%TEMP%\vaux-w\settings.gradle"
  pushd "%TEMP%\vaux-w"
  call "%TEMP%\gradle-8.10.2\bin\gradle.bat" wrapper --gradle-version 8.10.2 || (popd & exit /b 1)
  popd
  copy /y "%TEMP%\vaux-w\gradlew.bat" . >nul
  copy /y "%TEMP%\vaux-w\gradlew" . >nul
  xcopy /e /i /y "%TEMP%\vaux-w\gradle" gradle >nul
)

echo sdk.dir=%SDK:\=/%> local.properties

REM --- custom icon: drop icon\icon.png (1024x1024, transparent) ---
set "RD=app\src\main\res\drawable"
if exist icon\icon.png (
  if exist "%RD%\ic_fg.xml" del "%RD%\ic_fg.xml"
  copy /y icon\icon.png "%RD%\ic_fg.png" >nul
  echo Using custom icon.
) else (
  if exist "%RD%\ic_fg.png" del "%RD%\ic_fg.png"
  copy /y icon\placeholder_fg.xml "%RD%\ic_fg.xml" >nul
)

set "G=--no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1g"

if /i "%CMD%"=="clean" ( call gradlew.bat %G% clean & exit /b !errorlevel! )

if /i "%CMD%"=="release" (
  if not exist vaux.jks (
    echo [3/3] First release: creating your signing key. KEEP vaux.jks AND THE PASSWORD SAFE. You need them for every update.
    set /p PW=Choose a key password ^(6+ chars^): 
    keytool -genkeypair -v -keystore vaux.jks -alias vaux -keyalg RSA -keysize 2048 -validity 10000 -storepass !PW! -keypass !PW! -dname "CN=VAUX" || exit /b 1
    (echo file=vaux.jks& echo pass=!PW!)> keystore.properties
  )
  call gradlew.bat %G% assembleRelease || exit /b 1
  set "OUT=app\build\outputs\apk\release\app-release.apk"
) else if /i "%CMD%"=="install" (
  call gradlew.bat %G% installDebug || exit /b 1
  echo Installed on your connected phone. & exit /b 0
) else (
  call gradlew.bat %G% assembleDebug || exit /b 1
  set "OUT=app\build\outputs\apk\debug\app-debug.apk"
)
if not exist dist mkdir dist
copy /y "!OUT!" dist\VAUX-%CMD%.apk >nul
echo.
echo DONE  ->  %~dp0dist\VAUX-%CMD%.apk
