package com.kommserver.launcher.service;

import com.kommserver.launcher.Platform;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * komm-server-service.exe (WinSW, renamed at release-build time — see release.yml) is what's
 * registered with the Service Control Manager and supervises java.exe directly; this class
 * only talks to it via sc.exe / the exe's own install/uninstall verbs, on demand. It never
 * runs resident itself — that's the tray app's job (see tray/TrayApp.java), a separate,
 * non-service process, since Session 0 isolation means a Windows Service can never draw UI.
 */
public class WindowsServiceController implements ServiceController {

    private static final String SERVICE_ID = "KommServer";
    private final Path serviceExe = Platform.installDir().resolve("komm-server-service.exe");
    private final Path serviceXml = Platform.installDir().resolve("komm-server-service.xml");

    @Override
    public void install(Path serverJar) {
        if (!Files.exists(serviceExe)) {
            throw new IllegalStateException(
                    "komm-server-service.exe not found in " + Platform.installDir() +
                            " — reinstall komm-server-launcher, it ships the service wrapper.");
        }
        String xml = """
                <service>
                  <id>%s</id>
                  <name>Komm Server</name>
                  <description>Self-hosted komm community server</description>
                  <executable>%s</executable>
                  <arguments>-jar "%s"</arguments>
                  <workingdirectory>%s</workingdirectory>
                  <logpath>%s</logpath>
                  <log mode="roll-by-size">
                    <sizeThreshold>10240</sizeThreshold>
                    <keepFiles>8</keepFiles>
                  </log>
                  <onfailure action="restart" delay="2 sec"/>
                  <startmode>Automatic</startmode>
                </service>
                """.formatted(
                SERVICE_ID,
                javaBinary(),
                serverJar.toAbsolutePath(),
                serverJar.toAbsolutePath().getParent(),
                Platform.logDir());
        try {
            Files.createDirectories(Platform.logDir());
            Files.writeString(serviceXml, xml);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write " + serviceXml, e);
        }
        run(serviceExe.toString(), "install");

        // Windows Services default to SYSTEM/Administrators-only for start/stop — an
        // ordinary logged-in admin account, running non-elevated (the tray, or any
        // normal Command Prompt), gets a silent "Access is denied" from sc.exe with no
        // feedback at all, which is exactly what "Stop doesn't do anything" turned out
        // to be. This grants Authenticated Users (AU) explicit start/stop/query rights
        // (RPWP) on top of the default ACEs — SYSTEM (SY) and Administrators (BA) keep
        // full control (including reconfigure/delete), Power Users (PU) stay read-only.
        run("sc", "sdset", SERVICE_ID,
                "D:(A;;CCLCSWRPWPDTLOCRRC;;;SY)(A;;CCDCLCSWRPWPDTLOCRSDRCWDWO;;;BA)"
                        + "(A;;CCLCSWRPWPLOCRRC;;;AU)(A;;CCLCSWLOCRRC;;;PU)");
    }

    @Override
    public void uninstall() {
        run(serviceExe.toString(), "stop");
        run(serviceExe.toString(), "uninstall");
    }

    @Override
    public void start() {
        requireOk(run("sc", "start", SERVICE_ID), "start");
    }

    @Override
    public void stop() {
        requireOk(run("sc", "stop", SERVICE_ID), "stop");
    }

    @Override
    public void restart() {
        run("sc", "stop", SERVICE_ID);
        requireOk(run("sc", "start", SERVICE_ID), "restart");
    }

    // These used to silently discard sc.exe's result — install() granting Authenticated
    // Users start/stop rights (see below) fixes the common case, but any other failure
    // (service not installed, some other permission wrinkle) still deserves a message
    // instead of a false "requested" every time regardless of what actually happened.
    private void requireOk(ProcessResult result, String verb) {
        if (result.exitCode() != 0) {
            throw new RuntimeException("Failed to " + verb + " the service (sc.exe exited "
                    + result.exitCode() + "): " + result.output().trim());
        }
    }

    @Override
    public ServiceStatus status() {
        ProcessResult query = run("sc", "query", SERVICE_ID);
        if (query.output().contains("does not exist") || query.output().contains("FAILED 1060")) {
            return ServiceStatus.notInstalled();
        }
        boolean running = query.output().contains("RUNNING");
        String state = query.output().lines()
                .filter(l -> l.contains("STATE"))
                .findFirst().map(String::trim).orElse("unknown");
        return new ServiceStatus(true, running, state);
    }

    @Override
    public void logs(int lines, boolean follow) {
        Path logFile = Platform.logDir().resolve("komm-server-service.out.log");
        if (!Files.exists(logFile)) {
            System.out.println("No log file yet at " + logFile);
            return;
        }
        try {
            List<String> all = Files.readAllLines(logFile);
            int from = Math.max(0, all.size() - lines);
            all.subList(from, all.size()).forEach(System.out::println);
            if (follow) {
                tailFollow(logFile);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read log file: " + logFile, e);
        }
    }

    private void tailFollow(Path logFile) throws IOException {
        long pos = Files.size(logFile);
        try {
            while (true) {
                Thread.sleep(1000);
                long size = Files.size(logFile);
                if (size > pos) {
                    try (RandomAccessFile raf = new RandomAccessFile(logFile.toFile(), "r")) {
                        raf.seek(pos);
                        String line;
                        while ((line = raf.readLine()) != null) {
                            System.out.println(line);
                        }
                    }
                    pos = size;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // java.exe, not javaw.exe: WinSW's graceful stop works by sending a Ctrl+C-style
    // console control signal to ask the child process to shut down cleanly (which is
    // what lets the JVM's shutdown hooks run — including embedded-postgres's, which
    // otherwise leaves its whole process tree orphaned on a hard kill). javaw.exe has
    // no console at all to receive that signal, forcing WinSW straight to a hard
    // TerminateProcess once its stop timeout elapses, every single time. This has no
    // downside for a service specifically: Session 0 has no desktop, so neither variant
    // ever shows a visible window here regardless.
    private String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
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

    private record ProcessResult(int exitCode, String output) {}
}
