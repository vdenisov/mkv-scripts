@echo off
setlocal
groovy "%~dp0..\src\propedit.groovy" %*
exit /b %ERRORLEVEL%
