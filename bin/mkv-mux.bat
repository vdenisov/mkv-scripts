@echo off
setlocal
groovy "%~dp0..\src\mux.groovy" %*
exit /b %ERRORLEVEL%
