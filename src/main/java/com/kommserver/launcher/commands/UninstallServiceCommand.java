package com.kommserver.launcher.commands;

import com.kommserver.launcher.Palette;
import com.kommserver.launcher.service.ServiceControllerFactory;
import picocli.CommandLine;

@CommandLine.Command(name = "uninstall-service", description = "Remove the komm-server OS service registration")
public class UninstallServiceCommand implements Runnable {
    @Override
    public void run() {
        try {
            ServiceControllerFactory.create().uninstall();
            System.out.println(Palette.success("✓") + " service removed");
        } catch (Exception e) {
            System.out.println(Palette.danger("Uninstall failed: ") + e.getMessage());
        }
    }
}
