package com.kommserver.launcher.commands;

import com.kommserver.launcher.Palette;
import com.kommserver.launcher.service.ServiceControllerFactory;
import picocli.CommandLine;

@CommandLine.Command(name = "restart", description = "Restart the komm-server service")
public class RestartCommand implements Runnable {
    @Override
    public void run() {
        try {
            ServiceControllerFactory.create().restart();
            System.out.println(Palette.success("●") + " restart requested");
        } catch (Exception e) {
            System.out.println(Palette.danger("Restart failed: ") + e.getMessage());
        }
    }
}
