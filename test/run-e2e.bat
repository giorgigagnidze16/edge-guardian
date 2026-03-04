@echo off
:: Launches the E2E test script using Git Bash (not WSL bash)
"%ProgramFiles%\Git\usr\bin\bash.exe" "%~dp0run-e2e.sh"
pause