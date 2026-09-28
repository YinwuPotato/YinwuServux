@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

rem =====================================================================
rem  YinwuServux builder  (Canvas 26.3 / Folia fork, Java 25)
rem
rem  编译依赖：
rem    <SERVER_ROOT>\Van\libraries\...\canvas-api-*.jar   （Bukkit/Paper API + Canvas 区域 API）
rem    <SERVER_ROOT>\Van\libraries\ 下全部 jar             （adventure / guava / netty 等传递依赖）
rem    <SERVER_ROOT>\Van\versions\26.3\canvas-26.3.jar     （NMS：CompoundTag / BlockEntity / FriendlyByteBuf）
rem =====================================================================

set "OUT_JAR=yinwu-servux-1.0.0.jar"

rem 服务器根目录：用环境变量 YINWU_SERVER_ROOT 指定（下面第二节会检查）。
rem 脚本需要该目录下的 Van\libraries\（API + 依赖）和 Van\versions\（服务端 jar）。
set "SERVER_ROOT=%YINWU_SERVER_ROOT%"

rem ---------- 1. 找 API jar 和服务端 jar ----------
if not defined SERVER_ROOT (
  echo [ERR] 请先指定服务器根目录，例如：
  echo       set "YINWU_SERVER_ROOT=D:\myserver"
  echo       该目录下应有 Van\libraries\ 与 Van\versions\
  pause
  exit /b 1
)
set "API_JAR="
for /f "delims=" %%F in ('dir /b /s /o-d "%SERVER_ROOT%\Van\libraries\io\canvasmc\canvas\canvas-api\canvas-api-*.jar" 2^>nul') do (
  if not defined API_JAR set "API_JAR=%%~fF"
)
set "SERVER_JAR="
for /f "delims=" %%F in ('dir /b /s /o-d "%SERVER_ROOT%\Van\versions\canvas-*.jar" 2^>nul') do (
  if not defined SERVER_JAR set "SERVER_JAR=%%~fF"
)
if not defined API_JAR (
  echo [ERR] 找不到 canvas-api-*.jar，检查 SERVER_ROOT 变量
  pause
  exit /b 1
)
if not defined SERVER_JAR (
  echo [ERR] 找不到 Van\versions\canvas-*.jar，检查 SERVER_ROOT 变量
  pause
  exit /b 1
)

rem ---------- 2. javac（必须 JDK 25）----------
set "JAVAC="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "JAVAC=%JAVA_HOME%\bin\javac.exe"
if not defined JAVAC (
  for /d %%D in ("C:\Program Files\Eclipse Adoptium\jdk-25*") do (
    if not defined JAVAC if exist "%%~fD\bin\javac.exe" set "JAVAC=%%~fD\bin\javac.exe"
  )
)
if not defined JAVAC if exist "C:\Program Files\Java\jdk-25\bin\javac.exe" set "JAVAC=C:\Program Files\Java\jdk-25\bin\javac.exe"
if not defined JAVAC (
  where javac.exe >nul 2>nul && set "JAVAC=javac.exe"
)
if not defined JAVAC (
  echo [ERR] 找不到 javac.exe，请装 JDK 25 或设置 JAVA_HOME
  pause
  exit /b 1
)

set "CP=%API_JAR%;%SERVER_JAR%"
for /r "%SERVER_ROOT%\Van\libraries" %%F in (*.jar) do set "CP=!CP!;%%F"
set "JAR=jar.exe"
for %%D in ("%JAVAC%") do if not "%%~dpD"=="" set "JAR=%%~dpDjar.exe"

echo API    : %API_JAR%
echo Server : %SERVER_JAR%
echo javac  : %JAVAC%
"%JAVAC%" -version
echo.

rem ---------- 3. 编译 ----------
if exist out rmdir /s /q out
mkdir out
echo [1/2] compiling ...
"%JAVAC%" -encoding UTF-8 --release 21 -proc:none -cp "!CP!" -d out src\main\java\io\yinwu\servux\ServuxBridgePlugin.java
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
