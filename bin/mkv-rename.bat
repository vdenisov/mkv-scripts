@echo off
setlocal
groovy "%~dp0..\src\rename.groovy" %*
exit /b %ERRORLEVEL%
