# Run by the uninstaller ([UninstallRun] in komm-server-launcher.iss). CloseApplications=yes
# (Windows Restart Manager) only catches processes holding an actual open file handle on
# something being removed, and a JVM that already finished classloading from its own jar
# doesn't reliably keep one — so it wasn't closing the tray. This finds it by command line
# instead, which always works regardless of file-handle state.
Get-CimInstance Win32_Process -Filter "Name='javaw.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like '*komm-server-launcher.jar*tray*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
