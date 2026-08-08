package com.kommserver.launcher;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class Platform {

    private Platform() {}

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** Where the server jar, its data, and the Windows service wrapper live. */
    public static Path installDir() {
        if (isWindows()) {
            String programData = System.getenv("ProgramData");
            if (programData == null) programData = "C:\\ProgramData";
            return Paths.get(programData, "Komm", "Server");
        }
        return Paths.get("/opt/komm-server");
    }

    public static Path configFile() {
        if (isWindows()) {
            return installDir().resolve("launcher.properties");
        }
        return Paths.get("/etc/komm-server/launcher.properties");
    }

    public static Path logDir() {
        return installDir().resolve("logs");
    }
}
