package com.kommserver.launcher.service;

import java.nio.file.Path;

/**
 * Controls komm-server as an OS service. Deliberately thin: on Linux this delegates
 * straight to systemctl/journalctl, on Windows to sc.exe + a bundled WinSW wrapper —
 * the launcher supervises nothing itself, it only asks the OS's own service manager
 * to do it, which is what actually gives the server Restart=always / crash recovery.
 */
public interface ServiceController {

    void install(Path serverJar);

    void uninstall();

    void start();

    void stop();

    void restart();

    ServiceStatus status();

    void logs(int lines, boolean follow);
}
