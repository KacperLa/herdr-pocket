# Herdr Pocket: Android proof of concept

Independent application ID: dev.kaspy.herdr. Version 0.53 (code 53).

## Connect to existing Herdr TUI agents

Tap **Connect to Herdr TUI** to use the agents from the running server you normally see after SSH, then `herdr`. It uses the same SSH user and default Herdr session. If you normally run `herdr --session NAME`, enter that name under **Advanced options** before connecting. A named session is used consistently for list, attach, switching and reconnect. The separate **Connect to Herdr Core** button still uses hc.

This app uses read-only `herdr api snapshot` and `herdr terminal attach TERMINAL_ID` inside pinned SSH. If the snapshot is unavailable, it uses `herdr agent list` and, where supported, one metadata list each for panes, tabs and workspaces. Direct attach is interactive rendered ANSI over an SSH PTY. It never adds `--takeover`, starts a server or agent, changes the remote configuration, or falls back to Core automatically. If another direct client owns the agent, it must detach before retrying. Pocket lists ordinary Herdr panes in the connected roster’s Spaces tab, using the snapshot or legacy pane list when available. Linux/macOS direct attach only. The exact v0.9.5 CLI and protocol shape were inspected. Upstream direct attach does not forward Kitty images. Resize uses SSH PTY dimensions, and mouse/scroll are available when the host's Herdr client negotiates them. Pixel layout and the user's installed Herdr are not yet verified.

While connected, opening **Agents** refreshes the list over the existing SSH connection. Returning to the current row keeps its original terminal. For another TUI agent, Pocket stages a new PTY on that connection and displays it as soon as Herdr sends the complete first repaint, including an empty screen. If attach is refused, canceled or never sends a supported repaint, the previous terminal stays open. Fast in-app switching supports Herdr 0.6.6 through 0.9.x with a compatible running server; for other versions, use the full TUI via **Advanced options → Run custom attach command** instead. No upgrade or takeover is performed. Real switch time depends on the host and network.

The terminal header uses the current pane’s display name. Agents cards show the agent name, runner, workspace, and a short description. Agent rows use a robot avatar with a status badge in both Agents and Spaces; ordinary terminals keep a terminal icon. Long-press a TUI card and choose **Rename pane** to update its label. The app checks that the pane still belongs to the same terminal before writing. Internal terminal IDs, not titles, route requests.

On the connected roster, **+** creates and opens a plain shell with no runner prompt. On supported Herdr TUI (0.7.5–0.9.x), it creates a tab in the workspace of the current pane. On Core, it starts the configured shell on the SSH host and finds the new terminal in a fresh roster before attaching. The previous terminal stays attached if opening the shell fails. A regular pane is reclassified as an agent on refresh when Herdr reports it.

Switch between **Agents** and **Spaces** using the pill in the connected roster header. Agents lists recognized agents in Herdr attention order: blocked, done, working, idle, then unknown. For equal priority, Pocket uses the most recent reported state change when available. Spaces groups agents and terminals by workspace, preserving the Go to picker’s workspace, tab, and split-pane order. Spaces also uses Go to’s pane labels and split numbering. Use the rounded search field at the bottom of either tab to find a name, agent type, tab, workspace, or path, or filter to Blocked, Working, Idle, or Done. This only filters the list on your phone; it does not focus or change any pane on the host. If the backend does not provide workspace identities, those rows appear under Unassigned.

Pinch to resize the terminal down to 6sp. The quick-action row starts with Tab, Esc, Ctrl, Alt, slash, and Paste. The open D-pad has a center Enter key, with icons for Home, End, Page Up, and Page Down.

## Agent Files

Inside a connected Herdr TUI agent or plain shell, tap **⋮**, then **Files** to browse the selected terminal’s folder without leaving the terminal session. On stable Herdr TUI 0.9.0 or newer with working-directory metadata, Files opens the active process’s folder. If Herdr only provides a pane/workspace folder, the explorer labels it as such. Core and custom attach modes do not resolve agent folders. The server must support SFTP on the same SSH account.

Files previews the first 128 KiB of regular UTF-8 text and opens supported JPEG, PNG, GIF, WebP, and BMP images subject to size and pixel limits. Image previews have zoom controls; text previews have syntax highlighting, wrap, and line-number options. You can download the complete original file through Android’s document picker (up to 128 MiB), including readable files that cannot be previewed.

In a folder, the header’s **⋮** menu offers **Upload** and **New Folder**. Upload is limited to 128 MiB and requires an SFTP server that can publish without overwriting existing entries. It uses temporary foreground protection while the Android picker and upload are active, independently of Agent Alerts. Choose **Delete file** from a regular file’s row menu and confirm the exact name and parent folder to remove it. Files does not follow child symlinks or delete directories.

The header shows the file name in a preview and the current path in a folder. Long paths preserve the beginning. The up arrow goes to the parent within the agent’s root. Back goes from a preview to its folder, from a subfolder to its parent, and from the Files root to the Agent Menu.

