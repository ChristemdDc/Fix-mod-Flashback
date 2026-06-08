@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

echo ============================================================
echo   Flashback Connector Fix  -  Compilador
echo ============================================================
echo.

REM ------------------------------------------------------------
REM 1) Comprobar Java (se necesita JDK 21)
REM ------------------------------------------------------------
set "JAVA_EXE=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"

"%JAVA_EXE%" -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] No se encontro Java. Instala un JDK 21 ^(Temurin/Adoptium^)
    echo         y vuelve a ejecutar este archivo.
    echo.
    goto :FIN
)
echo [OK] Java detectado:
"%JAVA_EXE%" -version 2>&1 | findstr /i "version"
echo.

set "GRADLE_VER=8.10.2"

REM ------------------------------------------------------------
REM 2) Asegurar que existe el Gradle Wrapper (gradlew.bat)
REM ------------------------------------------------------------
if exist "%~dp0gradlew.bat" (
    echo [OK] Gradle Wrapper ya presente.
    goto :BUILD
)

echo [..] No hay Gradle Wrapper todavia. Intentando generarlo...
echo.

REM 2a) Si hay un Gradle instalado en el sistema, usarlo para crear el wrapper.
where gradle >nul 2>&1
if not errorlevel 1 (
    echo [..] Usando el Gradle del sistema para crear el wrapper...
    call gradle wrapper --gradle-version %GRADLE_VER%
    if exist "%~dp0gradlew.bat" goto :BUILD
)

REM 2b) Si no hay Gradle, descargar uno temporal (solo la primera vez).
set "BOOT=%~dp0.gradle-bootstrap"
set "GBIN=%BOOT%\gradle-%GRADLE_VER%\bin\gradle.bat"
if not exist "%GBIN%" (
    echo [..] Descargando Gradle %GRADLE_VER% ^(una sola vez, ~130 MB^)...
    if not exist "%BOOT%" mkdir "%BOOT%"
    powershell -NoProfile -ExecutionPolicy Bypass -Command ^
      "try { [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VER%-bin.zip' -OutFile '%BOOT%\gradle.zip' } catch { exit 1 }"
    if errorlevel 1 (
        echo [ERROR] No se pudo descargar Gradle. Revisa tu conexion a internet,
        echo         o abre la carpeta en IntelliJ IDEA ^(importa el proyecto y compila^).
        goto :FIN
    )
    echo [..] Descomprimiendo...
    powershell -NoProfile -ExecutionPolicy Bypass -Command ^
      "try { Expand-Archive -Path '%BOOT%\gradle.zip' -DestinationPath '%BOOT%' -Force } catch { exit 1 }"
    if errorlevel 1 (
        echo [ERROR] No se pudo descomprimir Gradle.
        goto :FIN
    )
)

echo [..] Generando el Gradle Wrapper...
call "%GBIN%" wrapper --gradle-version %GRADLE_VER%
if not exist "%~dp0gradlew.bat" (
    echo [ERROR] No se pudo generar el wrapper.
    goto :FIN
)

:BUILD
echo.
echo ============================================================
echo   Compilando el mod...  ^(la 1a vez tarda: baja NeoForge+MC^)
echo ============================================================
echo.
call "%~dp0gradlew.bat" clean build --stacktrace
set "RESULT=%ERRORLEVEL%"
echo.

if not "%RESULT%"=="0" (
    echo ============================================================
    echo   [X] LA COMPILACION FALLO   ^(codigo %RESULT%^)
    echo ------------------------------------------------------------
    echo   Lee el error de arriba. Causas tipicas:
    echo     - No tienes JDK 21.
    echo     - Sin internet la 1a vez ^(Gradle baja NeoForge y Minecraft^).
    echo   Si el fallo menciona el Mixin, revisa el apartado
    echo   "Si el build falla por el Mixin" del README.md
    echo ============================================================
    goto :FIN
)

REM ------------------------------------------------------------
REM 3) Build OK: localizar el jar y ofrecer copiarlo al modpack
REM ------------------------------------------------------------
set "JAR="
for %%F in ("%~dp0build\libs\flashbackconnectorfix-1.0.0.jar") do if exist "%%~fF" set "JAR=%%~fF"
if not defined JAR (
    for %%F in ("%~dp0build\libs\*.jar") do echo %%~nxF | findstr /v /i "sources" >nul && set "JAR=%%~fF"
)

echo ============================================================
echo   [OK] COMPILACION EXITOSA
echo ------------------------------------------------------------
if defined JAR (
    echo   JAR generado:
    echo     %JAR%
) else (
    echo   Revisa la carpeta build\libs\
)
echo ============================================================
echo.

set "MODS=G:\CurseForge\Instances\Prueba de Modpack para Servidor\mods"
if defined JAR (
    set /p "COPY=Copiar el mod a la carpeta mods del modpack? (S/N): "
    if /i "!COPY!"=="S" (
        copy /Y "!JAR!" "!MODS!\" >nul
        if errorlevel 1 (
            echo [ERROR] No se pudo copiar. Copialo manualmente a:
            echo   !MODS!
        ) else (
            echo [OK] Copiado a:
            echo   !MODS!
        )
    )
)

:FIN
echo.
echo Pulsa una tecla para cerrar...
pause >nul
endlocal
