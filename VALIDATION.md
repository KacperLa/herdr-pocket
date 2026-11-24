# Validation and remaining device checks
Date: 2026-11-21.

## Passed
- Eight JVM tests: strict SSH SHA-256 pin formatting/rejection, generation cancellation/duplicate prevention, compact Core JSON parsing, shell quoting.
- Disposable loopback SSH server with fresh synthetic encrypted Ed25519 key: no authentication for unknown/mismatched pin; verified key authentication; non-PTY JSON list and separate stderr; PTY attach to selected target; terminal I/O and character/pixel resize; no duplicated exec on explicit reattach.
- Hard 1 MiB list stdout cap, 8 KiB stderr cap, real 30-second list deadline; subsequent connection remains possible.
- Abrupt TCP loss classified separately from normal exit and channel-only closure. UI bounded reattach uses only an existing selected Core target; no automatic retry for arbitrary fallback command.
- Compose source, Android packaging, unit tests and release lint checked via Gradle. Final report/hashes supplied separately after the last build.
- Termlib arm64 ELF load segments support 16 KiB pages. Final APK native libraries also inspected before handoff.

## Not tested on device
No Android emulator or physical handset was available. No real host or private key was accessed. Synthetic transport success is not a claim of compatibility with the user's installed Core version. The actual Android rendered PNG pixels, Fold recreation/PTY resize, keyboard, per-agent Herdr graphics forwarding, background process survival and UI retry timing remain unverified.
The local Kitty demo creates a PNG and feeds protocol bytes into the real upstream renderer, but its visible result still needs confirmation on the handset.

## Quick handset checks
1. Open Herdr Pocket, tap Local Kitty image demo. Confirm the green circle and Kitty PNG text render as an image. Pinch to enlarge terminal text, fold/unfold and check the displayed column/row count changes.
2. Enter host/port/user. Check host fingerprint; cancel once and check again. Compare the offered fingerprint independently against the server host public key, then tap Trust on first use. A changed pin must be rejected, never silently replaced.
3. Choose a private key using Android's picker, enter optional passphrase, then Load Core sessions. This uses hc ls --json, not a TUI-owned agent list.
4. Choose a live session and verify it is the expected agent/address. Type, use Esc/Tab/Ctrl-C, then fold and unfold.
5. Drop and restore networking during an established selected Core session. Verify bounded reattach returns to the same session, without creating another agent. Explicit disconnect and remote exit must not restart it.
6. Run a trusted Kitty PNG command in that session; confirm actual pixels, then repeat after detach/reattach. The local demo does not prove remote graphics forwarding.

## Previous 0.1 snapshot outcome
Release assemble, all eight Gradle unit tests, and lint passed on the final frozen source. Lint: zero errors, five non-blocking warnings (target API compatibility, test-only JSON version, two Kotlin-style suggestions and x86_64 ChromeOS support omitted from this ARM64 build). ARM64 only, min API 34, target API 36. Both packaged ELF libraries have 0x4000 LOAD alignment.
Unsigned APK SHA-256: 147fce08546ad2b909591c13616a44dc250ca4be2a5ef378e10ba51b81926e25.
Signing/ZIP page alignment verification occurs separately in the stable signing workflow.

## Herdr Pocket 0.2
The new first-use prompt shows the normalized endpoint and exact SHA-256 server fingerprint. It runs after a credential-free probe. Cancel leaves the endpoint unpinned. Acceptance saves only that endpoint; authentication and reconnect still require a match. A known changed key has no trust override. The prior valid fingerprint migrates only to its saved endpoint.
The APK remains ARM64; DEX ZIP compression is configured via Android Gradle Plugin, with native libraries and resource table still stored. No native or app code shrinking is applied.
Thirteen JVM tests passed, including first-use acceptance, cancellation, endpoint isolation, migration and immutable mismatched pins. The disposable SSH loopback suite passed and confirmed missing/mismatched pins sent no authentication. Android device/UI interaction was not tested.

