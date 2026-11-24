#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
commit=7a50d641ec92ef164645af1f4e39d0bf1a86b480
if [[ -n "${1:-}" ]]; then
    cp "$1" "$work/source.tar.gz"
else
    curl --fail --silent --show-error --location --max-time 45 \
        "https://codeload.github.com/connectbot/termlib/tar.gz/$commit" \
        --output "$work/source.tar.gz"
fi
printf '%s  %s\n' 05e396d6760e2fc467ea1d8e4ab746567197be85d260f9a844f13f8c0db55d33 "$work/source.tar.gz" | sha256sum --check
tar -xzf "$work/source.tar.gz" -C "$work" --wildcards '*/lib/src/main/cpp/libvterm/*'
cd "$work/termlib-$commit/lib/src/main/cpp/libvterm"
mkdir -p t/.libs
"${CC:-cc}" -O2 -std=c99 -Iinclude src/*.c t/harness.c -o t/.libs/harness
perl t/run-test.pl t/25state_input.test
"${CC:-cc}" -O2 -std=c99 -Iinclude src/*.c "$here/dock-bytes.c" -o "$work/dock-bytes"
"$work/dock-bytes"
python3 "$here/dictation-pty.py" "$work/dock-bytes"
