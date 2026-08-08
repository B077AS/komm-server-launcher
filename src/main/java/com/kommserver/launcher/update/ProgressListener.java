package com.kommserver.launcher.update;

@FunctionalInterface
public interface ProgressListener {
    /** {@code total} is -1 when the server didn't report a Content-Length. */
    void onProgress(long transferred, long total);
}
