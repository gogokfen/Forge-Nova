@echo off
rem Builds Forge Nova's Java parts against the Forge jar found in the install folder.
rem Output: nova\lib\nova-host.jar and nova\lib\nova-engine-patches.jar
setlocal
pushd "%~dp0..\.."

set "FORGEJAR="
for %%f in (forge-gui-desktop-*-jar-with-dependencies.jar) do set "FORGEJAR=%%f"
if not defined FORGEJAR (
  echo [build] Forge desktop jar not found next to the nova folder.
  popd & exit /b 1
)
where javac >nul 2>nul || (
  echo [build] javac not found - install a JDK 17+ ^(a JRE can run Nova but cannot build it^).
  popd & exit /b 1
)
echo [build] Using %FORGEJAR%

if exist nova\build rmdir /s /q nova\build
mkdir nova\build\host nova\build\patches 2>nul
if not exist nova\lib mkdir nova\lib

rem --- host (everything reachable from the entry point)
javac --release 17 -encoding UTF-8 -nowarn -Xlint:none -cp "%FORGEJAR%" -sourcepath nova/host/src -d nova/build/host nova/host/src/forge/nova/NovaMain.java
if errorlevel 1 ( echo [build] host compilation failed & popd & exit /b 1 )
jar --create --file nova/lib/nova-host.jar -C nova/build/host .
if errorlevel 1 ( popd & exit /b 1 )

rem --- engine patches (optimised replacements of Forge engine classes)
if exist nova\engine-patches\sources.txt (
  javac --release 17 -encoding UTF-8 -nowarn -Xlint:none -cp "%FORGEJAR%" -sourcepath nova/engine-patches/src -d nova/build/patches @nova/engine-patches/sources.txt
  if errorlevel 1 ( echo [build] engine patch compilation failed & popd & exit /b 1 )
  jar --create --file nova/lib/nova-engine-patches.jar -C nova/build/patches .
  echo %FORGEJAR%> nova\lib\patches-built-for.txt
)

echo [build] Done.
popd
endlocal
