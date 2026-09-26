package com.kommserver.launcher.update;

import com.kommserver.launcher.Launcher;
import com.kommserver.launcher.Platform;
import com.kommserver.launcher.Repos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Checks for and applies updates to komm-server-launcher itself — the CLI/OS-service-manager
 * jar this class ships in, not the managed komm-server jar (see {@link ServerUpdateManager}
 * for that; the two are independent, since komm-server's own release workflow pins its
 * installer/tarball to a specific launcher release rather than always "latest").
 *
 * <p>Linux: a JVM already running off a path keeps working off its old inode after that path
 * is atomically replaced underneath it (POSIX unlink-while-open), so the download goes
 * straight to the installed {@code komm-server-launcher.jar}; this command finishes normally
 * and the next {@code kommserver} invocation simply loads the new jar.
 *
 * <p>Windows has no such guarantee — and packaging/windows/stop-tray.ps1 already notes that a
 * JVM holding a jar open here doesn't *reliably* release it even once "done" with it, which
 * makes a same-process rename onto this exact command's own currently-loaded jar unsafe to
 * depend on. So on Windows this downloads to {@code komm-server-launcher.jar.new}, best-effort
 * stops the resident tray process (the same script the installer already uses for this, since
 * Windows' own Restart Manager doesn't reliably catch it either), and hands the actual swap off
 * to a detached PowerShell one-liner that waits for *this* process's PID to exit before moving
 * the new jar into place — deterministic instead of hoping the lock happens to already be free.
 */
public class LauncherUpdateManager {

    private static final String STAGED_SUFFIX = ".new";
    private static final String STOP_TRAY_SCRIPT = "stop-tray.ps1";

    private final GithubReleaseClient client = new GithubReleaseClient();

    public record CheckResult(boolean updateAvailable, String currentVersion, String latestVersion, GithubRelease release) {}

    /** The version embedded in this exact running jar — the same manifest entry {@code kommserver --version} reads. */
    public static String currentVersion() {
        String v = Launcher.class.getPackage().getImplementationVersion();
        return v == null || v.isBlank() ? "dev" : v;
    }

    public CheckResult check() throws IOException, InterruptedException {
        GithubRelease release = client.latestRelease(Repos.OWNER, Repos.LAUNCHER_REPO);
        String current = currentVersion();
        boolean available = !release.version().equals(current);
        return new CheckResult(available, current, release.version(), release);
    }

    /**
     * Downloads the new launcher jar and applies it — immediately on Linux, or via the
     * detached hand-off described in the class javadoc on Windows. Either way, this call
     * itself never renames or deletes the currently-loaded jar directly.
     */
    public void apply(GithubRelease release, ProgressListener listener) throws IOException, InterruptedException {
        GithubRelease.Asset asset = release.asset(Repos.LAUNCHER_JAR_ASSET);
        if (asset == null) {
            throw new IOException("Release " + release.tagName() + " has no " + Repos.LAUNCHER_JAR_ASSET + " asset yet");
        }
        Files.createDirectories(Platform.installDir());
        Path target = Platform.installDir().resolve(Repos.LAUNCHER_JAR_ASSET);

        if (!Platform.isWindows()) {
            client.download(asset, target, listener);
            return;
        }

        Path staged = Platform.installDir().resolve(Repos.LAUNCHER_JAR_ASSET + STAGED_SUFFIX);
        client.download(asset, staged, listener);
        stopTrayBestEffort();
        handOffSwap(staged, target);
    }

    /** Best-effort — a failure here just means the hand-off below may need one more retry
     *  loop iteration once the tray happens to close on its own (logout, manual quit, reboot). */
    private void stopTrayBestEffort() {
        Path script = Platform.installDir().resolve(STOP_TRAY_SCRIPT);
        if (!Files.isRegularFile(script)) return;
        try {
            new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString())
                    .redirectErrorStream(true)
                    .start()
                    .waitFor();
        } catch (Exception ignored) {
            // Non-fatal — see javadoc above.
        }
    }

    /**
     * Spawns a detached PowerShell process that waits for this JVM to exit, then moves
     * {@code staged} onto {@code target}, retrying for a while in case some other
     * {@code kommserver} invocation (or a tray that respawned) still has it open. Not tied
     * to this process's lifetime once started — a ProcessBuilder child isn't killed when
     * its parent exits on Windows.
     */
    private void handOffSwap(Path staged, Path target) throws IOException {
        long pid = ProcessHandle.current().pid();
        String script = "Wait-Process -Id " + pid + " -Timeout 30 -ErrorAction SilentlyContinue; "
                + "for ($i = 0; $i -lt 20; $i++) { "
                + "try { Move-Item -Force -LiteralPath '" + staged + "' -Destination '" + target + "'; break } "
                + "catch { Start-Sleep -Seconds 3 } }";
        new ProcessBuilder("powershell.exe", "-NoProfile", "-WindowStyle", "Hidden", "-Command", script)
                .start();
    }
}
