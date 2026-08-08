package com.kommserver.launcher.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * komm-server itself is what systemd supervises directly (ExecStart=java -jar ...,
 * Restart=always) — there is no resident launcher process on Linux. This class only
 * shells out to systemctl/journalctl on demand, same as typing the commands by hand.
 */
public class LinuxServiceController implements ServiceController {

    private static final String SERVICE_NAME = "komm-server";
    private static final Path UNIT_FILE = Path.of("/etc/systemd/system/" + SERVICE_NAME + ".service");

    @Override
    public void install(Path serverJar) {
        String unit = """
                [Unit]
                Description=komm-server (self-hosted komm community server)
                After=network.target

                [Service]
                Type=simple
                ExecStart=%s -jar %s
                WorkingDirectory=%s
                Restart=always
                RestartSec=2
                StandardOutput=journal
                StandardError=journal

                [Install]
                WantedBy=multi-user.target
                """.formatted(javaBinary(), serverJar.toAbsolutePath(), serverJar.toAbsolutePath().getParent());
        try {
            Files.writeString(UNIT_FILE, unit);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write " + UNIT_FILE + " — are you running as root?", e);
        }
        run("systemctl", "daemon-reload");
        run("systemctl", "enable", SERVICE_NAME);
    }

    @Override
    public void uninstall() {
        run("systemctl", "stop", SERVICE_NAME);
        run("systemctl", "disable", SERVICE_NAME);
        try {
            Files.deleteIfExists(UNIT_FILE);
        } catch (IOException e) {
            throw new RuntimeException("Failed to remove " + UNIT_FILE + " (" + e.getMessage()
                    + ") — controlling a system service needs root: try `sudo kommserver uninstall-service`.", e);
        }
        run("systemctl", "daemon-reload");
    }

    @Override
    public void start() {
        requirePrivileged(run("systemctl", "start", SERVICE_NAME), "start");
    }

    @Override
    public void stop() {
        requirePrivileged(run("systemctl", "stop", SERVICE_NAME), "stop");
    }

    @Override
    public void restart() {
        requirePrivileged(run("systemctl", "restart", SERVICE_NAME), "restart");
    }

    // Every one of these silently reported "requested" regardless of whether it actually
    // worked — systemctl controlling a *system* (not --user) service needs root, and a
    // plain, unprivileged `kommserver start` just fails with nothing to show for it. Only
    // `uninstall()` used to catch this (via its own Files.deleteIfExists check); start/stop/
    // restart never checked systemctl's exit code at all.
    private void requirePrivileged(ProcessResult result, String verb) {
        if (result.exitCode() != 0) {
            throw new RuntimeException("Failed to " + verb + " the service (systemctl exited "
                    + result.exitCode() + "): " + result.output().trim()
                    + " — controlling a system service needs root: try `sudo kommserver " + verb + "`.");
        }
    }

    @Override
    public ServiceStatus status() {
        if (!Files.exists(UNIT_FILE)) {
            return ServiceStatus.notInstalled();
        }
        ProcessResult active = run("systemctl", "is-active", SERVICE_NAME);
        boolean running = active.output().trim().equals("active");
        return new ServiceStatus(true, running, active.output().trim());
    }

    @Override
    public void logs(int lines, boolean follow) {
        List<String> cmd = follow
                ? List.of("journalctl", "-u", SERVICE_NAME, "-n", String.valueOf(lines), "-f", "--no-pager")
                : List.of("journalctl", "-u", SERVICE_NAME, "-n", String.valueOf(lines), "--no-pager");
        inherit(cmd);
    }

    private String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private ProcessResult run(String... cmd) {
        try {
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes());
            }
            int exit = process.waitFor();
            return new ProcessResult(exit, output);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to run: " + String.join(" ", cmd), e);
        }
    }

    private void inherit(List<String> cmd) {
        try {
            new ProcessBuilder(cmd).inheritIO().start().waitFor();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to run: " + String.join(" ", cmd), e);
        }
    }

    private record ProcessResult(int exitCode, String output) {}
}
