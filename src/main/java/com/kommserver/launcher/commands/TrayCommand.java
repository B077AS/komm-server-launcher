package com.kommserver.launcher.commands;

import com.kommserver.launcher.Platform;
import com.kommserver.launcher.tray.TrayApp;
import picocli.CommandLine;

@CommandLine.Command(name = "tray", hidden = true,
        description = "Run the Windows tray icon (started automatically at login by the installer)")
public class TrayCommand implements Runnable {
    @Override
    public void run() {
        if (!Platform.isWindows()) {
            System.out.println("The tray icon is Windows-only — use `kommserver status` here instead.");
            return;
        }
        TrayApp.run();
    }
}
