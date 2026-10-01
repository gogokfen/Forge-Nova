@echo off
rem Forge Nova - GPU (WebGL2) client for the Forge rules engine.
rem Starts the headless engine host and opens the game in a chromeless app window.
rem ForgeNova.exe (the player launcher) runs this file without a window and sets NOVA_HIDDEN=1.
setlocal
pushd "%~dp0"

rem The launcher installs its own Java into runtime\java; otherwise use the one on the PATH.
set "JAVA=java"
if exist "runtime\java\bin\java.exe" set "JAVA=%CD%\runtime\java\bin\java.exe"
"%JAVA%" -version 1>nul 2>nul || (
   echo Java was not found. Forge Nova needs Java 21 or newer.
   if not "%NOVA_HIDDEN%"=="1" pause
   popd & exit /b 2
)

set "FORGEJAR="
for %%f in (forge-gui-desktop-*-jar-with-dependencies.jar) do set "FORGEJAR=%%f"
if not defined FORGEJAR (
   echo Forge desktop jar not found. Put this file in your Forge folder.
   if not "%NOVA_HIDDEN%"=="1" pause
   popd & exit /b 1
)

if not exist nova\lib\nova-host.jar (
   echo Building Forge Nova...
   call nova\tools\build.cmd || ( pause & popd & exit /b 1 )
)

rem Engine patches are compiled for one Forge build; skip them if Forge was updated since.
set "PATCHES="
if exist nova\lib\nova-engine-patches.jar (
   set /p BUILTFOR=<nova\lib\patches-built-for.txt
   call :checkpatches
)

set JOPTS=-Xmx4g -XX:+UseZGC -XX:+ZGenerational -Dfile.encoding=UTF-8 ^
  --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED ^
  --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.text=ALL-UNNAMED ^
  --add-opens java.desktop/java.awt=ALL-UNNAMED --add-opens java.desktop/java.beans=ALL-UNNAMED

if not exist nova\logs mkdir nova\logs
if "%NOVA_HIDDEN%"=="1" goto hidden
start "Forge Nova engine" /min cmd /c ""%JAVA%" %JOPTS% -cp "%PATCHES%nova\lib\nova-host.jar;%FORGEJAR%" forge.nova.NovaMain %* > nova\logs\host.log 2>&1"
popd
endlocal
exit /b 0

:hidden
"%JAVA%" %JOPTS% -cp "%PATCHES%nova\lib\nova-host.jar;%FORGEJAR%" forge.nova.NovaMain %* > nova\logs\host.log 2>&1
set "RC=%ERRORLEVEL%"
popd
exit /b %RC%

:checkpatches
if /i "%BUILTFOR%"=="%FORGEJAR%" (
   set "PATCHES=nova\lib\nova-engine-patches.jar;"
) else (
   echo Note: engine speed-ups were built for %BUILTFOR% - running unpatched. Run nova\tools\build.cmd to rebuild them.
)
exit /b 0
