@echo off
rem Forcing UTF-8 in Java's own PrintStream (Launcher.main) is only half the fix - a
rem fresh cmd.exe window still interprets those bytes using whatever legacy codepage it
rem started with (437/850 etc.) unless told otherwise. This has to happen in the console
rem itself, and has to happen BEFORE anything non-ASCII appears in this file - cmd.exe
rem parses lines using the CURRENT codepage, so even a comment above this point using an
rem em dash or similar breaks parsing before chcp ever runs. Keep this file ASCII-only.
chcp 65001 >nul
java -jar "%~dp0komm-server-launcher.jar" %*
