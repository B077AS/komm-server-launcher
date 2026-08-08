package com.kommserver.launcher.commands;

import com.kommserver.launcher.Palette;
import com.kommserver.launcher.config.LauncherConfig;
import com.kommserver.launcher.service.ServiceControllerFactory;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "install-service",
        description = "Register komm-server as an OS service (systemd on Linux, a Windows Service via WinSW on Windows)")
public class InstallServiceCommand implements Callable<Integer> {

    // Not a plain Runnable: activate.cmd chains this with `&& kommserver.cmd start` on
    // Windows, and only a real non-zero exit code stops that chain when this fails —
    // returning early from a Runnable's run() still exits 0 either way.
    @Override
    public Integer call() {
        LauncherConfig config = LauncherConfig.load();
        if (!Files.exists(config.getServerJar())) {
            System.out.println(Palette.danger("No server jar found at " + config.getServerJar()));
            System.out.println(Palette.muted("Run `kommserver update` first to download one."));
            return 1;
        }

        if (!ensureSetupToken(config)) {
            return 1;
        }

        try {
            ServiceControllerFactory.create().install(config.getServerJar());
            System.out.println(Palette.success("✓") + " service installed — `kommserver start` to bring it up");
            return 0;
        } catch (Exception e) {
            System.out.println(Palette.danger("Install failed: ") + e.getMessage());
            return 1;
        }
    }

    /**
     * The server jar is a generic build with no per-installation identity baked in — it
     * reads a one-time setup token from a plain file instead (see StartupValidator in
     * komm-server). {@code keys/tls-cert.pem} existing means this install already
     * activated at some point (the server deletes the token file itself right after a
     * successful activation, so the file's mere absence can't be trusted alone — it's
     * ambiguous between "never provided" and "already consumed").
     */
    private boolean ensureSetupToken(LauncherConfig config) {
        Path installDir = config.getServerJar().toAbsolutePath().getParent();
        Path certFile = installDir.resolve("keys").resolve("tls-cert.pem");
        Path tokenFile = installDir.resolve("setup-token.txt");

        if (Files.exists(certFile) || Files.exists(tokenFile)) {
            return true; // already activated, or a code is already waiting to be consumed
        }

        System.out.println(Palette.accent("New installation — activation needed."));
        System.out.println(Palette.muted("Paste your verification code:"));
        System.out.print("> ");
        System.out.flush();
        String code;
        try {
            code = new Scanner(System.in).nextLine().trim();
        } catch (java.util.NoSuchElementException e) {
            // No stdin attached to read from at all (not just an empty Enter press).
            System.out.println();
            System.out.println(Palette.danger("No input available here."));
            System.out.println(Palette.muted("Open Command Prompt or PowerShell and type exactly this, then paste your code when asked:"));
            System.out.println(Palette.accent("  kommserver install-service"));
            return false;
        }
        if (code.isEmpty()) {
            System.out.println(Palette.danger("No code entered — aborting."));
            return false;
        }

        try {
            Files.createDirectories(tokenFile.getParent());
            Files.writeString(tokenFile, code);
            System.out.println(Palette.success("✓") + " verification code saved");
            return true;
        } catch (IOException e) {
            System.out.println(Palette.danger("Failed to save the verification code: ") + e.getMessage());
            return false;
        }
    }
}
