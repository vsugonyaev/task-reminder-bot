@echo off
rem Runs the bot in a loop: if it exits (crash, no network at startup), restarts it in 15 seconds.
rem Started by the Windows Task Scheduler task created by install.ps1. Not meant to be edited.
setlocal
cd /d "%~dp0"
if not exist logs mkdir logs
call "%~dp0env.cmd"

:loop
rem Simple rotation: keep the current log up to 10 MB plus one previous file
if exist logs\bot.log for %%F in (logs\bot.log) do if %%~zF GTR 10485760 move /y logs\bot.log logs\bot.log.1 >nul

echo [%date% %time%] starting bot >> logs\bot.log
"%JAVA_EXE%" -Xmx256m -XX:+UseSerialGC ^
  -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 ^
  -Dorg.slf4j.simpleLogger.showDateTime=true "-Dorg.slf4j.simpleLogger.dateTimeFormat=yyyy-MM-dd HH:mm:ss" ^
  -jar task-reminder-bot.jar >> logs\bot.log 2>&1
echo [%date% %time%] bot exited with code %errorlevel%, restarting in 15 seconds >> logs\bot.log

rem "timeout" does not work without a console (the task runs in the background), ping is a portable sleep
ping -n 16 127.0.0.1 >nul
goto loop
