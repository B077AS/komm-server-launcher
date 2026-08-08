package com.kommserver.launcher.update;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public record GithubRelease(
        @SerializedName("tag_name") String tagName,
        List<Asset> assets
) {
    public record Asset(
            String name,
            @SerializedName("browser_download_url") String downloadUrl,
            String digest
    ) {}

    public String version() {
        return tagName.startsWith("v") ? tagName.substring(1) : tagName;
    }

    public Asset asset(String name) {
        return assets.stream().filter(a -> a.name().equals(name)).findFirst().orElse(null);
    }
}
