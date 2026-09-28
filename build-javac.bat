@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

rem =====================================================================
rem  YinwuServux builder  (Canvas 26.3 / Folia fork, Java 25)
rem
rem  用法：先指定服务器根目录，再运行本脚本
rem      set "YINWU_SERVER_ROOT=D:\myserver"
rem      build-javac.bat
rem
rem  需要该目录下有：
rem      Van\libraries\io\canvasmc\canvas\canvas-api\canvas-api-*.jar  (API)
rem      Van\libraries\ 下全部 jar                                    (adventure/guava/netty/bungee-chat 等)
rem      Van\versions\canvas-*.jar                                    (NMS)
rem =====================================================================

set "OUT_JAR=yinwu-servux-1.0.0.jar"
set "SRC=src\main\java\io\yinwu\servux\ServuxBridgePlugin.java"

if not defined YINWU_SERVER_ROOT (
  echo [ERR] 请先指定服务器根目录，例如：
  echo       set "YINWU_SERVER_ROOT=D:\myserver"
  echo       该目录下应有 Van\libraries\ 与 Van\versions\
  pause
  exit /b 1
)
set "SERVER_ROOT=%YINWU_SERVER_ROOT%"

rem ---------- 1. 找 API jar 和服务端 jar ----------
rem dir /b /s /o-n 在多目录下不会全局排序，所以逐个比文件名取“最大”的那个：
rem canvas-api-26.3.build.956 > build.947。
set "API_JAR="
for /f "delims=" %%F in ('dir /b /s "%SERVER_ROOT%\Van\libraries\io\canvasmc\canvas\canvas-api\canvas-api-*.jar" 2^>nul') do (
  set "CAND=%%~fF"
  if not defined API_JAR set "API_JAR=!CAND!"
  if "!CAND!" GTR "!API_JAR!" set "API_JAR=!CAND!"
)
set "SERVER_JAR="
for /f "delims=" %%F in ('dir /b /s "%SERVER_ROOT%\Van\versions\canvas-*.jar" 2^>nul') do (
  set "CAND=%%~fF"
  if not defined SERVER_JAR set "SERVER_JAR=!CAND!"
  if "!CAND!" GTR "!SERVER_JAR!" set "SERVER_JAR=!CAND!"
)
if not defined API_JAR (
  echo [ERR] 找不到 canvas-api-*.jar，检查 YINWU_SERVER_ROOT
  pause
  exit /b 1
)
if not defined SERVER_JAR (
  echo [ERR] 找不到 Van\versions\canvas-*.jar，检查 YINWU_SERVER_ROOT
  pause
  exit /b 1
)

rem ---------- 2. javac（JDK 25；--release 21 产出 Java 21 字节码）----------
set "JAVAC="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "JAVAC=%JAVA_HOME%\bin\javac.exe"
if not defined JAVAC for /d %%D in ("C:\Program Files\Eclipse Adoptium\jdk-25*") do if not defined JAVAC if exist "%%~fD\bin\javac.exe" set "JAVAC=%%~fD\bin\javac.exe"
if not defined JAVAC if exist "C:\Program Files\Java\jdk-25\bin\javac.exe" set "JAVAC=C:\Program Files\Java\jdk-25\bin\javac.exe"
if not defined JAVAC where javac.exe >nul 2>nul && set "JAVAC=javac.exe"
if not defined JAVAC (
  echo [ERR] 找不到 javac.exe，请装 JDK 25 或设置 JAVA_HOME
  pause
  exit /b 1
)
set "JAR=jar.exe"
for %%D in ("%JAVAC%") do if not "%%~dpD"=="" set "JAR=%%~dpDjar.exe"

echo API    : %API_JAR%
echo Server : %SERVER_JAR%
echo javac  : %JAVAC%
"%JAVAC%" -version
echo.

rem ---------- 3. 编译 ----------
rem 坑：classpath 有 100+ 个 jar（约 1.4 万字符），而 cmd 的 set 变量上限就是 8191 字符、
rem 命令行与 echo 单行同样有上限，直接在批处理里拼 classpath 一定会被截断（表现为
rem “找不到 net.md_5.bungee.api.chat.BaseComponent” 这种莫名的编译错误）。
rem 所以这里交给 PowerShell 收集并写成 @argfile，javac 再读该文件。
if exist out rmdir /s /q out
mkdir out
set "LIBDIR=%SERVER_ROOT%\Van\libraries"
set "OUTDIR=%CD%\out"
set "SRCFILE=%CD%\%SRC%"
set "ARGFILE=%TEMP%\yinwu-servux-javac.txt"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$jars=Get-ChildItem -LiteralPath $env:LIBDIR -Recurse -Filter *.jar | ForEach-Object { $_.FullName }; $cp=((@($env:API_JAR,$env:SERVER_JAR) + $jars) -join ';') -replace '\\','/'; $d=$env:OUTDIR -replace '\\','/'; $s=$env:SRCFILE -replace '\\','/'; $l=@('-encoding','UTF-8','--release','21','-proc:none','-d',$d,'-cp',$cp,$s); [System.IO.File]::WriteAllLines($env:ARGFILE,$l,(New-Object System.Text.UTF8Encoding($false)))"
if errorlevel 1 goto fail
echo [1/2] compiling ...（classpath：API + 服务端 jar + %SERVER_ROOT% 下全部库）
"%JAVAC%" "@%ARGFILE%"
if errorlevel 1 goto fail

rem ---------- 4. 打包 ----------
echo [2/2] packaging ...
"%JAR%" --create --file "%OUT_JAR%" -C out . -C src\main\resources .
if errorlevel 1 goto fail

echo.
echo [OK] %CD%\%OUT_JAR%
echo      复制到 %SERVER_ROOT%\Van\plugins\ 然后重启 Van。
pause
exit /b 0

:fail
echo.
echo [ERR] 构建失败。
echo       若报 "class file has wrong version 69.0"，说明 javac 太旧，必须用 JDK 25。
pause
exit /b 1