## Herdr Pocket 0.4
The native Core CLI resolver uses POSIX sh and checks the non-interactive PATH before ~/.local/bin/hc. Unit shell fixtures cover both commands, PATH preference, a home path containing spaces, literal shell-sensitive addresses, unset HOME, and missing or non-executable hc. Session selection tests cover remembered preference, unambiguous main, sole live, ambiguity/no live sessions, and endpoint/login scoping. The SSH loopback fixture checks the command syntax without executing it. Android device behavior and the actual SSH host were not tested.

## Herdr Pocket 0.5
Device checklist: attach to a live Core session, then use the back arrow next to the active agent name to open Agents. Cancel and verify the current terminal still takes input. Switch to a second live session, confirm its name and fresh terminal, then verify the first session is still running. After a network drop, confirm reconnect returns to the newly selected agent. The custom command terminal should not display Agents. Disposable SSH fixture results are in VALIDATION-SSH.md. No live user host was accessed for this build.
The core terminal header floats above terminal content. The separate Agents list includes an explicit **+** form; local shell fixtures check the supported background/no-parent invocation, name constraints and literal program argument. No creation is attempted automatically or against the user's host in validation.

## Herdr Pocket 0.6
The Android file picker grant is retained read-only and rechecked at process start. Revoked access is cleared; failed opens ask the user to pick the key again. The passphrase opt-in is off by default and saves only a typed passphrase after an authenticated connection. Plaintext is not stored in preferences or Android saved state. AES-GCM scope tests cover endpoint, user, key file and content, and pinned fingerprint changes. Android Keystore, document-provider restart/revocation and gesture display remain device checks.

Pinch updates the real font size after release. Termlib recalculates its terminal grid and the SSH resize callback uses the current PTY. The loopback checks resize routing to the new PTY after an agent switch. CLI stubs confirm supported main-screen history and fallback for older hc. Shell history and an application's alternate screen are distinct; alternate-screen terminal applications still own their own scrolling. Android keyboards control their number-row layout.

## Herdr Pocket 0.7
The mouse observer uses actual remote terminal mode sequences before touch can transmit SGR wheel reports. The loopback test negotiates pointer capture like Codex, verifies exact SGR up/down bytes on the newly selected PTY, sees a scroll marker on that PTY, and disables reporting again. This tests the SSH wire behavior, not Codex installed on a real host or the Android touch UI.
The dictation tests use fake recognition/send callbacks. The installed Android recognizer, permission UI and microphone were not exercised on a phone. No actual speech, user credential or real host was accessed.

## Herdr Pocket 0.8
Cold-start policy tests cover a one-shot claim, missing prerequisites, manual actions winning before startup, and same-agent discovery. The Android UI starts one bounded attempt from its restored ViewModel; selection, missing credentials and remote errors use the normal settings screen. Android lifecycle and actual startup reconnect were not tested on a handset. Dictation is still on-device-only, with clearer in-terminal feedback for permission, language/model and recognition states.

## Herdr Pocket 0.9
The circular gear at the top left of Agents replaces the in-screen back arrow and is labeled “Connection settings” for accessibility. It uses the same action and busy guard as the removed text button. The terminal's visible arrow, Android Back in Agents, Add, Refresh, and session-state actions are unchanged. Source comparison verified these bindings; actual handset layout and TalkBack were not tested. Quick device check: open Agents from a running terminal, tap the gear, use Return to terminal, open Agents again, then use system Back. Both routes should return to the same running agent.

## Herdr Pocket 0.10
Connection back routing is tested for a live Core session, a live custom terminal, disconnected and pending states, stale or replaced channels, and non-settings screens. The action only changes local navigation; for Core it reopens the live Agents picker on the existing SSH transport. Android Back in Agents still uses the existing dismiss path. Termlib starts each terminal view with software IME requests disabled and enables them only on a terminal tap; entering Agents clears focus and requests IME dismissal. No Android handset was available. On-device smoke test: tap the terminal to open the keyboard, open Agents and check it dismisses, then use gear → back arrow → Android Back to confirm the same terminal returns with the keyboard still closed. Tap the terminal to show it again. Also check that the settings arrow returns directly to a live custom terminal, and disappears after disconnect.

