package com.kommserver.launcher.commands;

import com.kommserver.launcher.Palette;
import com.kommserver.launcher.service.ServiceControllerFactory;
import picocli.CommandLine;

@CommandLine.Command(name = "stop", description = "Stop the komm-server service")
public class StopCommand implements Runnable {
    @Override
    public void run() {
        try {
            ServiceControllerFactory.create().stop();
            System.out.println(Palette.success("●") + " stop requested");
        } catch (Exception e) {
            System.out.println(Palette.danger("Stop failed: ") + e.getMessage());
        }
    }
}
