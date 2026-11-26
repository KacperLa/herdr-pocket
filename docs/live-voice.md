# Live Voice (beta)

In Pocket, open **Settings → Live Voice preview** and choose a
trusted service:

- **GPT Live compatible:** enter your proxy's HTTPS/WSS URL and client API key.
  Pocket speaks the GPT Live primary WebSocket protocol, not the Realtime
  protocol. Leave an OpenAI project key on the proxy, not in Pocket.
- **FOSS-Live:** enter its HTTPS/WSS URL and project bearer key. Pocket uses
  `local-live` and lets the server choose the voice. A base URL gets
  `/v1/live/sessions` appended; a complete custom path is used as supplied.
- **One-time relay ticket:** use the [optional relay](../tools/live-relay)
  and paste a fresh ticket at call start.

**Codex:** No extraction model, new daemon, or network port is required.
Pocket opens a short-lived read-only Codex App Server process over the existing
SSH connection. The SSH account must be able to find a Codex version that
supports `thread/turns/list` and see the same persisted Codex history as the
selected tab. If Herdr does not already report the native Codex session ID,
run `herdr integration install codex` on the host and relaunch Codex inside
Herdr. A brand-new session may need its first prompt in the terminal to create
history. Pocket does not install software or change host config.

**Claude Code and OpenCode:** structured readback also uses the session ID
reported by the matching Herdr integration (`herdr integration install claude`
or `herdr integration install opencode`). Restart that agent after installing
its integration. The same SSH account needs Python 3 and the agent's local
history: Claude Code reads its exact local project transcript; OpenCode uses
its session JSON export. The readers do not send history to a chat model.
Unrecognized history formats, ambiguous turns, and remote-only history stop
readback instead of silently switching to screen extraction.

**nobs:** The Herdr host must expose the live `custom:nobs` session ID and
support `agent prompt --expect-session`, which Pocket requires for every nobs
prompt. On the host, change into the nobs workspace with `cd`, then launch nobs
without a different workspace argument. If the session has never saved a
turn, send one test prompt in its terminal before starting Live Voice.
Readback uses Python 3's read-only SQLite connection to exactly one standard
store: `~/.local/state/nobs/nobs.db` or `<workspace>/.nobs/nobs.db`. Pocket does
not use custom database paths or choose between duplicate matching stores.
The selected ID, workspace, and database identity remain pinned for the whole call.

Herdr remains the source of agent state. After you approve a prompt, Pocket
tracks a Herdr work cycle and reads only the newly completed agent turn that
matches that prompt. The terminal remains available for live input requests
and approvals; ordinary follow-up questions in completed replies can be read
aloud. By default, Pocket shows editable drafts for the request and stored reply.
Turn off **Review agent requests and replies** and save to opt in to sending a
fresh voice request and reading a verified stored reply without dialogs, including
when the screen is locked. Pocket still checks the exact agent, session, state
and terminal menu; agent questions, terminal approvals, uncertain caller input
and screen-based reply drafts are never automatically approved. If Pocket cannot
verify a stored reply, it does not fall back to screen extraction.

Stored replies are not silently cropped or summarized. FOSS-Live receives up to
2,000 Unicode scalars and 8,000 UTF-8 bytes per append, with 16,000 UTF-8 bytes
per delegation counting separators. Pocket sends each part in order after the
previous part is acknowledged and stops if the route changes or speech is
interrupted. The service may reject a full speech queue or remove Markdown
formatting and fenced code before synthesis. GPT-Live accepts one append up to
its own 500-token limit and may paraphrase. A reply outside the transport limit
can be shortened manually; an unknown acknowledgement does not cause a retry.
The prompt limit is separate: 4,000 UTF-16 units of single-line text.

**Other agents:** To use the previous screen-based method, configure the full
HTTPS **Extraction URL**, a chat model that supports structured JSON, and its
bearer key if required. These are separate from the Live URL and key. Stored
keys are encrypted with Android Keystore and bound to their configured URL.
Use **Test saved extraction** to check the settings with synthetic text.
For compatible llama.cpp servers, **Disable thinking for extraction** affects
Pocket’s requests only. The extractor receives visible terminal snapshots;
**Ignore bottom lines** removes the selected number of rows for that pane.
Pocket still checks uncropped text locally for approvals and changed screens.

Open a recognized Herdr TUI agent and tap the waveform. Pocket will not
automatically answer, approve, or retry uncertain agent input. Leaving the pane
ends voice, but does not cancel agent work already submitted.

After a failed test or call, open **Live Voice preview → View diagnostics →
Copy diagnostics**. The recent log exists only in memory and contains
allowlisted event names and counts, not URLs, keys, terminal text, transcripts
or raw service responses.