## Herdr Pocket 0.11
The currently attached live agent is still marked “Current session” and is tappable. That branch only dismisses the picker and keeps the same PTY and emulator; the next tap can still open the keyboard. Different live rows use the existing SSH switch flow. Unit tests cover both routes and reject stale, finished or replaced-PTY cases. The gear, Refresh and Add positions are unchanged; the visible heading is gone and “Agents” is exposed as an accessibility pane title. No physical Android or TalkBack check was available.

## Herdr Pocket 0.12
The terminal dock mirrors the supplied black pill, slate keycaps and trailing white icon style. Ctrl+C, Esc and Tab retain their original bytes; the existing mic keeps its on-device recognition, cancellation, and inline status. The keyboard toggle acts on the local Termlib IME only. Its state is scoped to the displayed terminal and resets on navigation, with tests for the toggle, initial hidden state, and system dismissal. The reference's ambiguous extra symbols are omitted rather than mapped to invented actions. Actual dock appearance, portrait/inner-display behavior at large font scales, and IME animations remain handset checks.

## Herdr Pocket 0.13
Dictation no longer adds a visible text row below the dock, so the terminal height is independent of speech status. The existing microphone target has a solid red background only after recognizer readiness/beginning of speech and before end of speech or any cancellation/error/timeout; waiting, microphone permission, checking and finishing do not turn it red. The status remains available as a polite accessibility state description. Unit tests cover readiness, finish and late callbacks. Android recognizer callbacks and TalkBack were not tested on a handset.

## Herdr Pocket 0.14
The terminal size/pinch information label is no longer rendered; Termlib's grid and pixel resize path still calls SSH. Reconnect is shown after a failed attempt only for the matching idle host, port, login, saved pin and selected key. A changed host key and deliberate disconnect do not offer retry. Core retry prefers the exact previous live agent and opens the picker if it is unavailable; the custom retry matches the original command. Connection error text remains visible. Unit tests exercise profile and command eligibility, plus existing strict pin and exact-agent selection coverage. Android UI and actual remote network recovery were not tested on a handset.

## Herdr Pocket 0.15
Single-finger taps on a compatible mouse-tracking terminal produce one SGR left-button press/release pair at the actual measured visible cell, with no local keyboard action for a delivered remote tap. No compatible mouse mode leaves Termlib handling the tap. Tap slop, long presses, multiple fingers, consumed gestures, local selection, the extra right/bottom gutter, and replaced PTYs/mode generations are rejected. Existing wheel, pinch and non-mouse scroll behavior remains. Unit tests cover tap policy, bounds, mode, and exact SGR bytes; the loopback fixture asserts a matched press/release only on the switched/resized PTY. Actual Android gesture dispatch and the user's remote TUI have not been tested on-device.

## Herdr Pocket 0.16 test
Uses the verified official Herdr v0.9.5 CLI operand shape, JSON envelope, and non-takeover direct attach. Tests cover shell arguments with a controlled executable, malformed/unsupported/empty discovery, no session or backend crossover, and error messages. Upstream v0.9.5 builds the direct client with no Kitty graphics forwarding. A real Herdr server fixture could not start on the build executor because AF_UNIX sockets are prohibited, and the user's server and a physical Android device were not accessed.

## Herdr Pocket 0.17
Herdr TUI stages a second SSH PTY on the already-authenticated transport. A finite timeout is a failure only; the old terminal is replaced only after a complete, synchronized Herdr initial repaint with a clear-screen baseline, including an empty screen. The compiled fixture confirmed a fragmented empty frame switched in 142 ms on loopback, ordinary setup bytes did not switch, the old PTY accepted input while the replacement was pending, and an occupied target left the old PTY usable without takeover. The version guard accepts supported 0.6.6–0.9.x clients; incompatible wire versions fail in Herdr itself. Herdr v0.9.5 tagged source and the preceding protocol-version boundary were inspected, not a live Herdr server on the handset. Device/real-host timing not measured. Explicit Herdr agent names and presentation/conversation title fallbacks are tested; internal IDs remain unchanged for CLI routing. 80 Android unit tests passed, lint 0 errors and 14 warnings, and the synthetic SSH harness passed (57 JUnit checks, transport and server request assertions). Release APK passed 16 KiB ZIP and both native ELF segment alignment checks.

