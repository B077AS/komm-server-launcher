package com.kommserver.launcher.commands;

import com.kommserver.launcher.Palette;
import com.kommserver.launcher.service.ServiceControllerFactory;
import picocli.CommandLine;

@CommandLine.Command(name = "start", description = "Start the komm-server service")
public class StartCommand implements Runnable {
    @Override
    public void run() {
        try {
            ServiceControllerFactory.create().start();
            System.out.println(Palette.success("●") + " start requested — check `kommserver status` in a moment");
        } catch (Exception e) {
            System.out.println(Palette.danger("Start failed: ") + e.getMessage());
        }
    }
}