## Implemented
A readable native connection form, strict SSH SHA-256 host pinning, local private-key document selection, native Core terminal list, and Compose PTY terminal powered by ConnectBot Termlib 0.3.15.

`hc ls --json` runs without a PTY. Selecting a live row runs a POSIX-shell-quoted `hc attach -- '<address>'` with an xterm-256color PTY. This uses the Core CLI over SSH, not the Core terminal.attach RPC. The configurable herdr command opens the full TUI only as a fallback. Core CLI syntax/JSON were inspected at herdrdev/herdr-core c7517808d64f06609e4ecf0db1757005373cc3a3. Incompatible versions show an error, not an invented empty list.

The list includes shells because Core does not expose a separate agent registry. Status is a manual snapshot, not a subscription. The list command may auto-start Core's background server if absent, but does not start an agent.

## Security and lifecycle
Only public-key authentication. An unfamiliar host is probed without authentication. The app shows the exact host, port and SHA-256 fingerprint with a warning that the offered identity is not independently verified. Compare it through a trusted route if possible, then explicitly tap Trust on first use to save it. Saved fingerprints are scoped to normalized host and port. Changing either does not replace another saved pin. A known mismatch is always rejected before authentication and cannot be accepted in the first-use dialog. A valid prior fingerprint is migrated only to the saved endpoint; there is no automatic acceptance or overwrite.

The app remembers a read-only Android document grant, not a copy of the SSH key. If Android revokes access or the file goes away, select it again. To save a typed key passphrase, explicitly check Remember the passphrase I enter. The app saves it only after authentication, using AES-GCM with a nonexportable Android Keystore key. The ciphertext binds to the host, port, username, pinned fingerprint, Android file identity and the file's SHA-256. It is never used for an unknown or different profile. Forget key and changing the selected file clear the saved passphrase; Forget saved passphrase leaves the key selected. Credentials are excluded from backups, activity state and logs. Android may still hold the typed text in process memory; byte arrays are wiped where practical. No telemetry.

ViewModel survives rotation/fold recreation. PTY dimensions follow actual layout. Android may stop the process in background; always-on access is not promised. Selected Core targets retry an established transport loss at most three times (1/2/4-second backoff), using the exact same pinned host and attach address. Auth errors, pin mismatch and explicit remote exit stop retries. Custom commands remain manual to avoid repeating side effects. Arbitrary custom commands may have side effects: use an attach-only command. Duplicate connect requests are rejected; disconnect cancels pending network and file-read generations. Process restart reuses the selected file only while its document grant remains valid.

OSC clipboard is not connected; URLs are not automatically opened. Kitty images are enabled within upstream resource limits.

## Background agent alerts

While connected to a verified Herdr TUI terminal, use the gear to open **Connection**, then tap **Enable agent alerts** and allow notifications. Pocket uses the same pinned SSH profile on a separate connection for the watched session, so terminal switching does not interrupt the watcher. It uses SSH Unix-domain socket forwarding to read Herdr's event stream. No Firebase, ntfy, host script, or periodic status polling. An older Herdr without supported events needs an upgrade; if the SSH server disallows socket forwarding, that is a separate prerequisite. The foreground notification lets you stop alerts. Android process/network restrictions or force-stop can interrupt alerts, and events missed while offline are not replayed.

## Workspace architecture
AgentWorkspace is the primary identity, linked to a separate HostProfile/SshConfig. Terminal renderer and SSH lifetime are separate. Future per-agent voice/desktop sessions can use distinct endpoints and lifecycles without changing agent identity. Neither protocol is implemented; no placeholder controls or permissions are included.

## Demo and validation
The local demo writes a generated PNG as real Kitty escape bytes into the actual terminal emulator. It is labeled LOCAL DEMO and does not simulate successful SSH/Herdr access. Transport tests use only synthetic loopback keys.

Android rendered pixels, folding/IME behavior, Herdr image forwarding and image survival after Herdr reattach remain to be tested on device. A successful Kitty query alone is not rendering proof. See VALIDATION.md for final build outcomes.

## Licenses
ConnectBot Termlib: Apache-2.0; libvterm: MIT; mwiede JSch: BSD-3-Clause; Bouncy Castle: MIT-style. Notices are bundled. Independent prototype, not an official Herdr app.

## Terminal links

Tap a complete visible `http://` or `https://` address to open it in Android's default browser. Links embedded in separate OSC 8 text ask you to confirm the actual destination first. Terminal mouse clicks continue to reach the remote app everywhere else. Only normal web addresses are supported. A URL split across rows can open when the terminal stream preserves its actual soft wrap; an ambiguous clipped or hard-wrapped link remains ordinary terminal text.

## Build
Install JDK 21, Gradle 9.9.1, Android SDK platform 37.0 and Build Tools 36.0.0 through their official distributions. Set ANDROID_HOME and JAVA_HOME; point sdk.dir in your own local.properties at that SDK. Run:
    gradle :app:assembleRelease :app:testDebugUnitTest :termlib:testDebugUnitTest :app:lintRelease
