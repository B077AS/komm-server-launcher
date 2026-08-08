package com.kommserver.launcher.commands;

import com.kommserver.launcher.service.ServiceControllerFactory;
import picocli.CommandLine;

@CommandLine.Command(name = "logs", description = "Show recent komm-server log output")
public class LogsCommand implements Runnable {

    @CommandLine.Option(names = {"-n", "--lines"}, description = "Number of lines to show", defaultValue = "50")
    int lines;

    @CommandLine.Option(names = {"-f", "--follow"}, description = "Keep following new log output")
    boolean follow;

    @Override
    public void run() {
        ServiceControllerFactory.create().logs(lines, follow);
    }
}
