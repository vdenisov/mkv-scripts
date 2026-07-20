@echo off
setlocal
groovy "%~dp0..\src\fix_srt.groovy" %*
exit /b %ERRORLEVEL%