The release APK is unsigned. Final signing belongs to the stable private application keystore maintained outside this source tree. No keys or passphrases are included.
Run bash tests/ssh/run.sh for the disposable transport fixture. See VALIDATION-SSH.md for dependencies and limits.
Core and Herdr TUI own separate session sets. The native picker lists only the backend you choose; optional custom commands do not enumerate agents.

## GitHub Actions
The Build unsigned APK workflow runs the release build, unit tests and lint. Download its unsigned APK and SHA-256 checksum from the run's Artifacts section. The APK must be signed separately before installation. No signing keys or credentials are used by this workflow.

## Install Herdr Core on the SSH host
On the connection screen, tap **Copy HC install command** near **Connect**. Paste and run it on the Linux or macOS SSH host as the same user you connect with. The app copies the [upstream installer command](https://github.com/herdrdev/herdr-core#install) only; it does not execute it or alter the current connection. Installing Core does not migrate Herdr TUI agents.

Native list and selected-session attach/reconnect look for hc on the non-interactive SSH PATH first, then for an executable ~/.local/bin/hc under that SSH user's home. They do not source interactive shell startup files. Custom commands are sent unchanged.

The standard connection screen asks for host, SSH username and private key. Connect opens the remembered live session for that host/port/login, otherwise an unambiguous session named main, otherwise the only live session. If several live sessions remain ambiguous, pick one. Choose another Core session always shows the picker; an explicit choice is remembered once the terminal responds and remains connected. No session is created automatically. Port, custom commands, manual host check and the local demo are under Advanced options.

While attached to a Core session, tap the back arrow next to the agent name to open the **Agents** list. The app lists live sessions over the existing authenticated SSH connection. Pull down from the top of the Agents list to refresh; TalkBack also offers a Refresh agents action on the list. Healthy rows appear without a status heading and scroll behind the floating gear and plus under a gentle top fade. Errors and empty states remain visible. Tapping the current session or using Android Back returns to the same open terminal. Choosing a different session opens its PTY on that connection, then closes only the old viewing channel; the Core-owned session stays running. Custom attach commands do not show the Agents switcher.

The **+** action does not retry creation automatically. If the result isn't clear, refresh the roster and look for the new terminal before tapping **+** again. Connecting, selecting an existing row, and reconnecting never send creation commands.

Pinch changes the actual terminal font size when you release, and the new rows and columns are sent to the SSH PTY. Swiping still scrolls available local history. Core sessions request up to 1,000 lines of primary-screen history when the remote hc supports it; alternate-screen apps do not expose that as local scrollback. Keyboard rows come from the installed Android keyboard. Disconnect is in Connection rather than the terminal shortcut row.

When a remote terminal application enables SGR mouse reporting, vertical touch swipes and a physical mouse wheel send wheel events to it at the pointer's terminal cell. With no negotiated reporting, the physical wheel scrolls local Termlib history without sending keys to the server. In particular, supported Codex versions handle this inside the transcript area. Without negotiated reporting, ordinary terminal history still scrolls locally. Pinch remains font/grid resize. This does not send arrow keys or force mouse capture on a remote application.

The terminal dock includes one-shot Ctrl and Alt toggles (tap again to cancel), slash, Esc, Tab and an arrow-key popup, plus the microphone and a software keyboard toggle. A modifier applies to the next native key or IME commit, not raw replies or dictation. Switches and disconnects clear modifiers. Scroll the shortcut strip horizontally on narrow screens. The microphone uses Android on-device dictation. It has a red background only while listening, with no extra status line below the dock. Its status remains available to accessibility services. The final line is submitted to the current terminal with Enter. No cloud fallback is provided. Canceled or empty dictation sends nothing, and moving to a different terminal cancels the request.

Cold startup attempts one bounded reconnect to the last live Core agent when the saved SSH profile, pinned host key and document grant are still valid. It uses the encrypted passphrase only if it is bound to that profile and key. An unknown or changed SSH key is never trusted automatically. If the previous agent is absent, pick one manually. From an active Core terminal, open **Agents** and tap the top-left gear for **Connection settings**. The connected settings back arrow returns to **Agents** (directly to the terminal for a custom connection) without disconnecting. Android Back from Agents returns to the current terminal. Entering Agents hides the software keyboard, and the terminal only reopens it when tapped.

If a visible remote terminal disconnects, its last screen stays dimmed and read-only. An automatic retry shows centered **Reconnecting…**; a failed attempt offers centered **Reconnect** only when the same configured profile and pinned SSH host are eligible. **Connection settings** remain reachable for passphrase/key errors or a changed host key. Initial connection errors still appear in Connection settings. Retry limits and saved-target rules are unchanged; absent agents return to the picker. A custom retry reruns only the previously chosen command. The terminal no longer displays its dimensions line; pinch still resizes the live terminal.

A single-finger tap or an unmodified primary Bluetooth mouse click sends a left click to a live terminal application only while it has negotiated SGR cell-based mouse reports. Gestures and text selection keep their existing behavior. In ordinary terminal apps without mouse tracking, a tap still opens the keyboard; the keyboard dock button remains available in mouse-aware apps.
