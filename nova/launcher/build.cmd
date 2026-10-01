@echo off
rem Builds the player launcher: nova\build\launcher\ForgeNova.exe
rem Uses the C# compiler that ships with the .NET Framework on every Windows 10/11, so no SDK is needed.
setlocal
pushd "%~dp0"
set "CSC=%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if not exist "%CSC%" set "CSC=%WINDIR%\Microsoft.NET\Framework\v4.0.30319\csc.exe"
if not exist "%CSC%" ( echo [launcher] .NET Framework 4 C# compiler not found. & popd & exit /b 1 )
if not exist ..\build\launcher mkdir ..\build\launcher
"%CSC%" /nologo /target:winexe /optimize+ /platform:anycpu /out:..\build\launcher\ForgeNova.exe ^
  /win32icon:icon.ico /win32manifest:app.manifest /resource:icon-256.png,ForgeNova.icon.png ^
  /r:System.dll /r:System.Drawing.dll /r:System.Windows.Forms.dll /r:System.Web.Extensions.dll ^
  /r:System.IO.Compression.dll /r:System.IO.Compression.FileSystem.dll ^
  ForgeNova.cs
if errorlevel 1 ( echo [launcher] compilation failed & popd & exit /b 1 )
echo [launcher] Built nova\build\launcher\ForgeNova.exe
popd
endlocal
