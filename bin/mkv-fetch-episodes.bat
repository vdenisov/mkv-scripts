@echo off
setlocal
groovy "%~dp0..\src\fetch_episodes.groovy" %*
exit /b %ERRORLEVEL%
