# Herdr Pocket Live Voice preview

One visible, already-running Herdr TUI agent per call; one delegated request at a time. This is **GPT-Live**, not the Realtime API. Existing dictation does not use this feature.

## Run a private relay

Use a trusted machine; it does **not** need to run Herdr or have SSH access to your agent. You need Python 3.11+, a project with GPT-Live access, an OpenAI project key kept on that machine, and a TLS endpoint Pocket can reach. The example relay limits sessions to ten minutes and one concurrent call.

1. Set `OPENAI_API_KEY` and `POCKET_LIVE_OPERATOR_SECRET` in a private environment on the relay machine. Generate a random secret of at least 32 characters, such as via `python -c 'import secrets; print(secrets.token_urlsafe(32))'`. Do not put either secret into the app.
2. From this directory, create and activate a Python virtual environment, then run `pip install -r requirements.txt` and `python relay.py serve`. The relay listens only on `127.0.0.1:8765`.
3. Publish **only** `/live` over HTTPS with a trusted certificate. Restrict access to your private network if possible. Do not proxy the ticket-mint route or log Authorization headers or audio frames. Example Caddy configuration:

   ```caddy
   voice.example.com {
       handle /live {
           reverse_proxy 127.0.0.1:8765
       }
       handle {
           respond 404
       }
   }
   ```

4. In Pocket, choose **One-time relay ticket**, save `wss://voice.example.com/live` under **Settings → Live Voice preview**, and configure the extraction endpoint and model described in the [app README](../../README.md#live-voice-preview-experimental-branch). Open the target agent and tap the waveform icon beside dictation. At the relay machine, with the same operator secret in the environment, run `python relay.py mint`. Paste the one-time ticket into Pocket within two minutes. The relay consumes it once; Pocket does not persist it. Each call needs a new ticket.

A live call sends microphone audio through the relay to OpenAI and may incur API charges. Hang up with the waveform icon. When the app loses the selected terminal or goes into the background, it shuts the call down. Closing voice does not stop agent work that was already sent.

## Test one turn

Start with a quiet, disposable agent. Ask, “Have the agent answer with the word pineapple, without changing anything.” Review the transcribed prompt and tap **Send to agent**. Once Herdr reports a new work cycle ending and the visible output stays unchanged for at least two seconds, Pocket offers the fresh result for review. If the redraw makes a fresh answer hard to isolate, Pocket offers no prefilled result. Review or edit the short excerpt and tap **Share with GPT-Live** to hear it. For a blocked agent, address the question in the terminal; Pocket never sends approval or menu input.

Pocket adapts [Orchrd's Herdr capture and input](https://github.com/KacperLa/orchrd/blob/main/src/herdr.rs) and [work-cycle completion](https://github.com/KacperLa/orchrd/blob/main/src/completion.rs): match the exact Herdr agent instance, read the bottom visible lines, recheck state around captures and immediately before submitting `agent prompt`, require a structured receipt without retries, and wait for new completed work before considering output. Unlike Orchrd, Pocket does not run a separate intent model or infer question answers. The user reviews the prompt and the short grounded excerpt rather than forwarding the whole terminal. Herdr does not offer an atomic expected-screen write; avoid simultaneously operating the agent from a different device while sending a voice prompt.

[GPT-Live WebSocket protocol](https://developers.openai.com/api/reference/resources/live/primary-websocket) · [GPT-Live client delegation](https://developers.openai.com/api/docs/guides/live-delegation?delegation-mode=client)
