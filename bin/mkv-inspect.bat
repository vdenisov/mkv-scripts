@echo off
setlocal
groovy "%~dp0..\src\inspect.groovy" %*
exit /b %ERRORLEVEL%
