@echo off
cd /d "%~dp0"
python build.py --bundle --bump-version
exit /b %errorlevel%