## Herdr Pocket 0.18
The Herdr API agent name is shown in both pickers and header, not a hook or terminal conversation title. A neutral runner label is used for an unnamed agent; description, runner, path and status are independent. A refreshed same-ID rename updates the title without detaching; the selected card stays clickable. Status uses readable icons and accessibility descriptions, with rotation disabled when Android reports animations disabled.

A previously visible remote terminal is retained during reconnect and displays a centered action or progress text; the terminal is read-only until a current SSH PTY is available. Error and Connection settings routes remain available. Exact pinned-profile retry, automatic retry limits, original target routing and deliberate cancellation behavior are unchanged. No new dependencies. 83 Android unit tests and the separate SSH loopback fixture (61 focused assertions, same transport first-frame switch in 144 ms, occupied target/refusal and reconnect/SSH checks) passed. Lint: 0 errors, 14 warnings. ARM64 APK/native segments pass 16 KiB alignment. Android rendering, TalkBack, actual fold/IME behavior and timing on the user's server were not measured here.

## Herdr Pocket 0.19
Herdr v0.9.5 GUI Rename routes were traced through `client/shell/context_menu.rs`, `overlay_input.rs` and the public session schema. A GUI pane label is independent of the agent CLI name, as are tab and workspace display labels. The tagged CLI agent rename validator does not accept a spaced label such as Jetson AI. A modern read-only snapshot joins labels by exact IDs and honors precedence explicit agent → pane → non-default tab → workspace → neutral runner. The public workspace snapshot does not have a custom flag on this tag; recognizable defaults from directory/repo metadata are skipped. Optional legacy metadata requests are individually nullable; no remote writes or attach protocol changes. Agent descriptions remain separate. The roster uses a large accessible status avatar at left and one runner/path line.

84 Gradle unit checks and 62 focused checks passed, including rename refresh on the same ID, stale pane ID rejection, a source-shaped Jetson AI workspace, correct tab order, optional legacy query failure and no dangling metadata separators. SSH loopback verified snapshot discovery and same-transport switch (143 ms), refusal retaining original PTY, pin/auth and reconnect. Release lint: 0 errors, 14 warnings; compact arm64 release passes ZIP/native 16 KiB alignment. Local AF_UNIX is unavailable, so the release was not tested with a live Herdr daemon, handset, accessibility service or user server.

## Herdr Pocket 0.20
Long-press a connected TUI roster card to rename exactly its pane. The client verifies terminal/pane/tab/workspace identity before writing, validates Herdr's returned identity and label, and refreshes the roster; it does not rename the shared tab/workspace or canonical agent slug. A manual pane label takes title priority. This is the upstream `pane.rename` API exposed by the CLI; Herdr's API has no atomic compare-and-set precondition, so a user moving/swapping a pane concurrently between the preflight and mutation is not an atomic transaction.

The TUI **+** explicitly creates one new unfocused tab in the current verified workspace, confirms the fresh pane, then invokes `agent start` once from Pocket, using a supported built-in runner and server/client version gate (0.7.5–0.9.x). Tagged source rejects an existing agent/non-shell pane before sending launch input. Pocket passes no arbitrary commands, args, env or cwd, does not change focus, and does not auto-retry or auto-delete an uncertain tab. Core's existing action is unchanged. Loopback asserts the exact five rename/create requests, JSON identity confirmation, and continued old PTY input on the same transport.

Bluetooth wheel input uses the pinned Compose raw vertical scroll ticks with per-PTY/mode fractional accumulation, routes through negotiated SGR at the pointer or scrolls Termlib history locally, and leaves existing touch input unchanged. One isolated Termlib 0.3.15 bridge calls its JVM-public/Kotlin-internal viewport controller directly. Compiled linkage confirmed; this must be revisited if Termlib or Kotlin changes. No reflection, simulated touch or fallback remote keys. No handset/Bluetooth or live Herdr daemon was available; behavior verified against tagged upstream, compiled linkage, and synthetic fixtures only.

