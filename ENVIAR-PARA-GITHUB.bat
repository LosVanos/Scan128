\
@echo off
title Scan128 - Enviar para GitHub
echo.
echo ==========================================
echo   Scan128 - Upload automatico para GitHub
echo ==========================================
echo.

where git >nul 2>nul
if errorlevel 1 (
    echo ERRO: O Git nao esta instalado neste PC.
    echo Instala o Git for Windows e volta a executar este ficheiro.
    echo https://git-scm.com/download/win
    pause
    exit /b 1
)

set /p REPOURL=Cola aqui o URL HTTPS do teu repositorio GitHub: 

if "%REPOURL%"=="" (
    echo Nao introduziste nenhum URL.
    pause
    exit /b 1
)

echo.
echo A preparar o repositorio...

if exist .git rmdir /s /q .git

git init
git branch -M main
git add .
git commit -m "Scan128 V2.1 ROI scanner"
git remote add origin %REPOURL%
git push -u origin main --force

echo.
if errorlevel 1 (
    echo O upload falhou. Confirma se tens sessao iniciada no GitHub e se o URL esta correto.
) else (
    echo ==========================================
    echo   CONCLUIDO!
    echo ==========================================
    echo Agora abre o repositorio no GitHub ^> Actions.
    echo O workflow "Build Scan128 APK" deve iniciar automaticamente.
    echo Quando terminar, descarrega o artifact "Scan128-APK".
)
echo.
pause
