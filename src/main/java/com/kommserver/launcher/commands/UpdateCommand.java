package com.kommserver.launcher.commands;

import com.kommserver.launcher.Palette;
import com.kommserver.launcher.config.LauncherConfig;
import com.kommserver.launcher.service.ServiceController;
import com.kommserver.launcher.service.ServiceControllerFactory;
import com.kommserver.launcher.update.ServerUpdateManager;
import picocli.CommandLine;

import java.util.Scanner;

/**
 * Updates the managed komm-server jar only. The launcher does NOT update itself here —
 * a CLI process overwriting the jar it's currently executing from is exactly the kind of
 * self-swap the client/launcher pair avoids (the client updates the launcher, never the
 * other way round, because the launcher can't reliably outlive its own update of itself).
 * komm-server plays that "client" role for us: it runs 24/7 and checks for a newer
 * komm-server-launcher release itself, overwriting the (not currently running, or at
 * least not-this-process) launcher jar — see LauncherSelfUpdateService in komm-server.
 */
@CommandLine.Command(name = "update", description = "Check for and apply a komm-server update")
public class UpdateCommand implements Runnable {

    @CommandLine.Option(names = "--yes", description = "Don't prompt before restarting a running server")
    boolean yes;

    @Override
    public void run() {
        try {
            ServerUpdateManager manager = new ServerUpdateManager();
            LauncherConfig config = LauncherConfig.load();
            var result = manager.check(config);
            String current = result.currentVersion() == null || result.currentVersion().isBlank()
                    ? "none installed" : result.currentVersion();
            if (!result.updateAvailable()) {
                System.out.println(Palette.muted("server: up to date (" + current + ")"));
                return;
            }
            System.out.println(Palette.accent("server: ") + Palette.cyan(current) + " -> " + Palette.cyan(result.latestVersion()));

            ServiceController service = ServiceControllerFactory.create();
            boolean wasRunning = service.status().running();
            if (wasRunning && !yes && !confirm("Server is running — download and restart now to apply? [y/N] ")) {
                System.out.println(Palette.muted("Downloaded nothing. Run `kommserver update --yes` when you're ready."));
                return;
            }

            System.out.println(Palette.muted("Downloading " + result.latestVersion() + "..."));
            long[] lastPrinted = {-1};
            manager.apply(config, result.release(), (transferred, total) -> {
                // Redrawing on every 8 KB chunk is wasted work and flickers the line for
                // nothing — cap it to roughly 100 redraws across the whole download
                // regardless of size (or every 256 KB when the server sent no
                // Content-Length to compute a percentage from at all).
                long step = total > 0 ? Math.max(total / 100, 8192) : 262144;
                if (transferred != total && transferred - lastPrinted[0] < step) return;
                lastPrinted[0] = transferred;
                printProgress(transferred, total);
            });
            System.out.println();
            System.out.println(Palette.success("✓") + " server jar updated to " + Palette.cyan(result.latestVersion()));

            if (wasRunning) {
                service.restart();
                System.out.println(Palette.success("✓") + " restart requested");
            }
        } catch (Exception e) {
            System.out.println(Palette.danger("server update check failed: ") + e.getMessage());
        }
    }

    private boolean confirm(String prompt) {
        System.out.print(prompt);
        System.out.flush();
        String line = new Scanner(System.in).nextLine();
        return line.trim().equalsIgnoreCase("y");
    }

    // \r-redraw works identically on Linux terminals and Windows (Terminal or legacy
    // cmd/PowerShell — Launcher.main() already forces UTF-8 output, and \r is a plain
    // carriage return, not an ANSI escape, so it needs no jansi-specific handling).
    // Padded with trailing spaces since later redraws are sometimes shorter than
    // earlier ones (e.g. 100% vs 9%) and would otherwise leave stray characters behind.
    private static void printProgress(long transferred, long total) {
        String line;
        if (total > 0) {
            int percent = (int) Math.min(100, transferred * 100 / total);
            int barWidth = 24;
            int filled = barWidth * percent / 100;
            String bar = "█".repeat(filled) + "░".repeat(barWidth - filled);
            line = Palette.accent("[" + bar + "]") + " " + Palette.cyan(percent + "%")
                    + Palette.muted(" (" + humanSize(transferred) + " / " + humanSize(total) + ")");
        } else {
            line = Palette.muted("Downloading... " + humanSize(transferred));
        }
        System.out.print("\r" + line + "          ");
        System.out.flush();
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.0f KB", kb);
        return String.format("%.1f MB", kb / 1024.0);
    }
}