90 Android unit tests passed, including 68 focused JVM checks and the full loopback SSH fixture (142 ms staged switch); lint 0 errors/15 warnings, including the known pinned viewport warning. Unsigned compact arm64 APK and both native libraries pass 16 KiB alignment.

## Herdr Pocket 0.21
The connected Agents chooser has no idle heading or visible Refresh button. The gear and plus stay in the header. The full-height LazyColumn retains keyed rows and uses Material3 1.4.0's native nested-scroll pull-to-refresh; the list offers the same action to accessibility services. While a roster request is in flight, the indicator is tied to its exact SSH operation ID. Rename, add, switch and their dialogs block another refresh, errors/empty states/confirmation text remain visible, and graphics are unchanged.

93 Android JVM tests, 71 focused JVM checks and the same-transport synthetic SSH regression suite passed (149 ms first-frame switch in this fixture). Release lint: 0 errors and 15 warnings, including the existing pinned terminal-wheel bridge warning. Native ARM64 and ZIP alignment checks used the release payload. Actual phone touch and TalkBack behavior were not tested.

## Herdr Pocket 0.22
The Agents list uses the full safe viewport with initial padding below the floating gear/plus controls. A non-interactive top scrim gently fades rows behind them. Meaningful notices and the native pull-refresh indicator stay above the list; TalkBack action is preserved.

A Bluetooth primary mouse press/release follows the same one-shot negotiated SGR left-click and measured-cell validation as touch. The input recognizer rejects observable button chords, modifiers, movement outside click slop, multitouch, wheel interruption and stale PTY/mode targets. Only an enqueued down/up pair consumes the release. Wheel and ordinary local gestures remain unchanged.

The dock now uses one-shot Ctrl and Alt, slash, Esc/Tab and a repeatable arrow menu with non-focus-stealing popup behavior. A stable Termlib modifier manager preserves hardware and Android input routes. Native vterm emits key sequences for its current mode; unmodified dictation and automatic terminal replies are not rewritten. Delayed callback routing remains bound to the renderer's exact PTY, even across a channel swap. Changes to Android terminal editor hints were not made: Termlib's public normal-text option also changes editing/commit semantics. System and SSH-field keyboard settings were not changed.

The Android JVM suite (99 tests), focused suite (79 tests), loopback transport fixture and pinned libvterm keyboard tests were checked. No phone, Bluetooth pointer, TalkBack, or live IME/Herdr server test was available. Native TUI direct attach does not relay every inner application mode; popup arrows follow the same renderer path as physical keyboard arrows. Release lint has no errors (existing warnings only), compact ARM64 ZIP/native alignment verified.

## Herdr Pocket 0.23
The terminal requests a normal text layout from the selected Android keyboard without asking for predictive suggestions or personalized learning. The editor remains an immediate raw terminal, not a local compose-and-submit mode. Explicit IME commits (including Unicode) go through the existing terminal codec; provisional composition is not sent on finish, hide, or close and cannot replace already-sent terminal text. A single terminal backspace still works, while an editor-style bulk edit with no local context is refused. SSH setup fields, physical keyboard, Ctrl/Alt one-shot, terminal focus and SSH policy remain outside the change.

The exact upstream Termlib 0.3.15 Kotlin/resources are rebuilt locally with one marked IME source patch; the original arm64 JNI engine is extracted from the SHA-256-verified upstream AAR. No runtime code replacement or Android system setting change. Android's hints do not control arbitrary third-party keyboards.

Handset verification is still needed: in a disposable terminal, confirm Gboard shows its ordinary text layout; type ASCII and accents plus one multistage/non-Latin composition, verify expected single text delivery, Backspace and Enter, and dismiss while a composition is unfinished to verify it is not sent. Check Ctrl/Alt, slash, Esc/Tab, arrows and a physical keyboard. If a keyboard never explicitly commits a composition, unfinished preview text is intentionally discarded rather than risking unexpected remote input.

The dock's white outlined D-pad icon opens or closes a non-focusable cross popup, with four directions and no additional command. Keeping it open allows repeated presses and uses the same key path as before. Armed Ctrl/Alt have a thin white outline and retain accessible state. Verify trigger close, outside tap/system Back dismissal, keyboard state, and repeated arrows on the folded display.

