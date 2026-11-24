# Reproduce SSH transport validation

From this project directory, run:

```sh
bash tests/ssh/run.sh
```

Requirements: Bash, JDK 17+, Python 3.10+ with venv, curl, OpenSSH's
`ssh-keygen`, network access to Maven Central and PyPI for test dependencies,
and permission to bind a local TCP port. No Android SDK is needed for this
transport test. For the Android build, use the instructions in README.md.

The script compiles the transport, Core list parser, the JUnit tests, and
loopback harness with a pinned Kotlin compiler. It generates short-lived
Ed25519 fixture keys and a random fixture passphrase in a private temporary
directory and removes these at exit. The fixture binds only to
`127.0.0.1` on an ephemeral port. The server simulates the CLI responses and
checks commands; it never shells out to Herdr or executes the supplied strings.
No existing keys, SSH config, agent, devices, or Herdr servers are used.

Assertions:

- Dictation sanitization preserves ordinary text and strips terminal control
  input. This JVM suite does not run Android speech recognition.

- OpenSSH SHA-256 fingerprint formatting, absent or wrong pin rejection, and
  acceptance only for an exact pin.
- No authentication request reaches the server in missing- or wrong-pin
  attempts. Successful connections use the generated encrypted OpenSSH
  private key, with no password authentication.
- The SSH exec request invokes the POSIX resolver with an exact `ls --json`
  suffix and no PTY. The simulated output parses as one terminal;
  stderr is separate and capped at 8 KiB, and exit status is zero. An
  oversized stdout is aborted on the SSH reader before exceeding 1 MiB in
  callbacks. A stalled non-PTY command closes after 30 seconds. The timeout
  assertion adds about 30 seconds to the test run.
- Selecting that terminal constructs a resolver command with the exact
  `attach -- 'box/t3'` suffix. Each of two explicit connections requests
  the attach once with `xterm-256color`, and
  verifies terminal input, output, resize, and exit status. A duplicate request
  during a stalled handshake is rejected; cancellation closes its socket and
  makes the old event generation stale. A forced socket drop emits TransportLost,
  while an SSH channel-only close without exit status emits ordinary Closed.
- On one authenticated SSH transport the fixture lists agents while the old
  PTY still accepts input, rejects a candidate and keeps the old terminal,
  then cancels a pending list without closing that terminal. A successful
  switch proves new output follows the switch event, input reaches the new
  PTY, the resize packet reaches only the new channel, and the old channel
  closes. An expired or unrelated terminal identity cannot send input to the
  replacement PTY. The replacement PTY sends alternate-screen and mouse capture
  sequences like a fullscreen TUI, then requires exact SGR wheel-up and
  wheel-down reports with cell coordinates. After capture is disabled and
  the normal screen restored, the mouse encoder emits no wheel bytes.
  After an abrupt drop the harness explicitly
  reattaches to the last committed agent in a new generation. Replacement
  readiness requires SSH PTY and exec acceptance plus a 200 ms open grace;
  it does not require terminal output from a quiet agent.
- Core list parser unit tests cover the expected layout, empty/error cases, and
  quoting a shell-sensitive address. The loopback fixture verifies the
  `/bin/sh -c` argument structure and final invocation without evaluating
  any command strings.

The harness forces the base JSch classes and bundled Bouncy Castle
implementations rather than the JVM's multi-release crypto classes. This
exercises the intended Android crypto path on the JVM, not ART or an Android
device. It is not a live Herdr compatibility test or an Android renderer,
keyboard, fold/resume, host-network, or packaging test.

For an offline Python dependency cache or an existing environment with
Paramiko 5.1.0 installed, set `HERDR_FIXTURE_PYTHON` to that interpreter's
absolute path. The script still downloads the JVM jars from Maven Central.
