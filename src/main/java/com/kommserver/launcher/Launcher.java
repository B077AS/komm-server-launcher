package com.kommserver.launcher;

import com.kommserver.launcher.commands.InstallServiceCommand;
import com.kommserver.launcher.commands.LogsCommand;
import com.kommserver.launcher.commands.RestartCommand;
import com.kommserver.launcher.commands.StartCommand;
import com.kommserver.launcher.commands.StatusCommand;
import com.kommserver.launcher.commands.StopCommand;
import com.kommserver.launcher.commands.TrayCommand;
import com.kommserver.launcher.commands.UninstallServiceCommand;
import com.kommserver.launcher.commands.UpdateCommand;
import org.fusesource.jansi.AnsiConsole;
import picocli.CommandLine;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

@CommandLine.Command(
        name = "kommserver",
        description = "Install, run, and update a self-hosted komm-server.",
        mixinStandardHelpOptions = true,
        versionProvider = Launcher.VersionProvider.class,
        subcommands = {
                StatusCommand.class,
                StartCommand.class,
                StopCommand.class,
                RestartCommand.class,
                UpdateCommand.class,
                LogsCommand.class,
                InstallServiceCommand.class,
                UninstallServiceCommand.class,
                TrayCommand.class
        }
)
public class Launcher implements Runnable {

    @Override
    public void run() {
        // Bare `kommserver` shows the same thing `kommserver status` does.
        new StatusCommand().run();
    }

    public static void main(String[] args) {
        AnsiConsole.systemInstall();
        // Plain cmd.exe defaults to a legacy codepage that mangles the box-drawing/bullet
        // glyphs Palette uses. AnsiConsole.systemInstall() replaces System.out/err with its
        // own streams for ANSI translation on legacy consoles, so this has to wrap *those*
        // (order matters — wrapping first just gets discarded) to force UTF-8 without
        // depending on the user having run `chcp 65001` or using Windows Terminal.
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        try {
            int exitCode = new CommandLine(new Launcher()).execute(args);
            System.exit(exitCode);
        } catch (Throwable t) {
            // picocli's own execute() only catches Exception, not Error — AWT/Toolkit
            // initialization failures (e.g. java.awt.AWTError) are Errors, and without
            // this they vanish completely: no message, exit code 0, nothing to debug.
            System.err.println("Fatal: " + t);
            t.printStackTrace();
            System.exit(1);
        } finally {
            AnsiConsole.systemUninstall();
        }
    }

    static class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            String v = Launcher.class.getPackage().getImplementationVersion();
            return new String[]{"kommserver " + (v == null ? "dev" : v)};
        }
    }
}