## Herdr Pocket 0.24
A single touch or exclusive primary mouse press/release on a stable, drawn link opens a verified HTTP(S) address in Android's general web handler. Only a valid link takes priority over negotiated remote mouse input. Changed rows, clipped bounds, changed PTY or active selection, canceled gestures, scroll, pinch and buttons/modifiers outside the allowed route cannot open a link. If the URL changes between touch down and up, the click is not sent to the remote PTY as a fallback. The hit test requires the actual last drawn state both times; unrelated repaints are fine if the tapped row and exact destination/span are unchanged.

An OSC 8 link shows a confirmation with the full validated URL and rechecks the live terminal before opening. Plain link detection never joins hard rows; it only follows explicitly marked soft wraps in visible output or retained history. The tagged Herdr v0.9.5 direct renderer forwards OSC 8 spans and can send lines by independently addressing each row, so a wrap without native soft-wrap metadata, Unicode IRI, credential-bearing URL, unknown scheme, or clipped terminal-edge URL remains ordinary text. The browser is resolved by locally probing general web handlers, not by implicitly handing an actual URL to an arbitrary app-link handler. A device with no unique/default browser asks the user to set one. There are no link previews, URL logging, or background fetches.

JVM cases test malformed schemes/authorities, straight URLs and balanced punctuation, uppercase scheme, localhost/IPv6/query parameters, unsupported Unicode or control code points, clipped and hard rows, actual soft wraps, and the drawn-link identity rule. Loopback covers transport of a plain URL inside a synthetic Herdr direct-attach redraw. It does not exercise the Android click or browser chooser, real device and TalkBack still need verification. Try both a visible link and an OSC 8 label, a bad address, a link changing mid-tap, and an ordinary remote-mouse click, swipe or pinch. No real user server or URL visited during verification.

## Herdr Pocket 0.25
Ctrl and Alt are two independent toggles with a 2 dp white outline and accessible armed/checked state. Each can be tapped again to turn it off; both may be held for the same next key. Dock keys, finalized Android IME commits, and recognized hardware keys use the pinned native encoder. A bounded atomic IME commit applies the modifiers to only its first logical key (including correct CRLF and Unicode scalar handling); the rest is ordinary text in the same packet. Unusually large commits above 16K UTF-16 units while armed are rejected intact and retain the latch; ordinary unmodified commits keep their original handling.

Only a nonempty encoded packet accepted by the exact active SSH PTY clears its reserved bits. Independent decisions made after a key is queued are not erased by its delayed callback. Android preedit, local-only dead keys, selection, arming/disarming another modifier, link/popup/keyboard controls, dictation and protocol output do not clear the one-shot state. Renderer or PTY replacement clears it; a stale completion is never sent to a different PTY.

JVM tests probe virtual key mapping, combined/native masks, next-key release, rejection and stale acceptance, multi-character/CRLF/Unicode IME commits and old acknowledgements. Host-side libvterm tests use the exact pinned upstream source; the shipped JNI engine is compared with the previous release. A physical Fold/Gboard/IME focus check remains necessary. On a disposable terminal, arm Ctrl then Alt, type lowercase C with an IME or hardware keyboard, and verify the next ordinary key is unmodified; repeat with each toggle disarmed separately and with the dock's Up key.

## Herdr Pocket 0.26

Agent Files opens the selected TUI agent’s folder read-only over a separate SFTP channel on the existing pinned SSH transport. Verified 108 app and 5 Termlib unit tests, 82 focused JVM tests, and the loopback SSH/SFTP fixture. The fixture checks two unrelated roots, PTY input/output during an SFTP request, literal glob characters, and rejection of a symlink out of the selected root. Release lint: zero errors and 17 warnings. Verified ARM64 version 26, same release signing certificate, and 16 KiB APK alignment. Physical Fold and user Herdr host not tested.

## Herdr Pocket 0.27

The agent overflow button opens an opaque full-screen Agent Menu, preserving the live terminal underneath. The Files path header stays above the scrolling list with an accessible up arrow for subfolders. Android Back at the Files root returns to the menu; Close returns directly to the terminal. Verified 109 app tests, 5 Termlib tests, 83 focused JVM tests, loopback SSH/SFTP, release lint and assemble, the existing app signing certificate, and 16 KiB APK alignment. Physical Fold layout was not tested.

