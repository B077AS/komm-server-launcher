; Inno Setup script for the komm-server launcher / OS-service manager.
; Builds Komm-Server-Setup-<version>.exe.
; Requires Inno Setup 6 (winget install JRSoftware.InnoSetup).
;
; This is invoked from komm-server's OWN release workflow (not this repo's) — mirrors
; how the komm client's release workflow checks out komm-launcher@latest-release and
; builds the installer from there, seeded with the exact jar that release just built.
; komm-server-launcher's own release workflow only ever publishes the bare self-update
; jar; it never runs this script itself.
;
; Expects, staged into {#SourceDir}:
;   komm-server-launcher.jar   - this repo's fat jar (mvn clean package)
;   komm-server-service.exe    - WinSW, downloaded and renamed by the calling workflow
;   kommserver.cmd             - PATH shim, packaging/windows/ in this repo
;   komm-server.jar            - optional: the server jar to seed, so the installer
;                                 produces a working, running service with nothing
;                                 further to run — omit for a launcher-only build
;   launcher.properties        - optional: written by the calling workflow with
;                                 server.version=X, so `kommserver status` shows the
;                                 right version immediately (LauncherConfig otherwise
;                                 defaults it to "unknown" until the first `update`)
;
; activate.cmd and icon.ico are referenced directly from packaging/windows/ below (like
; this script itself) rather than staged into {#SourceDir} — they're static repo assets,
; not something the calling workflow needs to build or download first.

#ifndef AppVersion
  #define AppVersion "0.0.1"
#endif
#ifndef SourceDir
  #define SourceDir "..\..\target\installer-input"
#endif
#ifndef OutputDir
  #define OutputDir "..\..\target\installer"
#endif

[Setup]
AppId={{B6C1B6F4-6B3E-4A0D-9C7B-1A2B3C4D5E6F}
; "Komm Server", not "Komm Server Launcher" everywhere the user actually sees this —
; they're installing what they think of as the server, not a separate "launcher"
; concept. The technical filenames (komm-server-launcher.jar, this repo/script's own
; name) stay as they are; this only covers user-visible text.
AppName=Komm Server
AppVersion={#AppVersion}
AppPublisher=Komm
DefaultDirName={commonappdata}\Komm\Server
; Fixed, not user-changeable — Platform.installDir() in the launcher jar hardcodes this
; exact path rather than reading it back from the registry, so the install location and
; the code's assumption about it must never be able to drift apart.
DisableDirPage=yes
DisableProgramGroupPage=yes
OutputDir={#OutputDir}
OutputBaseFilename=Komm-Server-Setup-{#AppVersion}
Compression=lzma2
SolidCompression=yes
PrivilegesRequired=admin
ArchitecturesInstallIn64BitMode=x64compatible
WizardStyle=modern
SetupIconFile=icon.ico
UninstallDisplayIcon={app}\icon.ico
; Closes the tray process automatically if it's holding komm-server-launcher.jar open
; during an install/uninstall — same directive the client installer uses.
CloseApplications=yes

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "{#SourceDir}\komm-server-launcher.jar"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#SourceDir}\komm-server-service.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#SourceDir}\kommserver.cmd"; DestDir: "{app}"; Flags: ignoreversion
Source: "icon.ico"; DestDir: "{app}"; Flags: ignoreversion
Source: "activate.cmd"; DestDir: "{app}"; Flags: ignoreversion
Source: "stop-tray.ps1"; DestDir: "{app}"; Flags: ignoreversion
#ifexist SourceDir + "\komm-server.jar"
Source: "{#SourceDir}\komm-server.jar"; DestDir: "{app}"; Flags: ignoreversion
#endif
#ifexist SourceDir + "\launcher.properties"
Source: "{#SourceDir}\launcher.properties"; DestDir: "{app}"; Flags: ignoreversion
#endif

[Icons]
; Opens a persistent interactive prompt (/k, not /c) — a shortcut someone clicks is
; meant to leave them somewhere they can keep typing kommserver commands.
Name: "{group}\Komm Server"; Filename: "{sys}\cmd.exe"; \
    Parameters: "/k ""{app}\kommserver.cmd"" status"; WorkingDir: "{app}"; IconFilename: "{app}\icon.ico"
Name: "{autodesktop}\Komm Server"; Filename: "{sys}\cmd.exe"; \
    Parameters: "/k ""{app}\kommserver.cmd"" status"; WorkingDir: "{app}"; IconFilename: "{app}\icon.ico"; \
    Tasks: desktopicon
; Machine-wide (not {userstartup}) — consistent with the machine-wide install itself,
; and avoids the elevation/per-user-area ambiguity a plain {userstartup} entry has when
; PrivilegesRequired=admin (see Inno Setup's UsedUserAreasWarning).
Name: "{commonstartup}\Komm Server Tray"; Filename: "javaw.exe"; \
    Parameters: "-jar ""{app}\komm-server-launcher.jar"" tray"; WorkingDir: "{app}"; IconFilename: "{app}\icon.ico"

[Registry]
; Add {app} to the machine PATH so `kommserver` works from any terminal.
Root: HKLM; Subkey: "SYSTEM\CurrentControlSet\Control\Session Manager\Environment"; \
    ValueType: expandsz; ValueName: "Path"; ValueData: "{olddata};{app}"; \
    Check: NeedsAddPath('{app}')

[Code]
var
  ActivationPage: TInputQueryWizardPage;

const
  WM_SETTINGCHANGE = $001A;
  SMTO_ABORTIFHUNG = $0002;

function SendMessageTimeoutA(hWnd: Longint; Msg: Longint; wParam: Longint; lParam: PAnsiChar;
  fuFlags, uTimeout: Longint; var lpdwResult: Longint): Longint;
  external 'SendMessageTimeoutA@user32.dll stdcall';

// Writing the new PATH into the registry (see [Registry] below) does not, by itself,
// tell any already-running process — including Explorer — that it changed. Without
// this broadcast, even a Command Prompt opened fresh *after* installing still inherits
// Explorer's stale cached environment, and "kommserver" resolves to nothing, until a
// full logoff. This is the standard fix: broadcast WM_SETTINGCHANGE so Explorer (and
// anything else listening) picks up the new PATH immediately, without needing a reboot
// or logoff — this only helps windows opened after this runs, though; one already open
// keeps whatever PATH it already had regardless.
procedure RefreshEnvironment;
var
  ResultCode: Longint;
begin
  SendMessageTimeoutA(HWND_BROADCAST, WM_SETTINGCHANGE, 0, 'Environment', SMTO_ABORTIFHUNG, 5000, ResultCode);
end;

function NeedsAddPath(Param: string): boolean;
var
  OrigPath: string;
begin
  if not RegQueryStringValue(HKLM, 'SYSTEM\CurrentControlSet\Control\Session Manager\Environment', 'Path', OrigPath) then
  begin
    Result := True;
    exit;
  end;
  Result := Pos(';' + Param + ';', ';' + OrigPath + ';') = 0;
end;

// Same check InstallServiceCommand.ensureSetupToken makes on the Java side: a
// hub-issued cert already on disk means this install already activated at some point
// (there's nothing left to ask for); an already-present setup-token.txt means a code
// was already provided (by an earlier attempt, or by this same page on a re-run) and
// is just waiting to be consumed on next start.
function NeedsActivation(): Boolean;
begin
  Result := (not FileExists(ExpandConstant('{app}\keys\tls-cert.pem')))
    and (not FileExists(ExpandConstant('{app}\setup-token.txt')));
end;

procedure InitializeWizard;
begin
  ActivationPage := CreateInputQueryPage(wpSelectTasks,
    'Server Activation', 'Enter your verification code',
    'Paste your verification code here so this server can activate automatically. Leave ' +
    'this blank to do it later by running "kommserver install-service" yourself from a ' +
    'Command Prompt.');
  ActivationPage.Add('Verification code:', False);
end;

function ShouldSkipPage(PageID: Integer): Boolean;
begin
  Result := False;
  if PageID = ActivationPage.ID then
    Result := not NeedsActivation();
end;

// activate.cmd (install-service, start, wait for the TLS auto-restart, status) runs
// via ExecAndCaptureOutput rather than a spawned cmd window — its output is captured
// programmatically instead of appearing in a terminal of its own, so this whole step
// stays entirely inside the installer, matching how Linux's install.sh already runs
// everything in the one terminal the operator invoked it from.
procedure CurStepChanged(CurStep: TSetupStep);
var
  Code: String;
  ResultCode: Integer;
  Output: TExecOutput;
  OutputText: String;
  I: Integer;
  ProgressPage: TOutputProgressWizardPage;
begin
  if CurStep = ssPostInstall then
  begin
    RefreshEnvironment;

    Code := Trim(ActivationPage.Values[0]);
    // Written before activate.cmd runs below — if a code was entered here,
    // install-service finds this file already in place and skips its own console
    // prompt entirely; leaving it blank falls back to that prompt, which (since there's
    // no interactive window for it to go to from here) just becomes part of the
    // captured failure output shown below instead.
    if Code <> '' then
      SaveStringToFile(ExpandConstant('{app}\setup-token.txt'), Code, False);

    if FileExists(ExpandConstant('{app}\komm-server.jar')) then
    begin
      // The long explanation goes in the page's own Description (wraps properly,
      // unlike SetText's labels, which are single-line and just overflow past the
      // page edge instead of wrapping) — SetText is reserved for a short status line.
      // Marquee style gives continuous visible motion for the whole blocking call
      // below (there's no way to push incremental text updates during it — it's one
      // synchronous ExecAndCaptureOutput — so a static bar would otherwise look frozen
      // for the entire ~30 seconds this can take).
      ProgressPage := CreateOutputProgressPage('Setting Up Komm Server',
        'The server restarts itself automatically once, after first activation, to ' +
        'start serving HTTPS. This can take up to about a minute — please wait.');
      ProgressPage.ProgressBar.Style := npbstMarquee;
      ProgressPage.Show;
      try
        ProgressPage.SetText('Registering and starting the service...', '');
        if (not ExecAndCaptureOutput(ExpandConstant('{sys}\cmd.exe'),
              '/c "' + ExpandConstant('{app}\activate.cmd') + '"',
              ExpandConstant('{app}'), SW_HIDE, ewWaitUntilTerminated, ResultCode, Output))
           or (ResultCode <> 0) then
        begin
          OutputText := '';
          for I := 0 to GetArrayLength(Output.StdOut) - 1 do
            OutputText := OutputText + Output.StdOut[I] + #13#10;
          for I := 0 to GetArrayLength(Output.StdErr) - 1 do
            OutputText := OutputText + Output.StdErr[I] + #13#10;
          MsgBox('Komm Server did not finish setting up automatically:' + #13#10#13#10 +
            OutputText + #13#10 +
            'You can finish this yourself later by running "kommserver install-service" ' +
            'and "kommserver start" from a Command Prompt.', mbError, MB_OK);
        end;
      finally
        ProgressPage.Hide;
      end;
    end;
  end;
end;

[Run]
; Registering and starting the service (when a server jar was seeded) now happens in
; CurStepChanged above, via ExecAndCaptureOutput — no [Run] entry needed for it.
Filename: "javaw.exe"; Parameters: "-jar ""{app}\komm-server-launcher.jar"" tray"; \
    Description: "Start the Komm Server tray icon now"; Flags: nowait postinstall skipifsilent

[UninstallRun]
; Without this, uninstalling just deletes the launcher's files and leaves the Windows
; Service registration behind pointing at nothing — exactly the orphaned-service mess
; that has to be cleaned up by hand (sc delete, etc.) otherwise. install() already
; stops the service before unregistering it, so one call covers both.
Filename: "{app}\kommserver.cmd"; Parameters: "uninstall-service"; \
    Flags: runhidden; RunOnceId: "UninstallKommServerService"
; CloseApplications=yes (Restart Manager) doesn't reliably catch the tray process — see
; stop-tray.ps1 — so this finds and closes it explicitly by command line instead.
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; \
    Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\stop-tray.ps1"""; \
    Flags: runhidden; RunOnceId: "StopKommServerTray"
