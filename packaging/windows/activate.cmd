@echo off
rem Run by the installer via Inno Setup's ExecAndCaptureOutput (see komm-server-launcher.iss)
rem rather than shown in a window of its own - its stdout/stderr get captured and, on
rem failure, shown inside the installer's own message box. Kept deliberately quiet on
rem success: the installer's own progress page is what the user actually sees while
rem this runs.
call "%~dp0kommserver.cmd" install-service
if errorlevel 1 exit /b 1
call "%~dp0kommserver.cmd" start

rem The auto-restart-for-TLS/ports cycle (see StartupValidator in komm-server) needs
rem time to land before a status check means anything - a fixed wait was guessing at
rem that time and could easily show a stale port/state if the actual boot (embedded
rem Postgres, JWT keys, etc.) took longer than the guess. Poll instead, up to 30
rem seconds, and stop as soon as the server actually answers.
set attempts=0
:pollloop
set /a attempts+=1
"%~dp0kommserver.cmd" status | findstr /C:"accepting connections" >nul
if %errorlevel%==0 goto done
if %attempts% geq 15 goto done
timeout /t 2 /nobreak >nul
goto pollloop
:done

call "%~dp0kommserver.cmd" status
exit /b 0