## Herdr Pocket 0.28
The Files top-left arrow and Android Back use the same existing one-layer navigation. The Agent Menu uses a matching arrow to return to the same terminal, and terminal Back opens the agents list. The pinned folder up arrow stays next to the path inside subfolders.

Handset smoke test: in the active terminal, scroll back, then open the overflow menu. Check that only the centered agent name appears in the top bar and Files sits below it. Try a long agent name at normal and maximum font size with the phone folded and unfolded; the heading may wrap to two lines but should never overlap the Back arrow. Open a subfolder and text preview. Tap the left arrow or use Android Back to walk back to the folder, parent, menu, terminal and finally agents list. Ensure alternating between the two Back controls does not skip a layer and that terminal scrollback is still present when returning to the agent.

## Herdr Pocket 0.29
Files uses one centered heading: file name for a preview, absolute path for a directory, and “Files” while no path is known. The directory heading is single-line with an end ellipsis; the complete path remains the screen-reader text. The right-hand Up arrow appears only in a browsable subfolder, while the left arrow retains the same hierarchical Back action as Android Back.

Handset smoke test: confirm short and long paths appear in the same top bar as Back and Up, the long path retains the initial slash and leading components, TalkBack reads the complete path, the root and a file preview have no Up arrow, and a preview shows its filename only in the header. Repeat with maximum font size and the outer and inner screen.

## Herdr Pocket 0.30
The terminal pinch range is 6–40sp. The initial and attached rosters group recognized Agents first and all remaining live Terminals second; shell and agent entries are joined by stable terminal ID. Pressing + on the attached roster creates the backend’s normal plain shell and stages attachment to that exact terminal, with no runner picker or automatic command typed into the shell. A failed creation or attach leaves the previous terminal open. The D-pad’s center key sends Enter through the dock’s exact-terminal modifier path.

Handset smoke test: with one agent and one plain shell, check both group headings, tap the selected shell, return to the roster and use +. Confirm a plain prompt opens, then run an agent there manually, refresh the roster, and check the same pane moves to Agents. Verify the previous terminal remains available. Pinch down to 6sp and verify the D-pad center Enter with and without armed modifiers. Repeat on Core where available.

## Herdr Pocket 0.31
The connected roster has a bottom Agents/Spaces selector. Agent priority follows the Herdr TUI status order (blocked, done, working, idle, unknown) and newest reported state change, with stable ties. Pocket uses the public API’s seen state, which can differ from another client’s Done badge. Spaces follows Go to’s workspace, tab, and split-pane order; it keeps one row per terminal and groups rows without workspace identities under Unassigned. The D-pad navigation keys retain their Home, End, Page Up, and Page Down actions and accessible labels, but use icons.

Handset smoke test: move between several workspaces, including a split tab, and compare the Spaces row order with Ctrl+B, G. Check that duplicate workspace names don’t merge, selecting the same pane returns to its live terminal, and tab switching scrolls independently. Check the selector above system navigation on both Fold displays and all four D-pad icons with TalkBack.

## Herdr Pocket 0.32
Agents keeps Herdr’s priority order and shows the pane’s state, runner, and workspace/tab context. Spaces uses the Go to picker’s workspace order and separate display labels for single or split panes, without renaming or redirecting terminals. The native Space search filters by visible location/name/path and supports All, Blocked, Working, Idle, and Done. Results preserve workspace and pane order; clearing filters restores all rows, including plain terminals. A status filter suppresses plain terminal rows, as in Go to.

Handset smoke test: create a named tab with split panes, a numeric tab, and a shell with a different live path. Compare Spaces labels and order with the server’s Ctrl+B, G picker. Search the tab, path, and a workspace. Set and clear a status filter and confirm plain terminals return. Switch Agents/Spaces with the keyboard shown, confirm the bottom selector clears the keyboard on returning to Agents, and open a result to confirm it attaches to the exact pane.

