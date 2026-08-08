# komm-server-launcher

<p align="center">
  <b>Installs, updates, and runs a self-hosted <a href="https://github.com/B077AS/komm-server">komm-server</a> as a proper OS service — for <a href="https://kommvoice.com">Komm</a>, a free, self-hosted voice, video &amp; text chat platform.</b><br>
  Windows Service (WinSW) · systemd · <code>kommserver</code> CLI · Windows tray icon
</p>

<p align="center">
  <img alt="Java 21" src="https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white">
  <img alt="picocli" src="https://img.shields.io/badge/CLI-picocli-6DB33F">
  <img alt="Windows | Linux" src="https://img.shields.io/badge/Windows%20Service-systemd-4CAF50">
  <img alt="License: MIT" src="https://img.shields.io/badge/License-MIT-blue">
</p>

---

## What is Komm?

Komm is a modern chat platform built around a simple idea: **your community's messages and voice traffic belong on hardware you control.** Every community runs on its own self-hosted server — crystal-clear WebRTC voice channels, HD screen sharing, rich messaging, soundboards, roles & permissions, moderation tools and global hotkeys — without handing your conversations to anyone else. Free, no ads, no tracking, on Windows 10/11 and Linux (both X11 and Wayland, with native PipeWire support).

The platform has three pieces — you choose how many to run:

| Piece | Role | Who runs it |
|---|---|---|
| [komm](https://github.com/B077AS/komm) (+ [komm-launcher](https://github.com/B077AS/komm-launcher)) | Desktop client for Windows & Linux, kept up to date by the launcher | Everyone |
| [komm-server](https://github.com/B077AS/komm-server) (+ **komm-server-launcher**, this repo) | A community's own server: channels, messages, voice rooms, permissions. One JAR, embedded database | Community owners |
| [komm-hub](https://github.com/B077AS/komm-hub) | The network's directory: accounts, friends, DMs, and the CA that vouches for servers | Almost nobody — most people use [kommvoice.com](https://kommvoice.com) |

This repo is the piece [komm-server's own README](https://github.com/B077AS/komm-server#readme) promises as "coming soon": instead of a community owner manually downloading a JAR and running `java -jar komm-server.jar` in a terminal forever, this installs komm-server as a real OS service that starts at boot, restarts on crash, and updates itself on command — with a small branded CLI (and, on Windows, a tray icon) to control it.

## What it does

- **Registers komm-server as a real OS service** — a Windows Service via [WinSW](https://github.com/winsw/winsw) on Windows, a `systemd` unit on Linux — so it starts at boot and restarts itself on crash. This also covers the restart komm-server needs once it writes its hub-issued TLS certificate to disk (`server.ssl.*` only takes effect on the next boot); the service supervisor handles that automatically instead of it being a manual step.
- **A small branded CLI, `kommserver`**, for status/start/stop/restart/update/logs — the same commands on both platforms.
- **On Windows, an optional tray icon** (`kommserver tray`, started at login) wrapping the same commands, since a Windows Service can never draw UI itself (Session 0 isolation) — the tray is a separate, ordinary login-session process, not the service.
- **Updates the managed komm-server jar** — `kommserver update` checks GitHub for the latest komm-server release and, with confirmation if the server is currently running, downloads and swaps it in.
- **Handles first-time activation** — a fresh server jar has no per-installation identity baked in; `kommserver install-service` prompts for the verification code (fetched from the [komm](https://github.com/B077AS/komm) desktop client's "Get Verification Code" option, shown on an installation card only while it's still unverified) and hands it to komm-server as a one-time setup token, the same way the Windows installer's own activation page does.

## Install

**Windows:** run `Komm-Server-Setup-<version>.exe` from the
[latest release](https://github.com/B077AS/komm-server-launcher/releases/latest) (published by komm-server's release workflow, not this repo's — see [Building from source](#building-from-source-developers)). The installer can seed a working, already-running service in one pass, prompting for your verification code as it goes.

**Linux:**
```bash
curl -LO https://github.com/B077AS/komm-server-launcher/releases/latest/download/komm-server-launcher-linux-amd64.tar.gz
tar xzf komm-server-launcher-linux-amd64.tar.gz
cd komm-server-launcher-linux
sudo ./install.sh
```

Then, on either platform, if the installer didn't already leave you with a running service:
```
kommserver update            # downloads the latest komm-server jar
kommserver install-service   # registers the OS service (prompts for your verification code)
kommserver start
kommserver status
```

### Adopting an already-running install (Linux)

If you're already running komm-server as a bare jar — whether that's from before this launcher existed, or you've just never gotten around to wiring it up — `install.sh` can adopt it in place instead of doing a fresh install. When the tarball has no bundled server jar and the script is run interactively, it asks:

```
Is this a fresh install, or are you adopting a komm-server that's already
running as a bare jar (started by hand or by your own unit file, no launcher)?
Path to the existing jar (leave blank for a fresh install):
```

Give it the path to your existing jar and it writes `/etc/komm-server/launcher.properties` pointing at that exact location — nothing gets moved, and `komm-postgres-data/`, `keys/`, and `uploads/` next to it are untouched. It also fills in what it can for `kommserver status`: the version, read from the jar's manifest, and the port, from `installation-ports.properties` next to the jar if present, or — for installs old enough to predate that file — extracted straight out of the jar's own baked-in `application.properties` (komm-hub used to inject each installation's real, client-chosen ports directly into the jar it served, before that moved to an activation-time file written by the server itself). If you already have a `komm-server.service` systemd unit, it'll warn you that `kommserver install-service` is about to overwrite it, and tells you to stop it first.

Since your existing `keys/tls-cert.pem` stays right where it is, `kommserver install-service` skips the activation prompt automatically — there's nothing left to activate.

## Commands

| Command | Does |
|---|---|
| `kommserver` / `kommserver status` | Installed/running state, version, port reachability |
| `kommserver start` / `stop` / `restart` | Controls the OS service |
| `kommserver update` | Checks GitHub for a newer komm-server release, applies with confirmation if the server is running |
| `kommserver logs [-f] [-n N]` | Recent service log output (journalctl on Linux, WinSW's log file on Windows) |
| `kommserver install-service` / `uninstall-service` | Registers/removes the OS service |
| `kommserver tray` | Windows only — the tray icon, launched automatically at login |

`start` / `stop` / `restart` / `install-service` / `uninstall-service` need elevated privileges — `sudo` on Linux, an elevated shell on Windows (the tray icon and the Start Menu/Desktop shortcut already run at the level they need).

## How updates work

`kommserver update` asks GitHub's API directly for [komm-server](https://github.com/B077AS/komm-server)'s latest release and compares it against the version this launcher last installed (tracked in `launcher.properties`, not re-derived from the jar every time). If the server is running, it asks for confirmation before downloading and restarting (`--yes` skips the prompt).

The download itself shows a live progress bar in the terminal — percentage, transferred/total size — redrawn in place on both Windows and Linux, since it's plain JVM code (`\r` line-redraw, no OS-specific terminal APIs involved):

```
[████████████████░░░░░░░░]  67%  (24.1 MB / 35.9 MB)
```

## Building from source (developers)

```
mvn clean package -DskipTests
```

Produces `target/komm-server-launcher.jar`, runnable identically on both platforms
(`java -jar target/komm-server-launcher.jar status`).

The full installer/tarball are normally built by **komm-server's own** release workflow
(not this repo's — see the note at the top of `packaging/windows/komm-server-launcher.iss`),
which checks out this repo's latest release and seeds them with the exact server jar that
release just built — mirroring how [komm-launcher](https://github.com/B077AS/komm-launcher)'s
own installer is built from `komm`'s release workflow. This repo's own `release.yml` only
ever publishes the bare `komm-server-launcher.jar` self-update artifact. To test the full
packaging locally before publishing anything:

```
mvn clean package -Pinstaller   # Windows — needs Inno Setup 6 (winget install JRSoftware.InnoSetup)
                                 # -> target/installer/Komm-Server-Setup-dev.exe
mvn clean package -Ptarball     # Linux tarball — no extra tools needed, works from Windows too
                                 # -> target/komm-server-launcher-linux-amd64.tar.gz
```

Both download the real *latest* `komm-server.jar` (and, for `-Pinstaller`, WinSW) themselves
from GitHub — the only prerequisite is network access. Pass `-Dserver.jar=path\to\your\locally-built\komm-server-X.jar`
to bundle a local build instead (useful for testing unreleased server changes), and
`-Dserver.version=X` to label the build either way; it defaults to `dev` since there's no
release tag to read it from locally.

## Under the hood

| Package/class | Responsibility |
|---|---|
| `commands/` | One picocli subcommand per verb — `StatusCommand`, `UpdateCommand`, `InstallServiceCommand`, `LogsCommand`, `TrayCommand`, … |
| `service/ServiceController` | `WindowsServiceController` (WinSW + `sc.exe`) and `LinuxServiceController` (`systemctl`/`journalctl`) behind one interface |
| `update/ServerUpdateManager` | Checks and applies komm-server updates via `GithubReleaseClient` |
| `config/LauncherConfig` | This launcher's own small local state (`launcher.properties`) — where the server jar lives, which version was last installed |
| `Platform` | OS detection and the fixed install locations (`C:\ProgramData\Komm\Server`, `/opt/komm-server`) |
| `tray/TrayApp` | The Windows-only resident tray process — separate from the service itself (Session 0 isolation) |

## Tech stack

| Layer | Technology |
|---|---|
| Language / runtime | Java 21 |
| CLI | [picocli](https://picocli.info/), [Jansi](https://github.com/fusesource/jansi) for ANSI color on legacy Windows consoles |
| Windows service | [WinSW](https://github.com/winsw/winsw), wrapped via `sc.exe` |
| Linux service | `systemd` unit, controlled via `systemctl`/`journalctl` |
| Tray icon | AWT `SystemTray` (Windows only) |
| Packaging | Inno Setup 6 (Windows installer), Ant `<tar>` via `maven-antrun-plugin` (Linux tarball), `maven-shade-plugin` (fat JAR) |
| Misc | Gson, Lombok, Logback |

## Related repositories

| Repo | What it is |
|---|---|
| [komm](https://github.com/B077AS/komm) | Desktop client (JavaFX, Windows & Linux) |
| [komm-launcher](https://github.com/B077AS/komm-launcher) | Auto-updating launcher for the desktop client — Windows installer & Linux AppImage |
| [komm-server](https://github.com/B077AS/komm-server) | Self-hosted community server (single JAR, embedded database) |
| komm-server-launcher | This repo — installs, updates, and runs komm-server as an OS service |
| [komm-hub](https://github.com/B077AS/komm-hub) | Accounts, friends & DMs, server directory, CA, and the kommvoice.com website |

## FAQ

**Do I have to use this, or can I just run the JAR myself?** You can always run `java -jar komm-server.jar` directly, same as before — this is purely for anyone who wants komm-server to survive reboots and crashes without babysitting a terminal window.

**What's the verification code it asks for?** The one-time setup token for the server installation you created in the [komm](https://github.com/B077AS/komm) desktop client — open the installation's options menu there and choose "Get Verification Code" (only available while it's still `NOT_VERIFIED`; the hub clears the code the moment the server activates). It's the same token komm-server itself would ask for on first boot if you ran the JAR manually — `install-service` just collects and hands it off for you.

**Does uninstalling remove my server data?** No — `uninstall-service` only stops and deregisters the OS service registration. `komm-postgres-data/`, `keys/`, and `uploads/` next to the server jar are untouched.

**Why doesn't `kommserver update` update the launcher itself?** A process overwriting the jar it's currently running from is fragile by nature — the client/launcher pair avoids exactly this by having the client update the launcher, never the reverse. `kommserver update` only ever touches the managed komm-server jar.

## License

This project is licensed under the [MIT License](LICENSE).
