@echo off
setlocal
groovy "%~dp0..\src\find_unused_fonts.groovy" %*
exit /b %ERRORLEVEL%
