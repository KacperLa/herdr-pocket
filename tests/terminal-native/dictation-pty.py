"""Default-mode libvterm text+Enter should submit exactly one line to a disposable PTY."""
import errno
import os
import pty
import select
import subprocess
import sys
import termios
import time

packet = subprocess.check_output([sys.argv[1], '--dictation-line'])
text_only = subprocess.check_output([sys.argv[1], '--dictation-text'])
assert text_only == 'café 😀'.encode()
assert packet == text_only + b'\r'
master, slave = pty.openpty()
proc = None
try:
    mode = termios.tcgetattr(slave)
    mode[0] |= termios.ICRNL
    mode[3] = (mode[3] | termios.ICANON) & ~termios.ECHO
    termios.tcsetattr(slave, termios.TCSANOW, mode)
    proc = subprocess.Popen(
        ['/bin/sh', '-c', 'IFS= read -r line && printf "SUBMITTED:<%s>\\n" "$line"'],
        stdin=slave, stdout=slave, stderr=slave, close_fds=True,
    )
    os.close(slave)
    slave = None
    assert os.write(master, text_only) == len(text_only)
    # With the switch off, the shell gets a pending line, not a submission.
    assert not select.select([master], [], [], .2)[0]
    assert proc.poll() is None
    enter = packet[len(text_only):]
    assert os.write(master, enter) == len(enter)
    content = bytearray()
    deadline = time.monotonic() + 3
    while time.monotonic() < deadline:
        if not select.select([master], [], [], .1)[0]:
            continue
        try:
            chunk = os.read(master, 4096)
        except OSError as err:
            if err.errno != errno.EIO:
                raise
            break
        if not chunk:
            break
        content.extend(chunk)
    assert proc.wait(timeout=3) == 0
    assert content.replace(b'\r', b'').splitlines() == ['SUBMITTED:<café 😀>'.encode()], content
    print('Interactive PTY received text and submitted exactly one complete Unicode line.')
finally:
    if proc is not None and proc.poll() is None:
        proc.kill()
        proc.wait()
    if slave is not None:
        os.close(slave)
    os.close(master)
