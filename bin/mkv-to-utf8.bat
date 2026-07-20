@echo off
setlocal
groovy "%~dp0..\src\to_utf8.groovy" %*
exit /b %ERRORLEVEL%
