@echo off
chcp 65001 >nul
cd /d "%~dp0"

set "JAVAW="
if exist "%~dp0jre\bin\javaw.exe" set "JAVAW=%~dp0jre\bin\javaw.exe"
if not defined JAVAW if exist "%~dp0tools\jdk-21.0.12.1+1\bin\javaw.exe" set "JAVAW=%~dp0tools\jdk-21.0.12.1+1\bin\javaw.exe"
if not defined JAVAW (
  where javaw >nul 2>nul && set "JAVAW=javaw"
)
if not defined JAVAW (
  echo 没有找到 Java 运行时。请安装 Java 21，或把裁剪后的 jre 放到本目录的 jre\ 下。
  pause
  exit /b 1
)

start "" "%JAVAW%" -Dmcl.home="%~dp0" -jar "%~dp0MCL.jar"
