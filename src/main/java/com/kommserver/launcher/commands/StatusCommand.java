package com.kommserver.launcher.commands;

import com.kommserver.launcher.Palette;
import com.kommserver.launcher.Platform;
import com.kommserver.launcher.config.LauncherConfig;
import com.kommserver.launcher.service.ServiceController;
import com.kommserver.launcher.service.ServiceControllerFactory;
import com.kommserver.launcher.service.ServiceStatus;
import picocli.CommandLine;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;

@CommandLine.Command(name = "status", description = "Show whether komm-server is installed and running")
public class StatusCommand implements Runnable {

    @Override
    public void run() {
        System.out.println(Palette.header());

        LauncherConfig config = LauncherConfig.load();
        ServiceController service = ServiceControllerFactory.create();
        ServiceStatus status = service.status();

        if (!status.installed()) {
            System.out.println(Palette.badge(false, "not installed"));
        } else {
            System.out.println(Palette.badge(status.running(), status.running() ? "running" : "stopped"));
        }

        String version = config.getInstalledServerVersion();
        System.out.println(Palette.muted("version   ")
                + (version == null || version.isBlank() ? Palette.muted("unknown") : Palette.cyan(version)));
        System.out.println(Palette.muted("jar       ") + config.getServerJar());

        int port = config.getEffectiveServerPort();
        if (status.running() && portOpen("127.0.0.1", port)) {
            System.out.println(Palette.muted("port " + port + "  ") + Palette.success("accepting connections"));
        } else if (status.running()) {
            System.out.println(Palette.muted("port " + port + "  ") + Palette.danger("not responding yet"));
        }

        printAvailableCommands(status, Files.exists(config.getServerJar()));
    }

    // "It doesn't show me any options" was real feedback — status is the thing people
    // run when they don't know what to do next, so it should answer that directly
    // instead of assuming they already know the command list or will find --help.
    private void printAvailableCommands(ServiceStatus status, boolean hasServerJar) {
        System.out.println();
        System.out.println(Palette.muted("─".repeat(28)));
        if (!status.installed()) {
            if (!hasServerJar) {
                System.out.println(Palette.accent("kommserver update") + Palette.muted("            downloads the server jar"));
            }
            System.out.println(Palette.accent("kommserver install-service") + Palette.muted("   registers the OS service"));
        } else if (status.running()) {
            System.out.println(Palette.accent("kommserver stop") + Palette.muted("              stops the service"));
            System.out.println(Palette.accent("kommserver restart") + Palette.muted("           restarts it"));
            System.out.println(Palette.accent("kommserver logs -f") + Palette.muted("           follows the live log"));
            System.out.println(Palette.accent("kommserver update") + Palette.muted("            checks for a newer server version"));
        } else {
            System.out.println(Palette.accent("kommserver start") + Palette.muted("             starts the service"));
            System.out.println(Palette.accent("kommserver logs") + Palette.muted("              shows recent log output"));
            System.out.println(Palette.accent("kommserver uninstall-service") + Palette.muted(" removes the service registration"));
        }
        System.out.println(Palette.muted("kommserver --help") + Palette.muted("            full command list"));
        // This was hardcoded to Windows-specific wording regardless of platform — showed
        // up verbatim (and wrongly) on Linux, where none of it applies: /usr/local/bin is
        // already on PATH for every new shell, no staleness to worry about, and there's
        // no Desktop/Start Menu shortcut. The real caveat on Linux is different — root.
        if (Platform.isWindows()) {
            // Only true for a Command Prompt/PowerShell window opened AFTER installing —
            // one that was already open beforehand keeps whatever PATH it started with,
            // same reason the tray's own "Open console" needed an absolute-path fix
            // rather than relying on this. The Desktop/Start Menu shortcut always works
            // regardless, since it doesn't depend on PATH at all.
            System.out.println(Palette.muted("(type these into a Command Prompt/PowerShell window opened after installing,"));
            System.out.println(Palette.muted(" or use the Desktop/Start Menu shortcut, which always works)"));
        } else {
            System.out.println(Palette.muted("(start/stop/restart/install-service/uninstall-service need root: sudo kommserver ...)"));
        }
    }

    private boolean portOpen(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
