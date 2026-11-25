#!/usr/bin/env bash
set -euo pipefail

# No real hosts, working-directory credentials, SSH config, or agent are used.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="$(mktemp -d)"
FIXTURE_PID=""
cleanup() {
    if [[ -n "$FIXTURE_PID" ]]; then kill "$FIXTURE_PID" 2>/dev/null || true; fi
    rm -rf -- "$WORK"
}
trap cleanup EXIT
umask 077
cd "$WORK"
mkdir jars
fetch() {
    curl --fail --silent --show-error --location --retry 2 --max-time 90 \
        "https://repo.maven.apache.org/maven2/$1" -o "jars/${1##*/}"
}
deps=(
    org/jetbrains/kotlin/kotlin-compiler-embeddable/2.1.21/kotlin-compiler-embeddable-2.1.21.jar
    org/jetbrains/kotlin/kotlin-stdlib/2.1.21/kotlin-stdlib-2.1.21.jar
    org/jetbrains/kotlin/kotlin-script-runtime/2.1.21/kotlin-script-runtime-2.1.21.jar
    org/jetbrains/kotlin/kotlin-daemon-embeddable/2.1.21/kotlin-daemon-embeddable-2.1.21.jar
    org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.0/kotlinx-coroutines-core-jvm-1.8.0.jar
    org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar
    org/jetbrains/annotations/13.0/annotations-13.0.jar
    com/github/mwiede/jsch/2.28.8/jsch-2.28.8.jar
    org/bouncycastle/bcprov-jdk18on/1.87/bcprov-jdk18on-1.87.jar
    org/json/json/20250517/json-20250517.jar
    junit/junit/4.13.2/junit-4.13.2.jar
    org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar
)
pids=()
for dep in "${deps[@]}"; do fetch "$dep" & pids+=("$!"); done
for pid in "${pids[@]}"; do wait "$pid"; done

src="$ROOT/app/src/main/java/dev/kaspy/herdr"
test="$ROOT/app/src/test/java/dev/kaspy/herdr"
cp_compile="jars/kotlin-stdlib-2.1.21.jar:jars/jsch-2.28.8.jar:jars/json-20250517.jar:jars/junit-4.13.2.jar"
java -cp 'jars/*' org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect \
    -classpath "$cp_compile" -d classes \
    "$src/AgentBackend.kt" "$src/AgentSelection.kt" "$src/AgentFiles.kt" "$src/AgentSftp.kt" "$src/AgentRosterRefresh.kt" "$src/TuiMutations.kt" "$src/PhysicalMouseWheel.kt" "$src/TuiAttachFrame.kt" "$src/ReconnectTarget.kt" "$src/SshSession.kt" "$src/SshConfig.kt" "$src/CoreSessions.kt" "$src/HostTrust.kt" "$src/PassphraseCipher.kt" "$src/TerminalFont.kt" "$src/TerminalMouse.kt" "$src/TerminalTouchTap.kt" "$src/StartupReconnect.kt" \
    "$test/SshSessionTest.kt" "$test/AgentFilesTest.kt" "$test/CoreSessionsTest.kt" "$test/ReconnectTargetTest.kt" "$test/HostTrustTest.kt" "$test/PassphraseVaultTest.kt" "$test/TerminalFontTest.kt" "$test/TerminalMouseTest.kt" "$test/TerminalTouchTapTest.kt" "$test/StartupReconnectTest.kt" "$test/TuiAgentsTest.kt" "$test/AgentRosterRefreshTest.kt" "$test/TuiMutationsTest.kt" "$test/PhysicalMouseWheelTest.kt" \
    "$ROOT/tests/ssh/Harness.kt"

java -cp 'jars/*:classes' org.junit.runner.JUnitCore \
    dev.kaspy.herdr.AgentFilesTest dev.kaspy.herdr.SshSessionTest dev.kaspy.herdr.CoreSessionsTest dev.kaspy.herdr.ReconnectTargetTest dev.kaspy.herdr.HostTrustTest dev.kaspy.herdr.PassphraseVaultTest dev.kaspy.herdr.TerminalFontTest dev.kaspy.herdr.TerminalMouseTest dev.kaspy.herdr.TerminalTouchTapTest dev.kaspy.herdr.StartupReconnectTest dev.kaspy.herdr.TuiAgentsTest dev.kaspy.herdr.AgentRosterRefreshTest dev.kaspy.herdr.TuiMutationsTest dev.kaspy.herdr.PhysicalMouseWheelTest

fixture_python="${HERDR_FIXTURE_PYTHON:-}"
if [[ -z "$fixture_python" ]]; then
    python3 -m venv fixture-venv
    fixture_python="$WORK/fixture-venv/bin/python"
    "$fixture_python" -m pip --disable-pip-version-check --no-cache-dir \
        --timeout 15 --retries 2 install --quiet 'paramiko==5.1.0'
fi
"$fixture_python" -c 'from importlib.metadata import version; assert version("paramiko") == "5.1.0"'
# Random fixture-only passphrase. The host key intentionally has no passphrase.
ssh-keygen -q -t ed25519 -N '' -f local-host
export HERDR_FIXTURE_KEY_PASSPHRASE
HERDR_FIXTURE_KEY_PASSPHRASE="$("$fixture_python" -c 'import secrets; print(secrets.token_urlsafe(32))')"
ssh-keygen -q -t ed25519 -a 4 -N "$HERDR_FIXTURE_KEY_PASSPHRASE" -f local-client

"$fixture_python" "$ROOT/tests/ssh/fixture.py" &
FIXTURE_PID=$!
for ((i=0;i<100;i++)); do [[ -s fixture-info ]] && break; sleep 0.1; done
[[ -s fixture-info ]]
# Force JSch base Java 8 classes to exercise its Android-like BC crypto path.
java -Djdk.util.jar.enableMultiRelease=false -cp 'jars/*:classes' dev.kaspy.herdr.HarnessKt
wait "$FIXTURE_PID"
FIXTURE_PID=""
printf 'Loopback server request assertions passed.\n'
