package com.kommserver.launcher.config;

import com.kommserver.launcher.Platform;
import lombok.Getter;
import lombok.Setter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The launcher's own small local state — not to be confused with komm-server's own
 * installation record (keys/, komm-postgres-data/). Tracks where the managed server jar
 * lives and which version was last downloaded, so `update` and `status` don't need to
 * re-derive it by inspecting the jar every time.
 */
@Getter
@Setter
public class LauncherConfig {

    private Path serverJar;
    private String installedServerVersion;
    private int serverPort;

    private final Path file;

    private LauncherConfig(Path file) {
        this.file = file;
    }

    public static LauncherConfig load() {
        Path file = Platform.configFile();
        LauncherConfig config = new LauncherConfig(file);
        Properties props = new Properties();
        if (Files.isReadable(file)) {
            try (var in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read launcher config: " + file, e);
            }
        }
        config.serverJar = Path.of(props.getProperty("server.jar",
                Platform.installDir().resolve("komm-server.jar").toString()));
        config.installedServerVersion = props.getProperty("server.version", "");
        config.serverPort = Integer.parseInt(props.getProperty("server.port", "8090"));
        return config;
    }

    /**
     * server.port here is just this file's own tracked default (8090 unless someone
     * edits it) — it has no connection at all to the port actually chosen on the hub
     * dashboard at installation creation. That value lives in a completely different
     * file, installation-ports.properties, written by the SERVER (PortConfigService)
     * after activation and read by komm-server's own PortEnvironmentPostProcessor —
     * this launcher process never otherwise learns about it. Without reading that file
     * too, `kommserver status` would silently probe the wrong port forever whenever an
     * installation didn't use the default.
     */
    public int getEffectiveServerPort() {
        Path installDir = serverJar.toAbsolutePath().getParent();
        Path portsFile = installDir == null ? null : installDir.resolve("installation-ports.properties");
        if (portsFile != null && Files.isReadable(portsFile)) {
            try (var in = Files.newInputStream(portsFile)) {
                Properties props = new Properties();
                props.load(in);
                String port = props.getProperty("server.port");
                if (port != null && !port.isBlank()) {
                    return Integer.parseInt(port.trim());
                }
            } catch (Exception ignored) {
                // Falls through to the tracked default below.
            }
        }
        return serverPort;
    }

    public void save() {
        Properties props = new Properties();
        props.setProperty("server.jar", serverJar.toString());
        props.setProperty("server.version", installedServerVersion == null ? "" : installedServerVersion);
        props.setProperty("server.port", String.valueOf(serverPort));
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            try (var out = Files.newOutputStream(file)) {
                props.store(out, "komm-server-launcher configuration");
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to write launcher config: " + file +
                    " — try running as " + (Platform.isWindows() ? "Administrator" : "root"), e);
        }
    }
}
