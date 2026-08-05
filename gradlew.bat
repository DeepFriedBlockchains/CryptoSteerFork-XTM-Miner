@echo off
setlocal
set DIR=%~dp0
if exist "%USERPROFILE%\.gradle\cryptosteer-wrapper\gradle-8.13\bin\gradle.bat" goto run
powershell -NoProfile -ExecutionPolicy Bypass -Command "$u='https://services.gradle.org/distributions/gradle-8.13-bin.zip';$d=Join-Path $env:USERPROFILE '.gradle\cryptosteer-wrapper';New-Item -ItemType Directory -Force -Path $d|Out-Null;$z=Join-Path $d 'gradle-8.13-bin.zip';Invoke-WebRequest -UseBasicParsing $u -OutFile $z;$h=(Get-FileHash -Algorithm SHA256 $z).Hash.ToLower();if($h -ne '20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78'){throw 'Gradle checksum mismatch'};Expand-Archive -Force $z $d"
:run
call "%USERPROFILE%\.gradle\cryptosteer-wrapper\gradle-8.13\bin\gradle.bat" -p "%DIR%" %*
