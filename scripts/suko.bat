@echo off
setlocal enabledelayedexpansion
rem Minimal wrapper: finds the suko-cli fat jar next to this script (or in
rem this repo's suko-cli\build\libs\, for a checkout of the source repo)
rem and runs it with "java -jar". See scripts\suko (the POSIX-shell twin
rem of this script) and jbang-catalog.md for the jbang-based alternative.

where java >nul 2>nul
if errorlevel 1 (
    echo suko: "java" was not found on PATH. Install a Java 21+ runtime ^(or use 1>&2
    echo "jbang suko@<owner>" instead, which manages its own JDK^) and try again. 1>&2
    exit /b 1
)

set "SCRIPT_DIR=%~dp0"
set "JAR="
for /f "delims=" %%F in ('dir /b /o-n "%SCRIPT_DIR%suko-cli-*-all.jar" 2^>nul') do (
    if not defined JAR set "JAR=%SCRIPT_DIR%%%F"
)
if not defined JAR (
    for /f "delims=" %%F in ('dir /b /o-n "%SCRIPT_DIR%..\suko-cli\build\libs\suko-cli-*-all.jar" 2^>nul') do (
        if not defined JAR set "JAR=%SCRIPT_DIR%..\suko-cli\build\libs\%%F"
    )
)

if not defined JAR (
    echo suko: could not find a suko-cli-^<version^>-all.jar next to this script, 1>&2
    echo nor under ..\suko-cli\build\libs\. Build one with "gradle :suko-cli:fatJar" 1>&2
    echo or download a release asset and place it next to this script. 1>&2
    exit /b 1
)

java -jar "%JAR%" %*
exit /b %ERRORLEVEL%
