package com.kommserver.launcher.update;

import com.kommserver.launcher.Repos;
import com.kommserver.launcher.config.LauncherConfig;

import java.io.IOException;
import java.nio.file.Files;

public class ServerUpdateManager {

    private final GithubReleaseClient client = new GithubReleaseClient();

    public record CheckResult(boolean updateAvailable, String currentVersion, String latestVersion, GithubRelease release) {}

    public CheckResult check(LauncherConfig config) throws IOException, InterruptedException {
        GithubRelease release = client.latestRelease(Repos.OWNER, Repos.SERVER_REPO);
        boolean available = !release.version().equals(config.getInstalledServerVersion());
        return new CheckResult(available, config.getInstalledServerVersion(), release.version(), release);
    }

    /** Downloads the new server jar over the configured path and records the new version. Does not restart anything. */
    public void apply(LauncherConfig config, GithubRelease release, ProgressListener listener) throws IOException, InterruptedException {
        GithubRelease.Asset asset = release.asset(Repos.SERVER_JAR_ASSET);
        if (asset == null) {
            throw new IOException("Release " + release.tagName() + " has no " + Repos.SERVER_JAR_ASSET + " asset");
        }
        if (config.getServerJar().getParent() != null) {
            Files.createDirectories(config.getServerJar().getParent());
        }
        client.download(asset, config.getServerJar(), listener);
        config.setInstalledServerVersion(release.version());
        config.save();
    }
}