## Herdr Pocket 0.33
The Spaces search is a rounded pill in the toolbar between the side controls. Clearing, search results, the status filter, and independent view scroll positions behave as before. Files resolves the exact selected TUI terminal in the fresh pane list, so it works when the terminal has no recognized agent. Stable Herdr 0.9.0+ uses its verified live foreground directory; legacy and missing live metadata use a labeled pane/workspace fallback. It never guesses a home directory or sends a command through the visible terminal to find its path.

Handset smoke test: attach a plain shell through Spaces, use `cd` to enter another folder, open Files, and check that it shows that folder without stopping the shell. Reopen after another `cd` and repeat. Check the rounded Spaces search with short and long queries, status filtering, clear, and Android font scaling on both Fold displays.

## Herdr Pocket 0.34
The Spaces search, connection gear, status filter, and new-terminal button share one 48dp minimum, gray fill, white ink, and rounded white outline. The outline thickens for focused search or an active status filter. Search remains keyboard-editable and can grow vertically with accessibility font settings.

Handset smoke test: compare outlines and heights on both Fold displays with no query, then enter text, use Clear and IME Search, and set and clear a status filter. Repeat with large font size and confirm the toolbar controls stay separate and the roster scroll clearance tracks the toolbar.

## Herdr Pocket 0.38
The text preview has its own top-right options menu. Wrap is on and source line numbering off initially; long lines can scroll sideways with Wrap off, and the gutter does not number visual continuations or join selectable text. Download uses the Android save picker, then streams original SFTP bytes through an app-private temporary file rather than exporting the preview. Write and commit are scoped to the exact current terminal, file, and picker request.

Handset smoke test: view a long line and a CRLF text file, use the two independent options, and check horizontal scrolling with line numbers. Download a text file longer than the preview to local Downloads and compare its SHA-256 against the remote original. Try canceling the picker, returning to the folder during transfer, and changing connection mid-transfer. Verify the app does not save a different file or write to a stale request. Android document-provider integration needs on-device verification separately from the synthetic SSH/SFTP fixture.

## Herdr Pocket 0.40
The roster’s search and status filter are shared between Agents and Spaces. Results still preserve Agents priority and Spaces workspace order; the unfiltered Spaces list includes plain terminals.
The file viewer highlights YAML, JSON, logs, Python, C++, JavaScript, TypeScript, Rust, and Bash locally in supported previews. The bounded lexer colors familiar lexical tokens without requiring a language server, executing the file, or requesting more remote data. Unknown formats remain plain. Colors do not modify the displayed text, line numbering, or original-byte downloads. Readable regular files that cannot be rendered as UTF-8 now offer original-byte Download without text options; failures that cannot establish a readable or in-root file do not offer it.

Handset smoke test: open a representative file for each language and a mixed-severity log. Check text and contrast with Wrap and line numbers in all combinations, scroll a long line sideways, and copy or download a file to verify the underlying bytes did not change. Quickly switch between a large script and an unrecognized text file; stale colors must not appear in the new preview. Try downloading a binary from its unsupported preview and confirm the filename and SHA-256 of the saved file, then cancel Android’s picker once and confirm that the screen still offers Download. These handset and accessibility checks are separate from local unit and release validation.

## Herdr Pocket 0.42
Pocket locally acknowledges a shown Done completion after a successful roster attach or an explicit return to the same live terminal. It preserves other statuses and any future completion, without sending server-side focus commands or marking sibling panes. The completion must have a positive exact identity in Herdr's roster; older responses without one retain their server-reported badge. Local acknowledgment is scoped to the same SSH attempt and named TUI session.

Handset smoke test: select a Done agent, view the terminal, and return to Agents; expect its checkmark to clear. Keep another completed pane in the same tab and check that its badge stays. Run another turn, leave the terminal shown by switching to a different one, and confirm a new completion appears; failed or canceled attach should keep the old checkmark.

## Herdr Pocket 0.44
Both roster views show the status reported by Herdr. Pocket no longer marks a completion seen locally when the terminal is opened and does not issue server-side focus or acknowledgment commands. Reopening or pulling to refresh requests a new authoritative snapshot; there is no background polling.
