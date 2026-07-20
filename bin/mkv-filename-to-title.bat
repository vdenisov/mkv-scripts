@echo off
setlocal
groovy "%~dp0..\src\filename_to_title.groovy" %*
exit /b %ERRORLEVEL%
