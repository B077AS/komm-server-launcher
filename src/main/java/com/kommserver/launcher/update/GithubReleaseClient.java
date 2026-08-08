package com.kommserver.launcher.update;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

public class GithubReleaseClient {

    private static final Gson GSON = new Gson();
    // GitHub's asset download URLs 302 to objects.githubusercontent.com — HttpClient does
    // not follow redirects by default, so without this every download() 302s and stops.
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public GithubRelease latestRelease(String owner, String repo) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("https://api.github.com/repos/%s/%s/releases/latest".formatted(owner, repo)))
                .header("Accept", "application/vnd.github+json")
                .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 403 && "0".equals(response.headers().firstValue("X-RateLimit-Remaining").orElse(null))) {
            // Unauthenticated GitHub API calls are capped at 60/hour per IP — easy to hit
            // during heavy testing, much less likely for real day-to-day use. Not a bug,
            // just an inherent limit of talking to GitHub directly (see Repos.java) rather
            // than through komm-hub, which could share one authenticated poll across every
            // installation instead of each one spending its own budget.
            String resetAt = response.headers().firstValue("X-RateLimit-Reset")
                    .map(s -> java.time.Instant.ofEpochSecond(Long.parseLong(s)).toString())
                    .orElse("later");
            throw new IOException("GitHub's rate limit for unauthenticated requests (60/hour) is used up "
                    + "for now — try again after " + resetAt + ".");
        }
        if (response.statusCode() != 200) {
            throw new IOException("GitHub API returned " + response.statusCode() + " for " + owner + "/" + repo);
        }
        return GSON.fromJson(response.body(), GithubRelease.class);
    }

    /**
     * Downloads to {@code destination}, verifying the SHA-256 GitHub reports for the asset
     * when present. Streamed manually (rather than {@code BodyHandlers.ofFile}) so
     * {@code listener} can report progress as bytes arrive — {@code listener} may be null.
     */
    public void download(GithubRelease.Asset asset, Path destination, ProgressListener listener) throws IOException, InterruptedException {
        Path tmp = destination.resolveSibling(destination.getFileName() + ".download");
        HttpRequest request = HttpRequest.newBuilder(URI.create(asset.downloadUrl())).GET().build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("Download failed with status " + response.statusCode());
        }
        long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            long transferred = 0;
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
                transferred += read;
                if (listener != null) listener.onProgress(transferred, total);
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        if (asset.digest() != null && asset.digest().startsWith("sha256:")) {
            String expected = asset.digest().substring("sha256:".length());
            String actual = sha256(tmp);
            if (!expected.equalsIgnoreCase(actual)) {
                Files.deleteIfExists(tmp);
                throw new IOException("Checksum mismatch for " + asset.name() + " — download corrupted or tampered with.");
            }
        }
        Files.move(tmp, destination, StandardCopyOption.REPLACE_EXISTING);
    }

    private String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = in.read(buf)) != -1) {
                    digest.update(buf, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
