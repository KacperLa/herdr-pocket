import base64, hashlib, json, os, pathlib, shlex, socket, struct, threading, time
import paramiko
host = paramiko.Ed25519Key.from_private_key_file('local-host')
client = paramiko.Ed25519Key.from_private_key_file('local-client', password=os.environ['HERDR_FIXTURE_KEY_PASSPHRASE'])
pin = 'SHA256:' + base64.b64encode(hashlib.sha256(host.asbytes()).digest()).decode().rstrip('=')
LIST_SUFFIX = 'exec "$hc_bin" ls --json'
SECOND_SUFFIX = """exec "$hc_bin" attach -- 'box/t4'"""
FAIL_SUFFIX = """exec "$hc_bin" attach -- 'box/bad'"""
TUI_LIST_SUFFIX = "herdr_tui_roster"
TUI_BUSY_SUFFIX = """exec "$herdr_bin" --session 'office' terminal attach 'term_busy'"""
TUI_FAST_SUFFIX = """exec "$herdr_bin" --session 'office' terminal attach 'term_fast'"""
TUI_GET_EXISTING = """exec "$herdr_bin" --session 'office' pane get 'w1:p1'"""
TUI_RENAME = """exec "$herdr_bin" --session 'office' pane rename 'w1:p1' 'Jetson AI 2'"""
TUI_CREATE_TAB = """exec "$herdr_bin" --session 'office' tab create --workspace 'w1' --no-focus"""
TUI_GET_NEW = """exec "$herdr_bin" --session 'office' pane get 'w1:p2'"""
CORE_CREATE_SHELL = """exec "$hc_bin" run --bg --json --no-parent --name 'shell-fixture'"""
ATTACH_SUFFIX = """exec "$hc_bin" attach -- 'box/t3'"""

def assert_resolved_command(events, suffix):
    commands = [entry[1] for entry in events if entry[0] == 'exec']
    assert len(commands) == 1, commands
    args = shlex.split(commands[0], posix=True)
    assert len(args) == 3 and args[:2] == ['/bin/sh', '-c'], args
    script_lines = args[2].rstrip().splitlines()
    assert len(script_lines)>1
    if suffix==TUI_LIST_SUFFIX:
        calls=[line for line in script_lines if '"$herdr_bin" --session' in line]
        assert len(calls)==5, calls
        for line, cli in zip(calls, ('api snapshot','agent list','pane list','tab list','workspace list')):
            assert ('"$herdr_bin" --session ' + "'office' " + cli) in " ".join(line.split()), calls
    else:
        assert script_lines[-1]==suffix, script_lines

class Server(paramiko.ServerInterface):
    def __init__(self):
        self.events = []
        self.exec = threading.Event()
        self.channel_commands = {}
        self.channel_events = {}
        self.resize = threading.Event()
        self.channel_resizes = {}
    def get_allowed_auths(self, username): return 'publickey'
    def check_auth_none(self, username):
        self.events.append(['none', username])
        return paramiko.AUTH_FAILED
    def check_auth_password(self, username, password):
        self.events.append(['password', username])
        return paramiko.AUTH_FAILED
    def check_auth_publickey(self, username, key):
        self.events.append(['publickey', username])
        return paramiko.AUTH_SUCCESSFUL if key == client and username == 'fixture' else paramiko.AUTH_FAILED
    def check_channel_request(self, kind, chanid):
        return paramiko.OPEN_SUCCEEDED if kind == 'session' else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED
    def check_channel_pty_request(self, channel, term, width, height, width_px, height_px, modes):
        self.events.append(['pty', term.decode(), width, height])
        return True
    def check_channel_exec_request(self, channel, command):
        request = ['exec', command.decode()]
        self.events.append(request)
        self.channel_commands[channel.chanid] = request
        self.channel_events.setdefault(channel.chanid, threading.Event()).set()
        self.exec.set()
        return not shlex.split(request[1])[2].rstrip().endswith(FAIL_SUFFIX)
    def check_channel_window_change_request(self, channel, width, height, width_px, height_px):
        self.events.append(['size', width, height, width_px, height_px])
        self.channel_resizes.setdefault(channel.chanid, []).append((width, height, width_px, height_px))
        if width == 104: self.resize.set()
        return True
def expect_channel(t, s, suffix):
    channel = t.accept(7)
    assert channel is not None
    assert s.channel_events.setdefault(channel.chanid, threading.Event()).wait(7)
    assert_resolved_command([s.channel_commands[channel.chanid]], suffix)
    channel.settimeout(7)
    return channel

FILES_ROOT = pathlib.Path('virtual-files').resolve()
FILES_READY = threading.Event()
FILES_PTY = threading.Event()
DELETION_REQUESTS = []
MKDIR_REQUESTS = []

class SlowFixtureFile(paramiko.SFTPHandle):
    def read(self, offset, length):
        # Give the client enough time to cancel after bytes reach its stage.
        time.sleep(0.06)
        return super().read(offset, length)

class HardlinkFixtureServer(paramiko.SFTPServer):
    def _send_server_version(self):
        kind,data=self._read_packet()
        assert kind==1
        response=paramiko.Message()
        response.add_int(3)
        response.add("hardlink@openssh.com","1")
        self._send_packet(2,response)
        return struct.unpack(">I",data[:4])[0]
    def _process(self,t,number,msg):
        if t==200 and msg.get_remainder().startswith(b"\0\0\0\x14hardlink@openssh.com"):
            assert msg.get_text()=="hardlink@openssh.com"
            source=self.server.local(msg.get_text())
            destination=self.server.local(msg.get_text())
            assert source.parent.name.startswith(".herdr-pocket-upload-")
            try:
                if destination.name=="race.bin":
                    with destination.open("xb") as competitor:
                        competitor.write(b"competitor data")
                os.link(source,destination)
                self._send_status(number,paramiko.SFTP_OK)
            except OSError as ex:
                self._send_status(number,paramiko.SFTPServer.convert_errno(ex.errno))
        else:
            super()._process(t,number,msg)

class FixtureSftp(paramiko.SFTPServerInterface):
    def local(self, path):
        assert path.startswith('/') and '..' not in path.split('/'), path
        return FILES_ROOT / path.lstrip('/')
    def canonicalize(self, path):
        if path == '/alpha' and not FILES_READY.is_set():
            FILES_READY.set()
            assert FILES_PTY.wait(7), 'PTY input blocked while SFTP was active'
        return '/' + self.local(path).resolve().relative_to(FILES_ROOT).as_posix().removeprefix('./')
    def list_folder(self, path):
        try:
            result = []
            for p in self.local(path).iterdir():
                item = paramiko.SFTPAttributes.from_stat(p.lstat())
                item.filename = p.name
                result.append(item)
            return result
        except OSError as ex:
            return paramiko.SFTPServer.convert_errno(ex.errno)
    def stat(self, path):
        try:
            return paramiko.SFTPAttributes.from_stat(self.local(path).stat())
        except OSError as ex:
            return paramiko.SFTPServer.convert_errno(ex.errno)
    def lstat(self, path):
        try:
            return paramiko.SFTPAttributes.from_stat(self.local(path).lstat())
        except OSError as ex:
            return paramiko.SFTPServer.convert_errno(ex.errno)
    def open(self, path, flags, attr):
        if flags != os.O_RDONLY:
            local=self.local(path)
            assert local.parent.name.startswith(".herdr-pocket-upload-") and local.name.startswith("payload-")
            try:
                handle=paramiko.SFTPHandle(flags)
                handle.writefile=os.fdopen(os.open(local,flags,0o600),'wb')
                return handle
            except OSError as ex:
                return paramiko.SFTPServer.convert_errno(ex.errno)
        try:
            file = self.local(path).open('rb')
            handle = SlowFixtureFile(flags) if path.endswith("/slow.bin") else paramiko.SFTPHandle(flags)
            handle.readfile = file
            return handle
        except OSError as ex:
            return paramiko.SFTPServer.convert_errno(ex.errno)

    def mkdir(self,path,attr):
        target=self.local(path)
        if target.name.startswith(".herdr-pocket-upload-"):
            return self.change(target.mkdir)
        MKDIR_REQUESTS.append(target.name)
        assert target.name in ("new [1]*? \\ folder","mkdir-race"), path
        if target.name=="mkdir-race":
            with target.open("xb") as competitor:
                competitor.write(b"competitor data")
        return self.change(target.mkdir)
    def chattr(self,path,attr):
        assert self.local(path).name.startswith(".herdr-pocket-upload-") and attr.st_mode & 0o777==0o700
        return self.change(lambda: self.local(path).chmod(attr.st_mode))
    def rmdir(self,path):
        assert self.local(path).name.startswith(".herdr-pocket-upload-")
        return self.change(lambda: self.local(path).rmdir())
    def remove(self,path):
        target=self.local(path)
        if target.parent.name.startswith(".herdr-pocket-upload-"):
            return self.change(target.unlink)
        DELETION_REQUESTS.append(target.name)
        assert target.name in ("delete [1]*?.bin","ambiguous-delete.bin"), path
        result=self.change(target.unlink)
        if target.name=="ambiguous-delete.bin" and result==paramiko.SFTP_OK:
            return paramiko.SFTP_FAILURE
        return result
    def change(self,fn):
        try:
            fn()
            return paramiko.SFTP_OK
        except OSError as ex:
            return paramiko.SFTPServer.convert_errno(ex.errno)

def exercise_same_transport(t, s, connection):
    old = expect_channel(t, s, ATTACH_SUFFIX)
    old.sendall(b'old ready')
    sftp_channels = [t.accept(7)]
    assert sftp_channels[-1] is not None
    assert FILES_READY.wait(7)
    assert old.recv(100) == b'during sftp'
    old.sendall(b'old while browsing')
    FILES_PTY.set()
    for _ in range(3):
        sftp_channels.append(t.accept(7))
        assert sftp_channels[-1] is not None
    listing = expect_channel(t, s, LIST_SUFFIX)
    assert old.recv(100) == b'during list'
    old.sendall(b'old while listing')
    listing.sendall(b'{"terminals":[{"address":"box/t4","name":"second","state":"live"}],"hidden_finished":0}')
    listing.send_stderr(b'list diagnostic')
    listing.send_exit_status(0)
    listing.close()

    failed = expect_channel(t, s, FAIL_SUFFIX)
    assert old.recv(100) == b'after failure'
    old.sendall(b'old after failure')

    shell_created = expect_channel(t, s, CORE_CREATE_SHELL)
    shell_created.sendall(b't9\n')  # Run response format is deliberately not used.
    shell_created.send_exit_status(0)
    shell_created.close()
    fresh_core_roster = expect_channel(t, s, LIST_SUFFIX)
    fresh_core_roster.sendall(b'{"terminals":[{"address":"t9","name":"shell-fixture","state":"live","agent":"bash"}],"hidden_finished":0}')
    fresh_core_roster.send_exit_status(0)
    fresh_core_roster.close()

    tui_list = expect_channel(t, s, TUI_LIST_SUFFIX)
    tui_list.sendall(json.dumps({
        'id':'cli:api:snapshot',
        'result':{'type':'session_snapshot','snapshot':{
            'version':'0.9.5','protocol':22,
            'agents':[{'terminal_id':'term_busy','pane_id':'w1:p1','tab_id':'w1:t1','workspace_id':'w1','agent':'codex','agent_status':'idle'}],
            'panes':[{'terminal_id':'term_busy','pane_id':'w1:p1','tab_id':'w1:t1','workspace_id':'w1'},
                     {'terminal_id':'term_shell','pane_id':'w2:p1','tab_id':'w2:t1','workspace_id':'w2'}],
            'tabs':[{'tab_id':'w1:t1','workspace_id':'w1','label':'1'}],
            'workspaces':[{'workspace_id':'w1','label':'Jetson AI'}],
        }},
    }).encode())
    tui_list.send_exit_status(0)
    tui_list.close()
    busy = expect_channel(t, s, TUI_BUSY_SUFFIX)
    busy.sendall(b'\x1b[?1000l')  # stdout before Herdr finishes the server handshake
    time.sleep(0.4)  # longer than ordinary Core staging grace
    busy.sendall_stderr(b'herdr: server shut down: terminal attach failed: terminal term_busy already has an attached client; retry with --takeover')
    busy.send_exit_status(1)
    busy.close()
    assert old.recv(100) == b'after owner refusal'
    old.sendall(b'old after owner refusal')

    existing={'terminal_id':'term_busy','pane_id':'w1:p1','workspace_id':'w1','tab_id':'w1:t1'}
    fresh={'terminal_id':'term_fast','pane_id':'w1:p2','workspace_id':'w1','tab_id':'w1:t2'}
    for command,result in [
        (TUI_GET_EXISTING, {'type':'pane_info','pane':existing}),
        (TUI_RENAME, {'type':'pane_info','pane':dict(existing,label='Jetson AI 2')}),
        (TUI_CREATE_TAB, {'type':'tab_created','tab':{'workspace_id':'w1','tab_id':'w1:t2'},'root_pane':fresh}),
        (TUI_GET_NEW, {'type':'pane_info','pane':fresh}),
    ]:
        channel=expect_channel(t,s,command)
        channel.sendall(json.dumps({'result':result}).encode())
        channel.send_exit_status(0)
        channel.close()
    assert old.recv(100) == b'after roster mutations'
    old.sendall(b'old after roster mutations')

    canceled = expect_channel(t, s, LIST_SUFFIX)
    assert old.recv(100) == b'confirm pending'
    old.sendall(b'cancel ready')
    until = time.monotonic() + 7
    while not canceled.closed and time.monotonic() < until:
        time.sleep(0.02)
    assert canceled.closed and not old.closed
    old.sendall(b'old after cancel')

    new = expect_channel(t, s, SECOND_SUFFIX)
    new.sendall(b'new early')
    assert new.recv(100) == b'new input'
    resized = (73, 31, 712, 489)
    until_resize = time.monotonic() + 7
    while resized not in s.channel_resizes.get(new.chanid, []) and time.monotonic() < until_resize:
        time.sleep(0.02)
    assert resized in s.channel_resizes.get(new.chanid, [])
    assert resized not in s.channel_resizes.get(old.chanid, [])
    until = time.monotonic() + 7
    while not old.closed and time.monotonic() < until:
        time.sleep(0.02)
    assert old.closed
    new.sendall(b'new again')
    assert new.recv(100) == b'request mouse modes'
    new.sendall(b'\x1b[?1049h\x1b[!p\x1b[?1000h\x1b[?1002h\x1b[?1003h\x1b[?10')
    new.sendall(b'06hmouse enabled')
    expected = b'\x1b[<64;12;7M\x1b[<65;15;9M\x1b[<0;23;14M\x1b[<0;23;14m'
    received = b''
    while len(received) < len(expected):
        chunk = new.recv(len(expected) - len(received))
        assert chunk, 'replacement PTY closed before the wheel/click reports arrived'
        received += chunk
    assert received == expected, repr(received)
    new.sendall(b'\x1b[?1003l\x1b[?1002l\x1b[?1000l\x1b[?1006l\x1b[?1049lmouse disabled')
    tui = expect_channel(t, s, TUI_FAST_SUFFIX)
    tui.sendall(b'\x1b[?1000l\x1b[?1016$p\x1b[c\x1b[?1049h')
    tui.sendall(b'\x1b[?2026h\x1b[?25l\x1b]8;;\x1b')
    new.sendall(b'previous PTY still live')
    assert new.recv(100) == b'still live while opening'
    tui.sendall(b'\\\x1b[2J\x1b[1;1H\x1b[?25l\x1b[?20')
    time.sleep(0.08)  # end arrives separately even for a rendered screen with no text
    tui.sendall(b'26l')
    # Synthetic direct-attach redraw bytes: keep the visible URL complete and
    # explicitly terminated; never fetch it or substitute an actual user host.
    tui.sendall(b'\x1b[?2026h\x1b[2;1HVisit https://example.test/agent?q=1 now\x1b[?2026l')
    assert tui.recv(100) == b"cd '/shell-root/live project'\r"
    tui.sendall(b'synthetic shell changed directory')
    shell_roster = expect_channel(t, s, TUI_LIST_SUFFIX)
    shell_roster.sendall(json.dumps({'result':{'type':'session_snapshot','snapshot':{
        'version':'0.9.5',
        'agents':[dict(existing,cwd='/alpha',foreground_cwd='/alpha')],
        'panes':[dict(existing,cwd='/alpha',foreground_cwd='/alpha',focused=True),
                 dict(fresh,cwd='/shell-root',foreground_cwd='/shell-root/live project',focused=False)],
    }}}).encode())
    shell_roster.send_exit_status(0)
    shell_roster.close()
    shell_sftp = t.accept(7)
    assert shell_sftp is not None
    assert tui.recv(100) == b'still at shell'
    tui.sendall(b'shell active while browsing')
    # Retain each accepted channel until its subsystem request starts; Paramiko's
    # transport stores only weak references after accept() removes the queue entry.
    full_download = t.accept(10)
    assert full_download is not None
    rejected_download = t.accept(10)
    assert rejected_download is not None
    unsupported_preview = t.accept(10)
    assert unsupported_preview is not None
    missing_preview = t.accept(10)
    assert missing_preview is not None
    binary_download = t.accept(10)
    assert binary_download is not None
    canceled_download = t.accept(10)
    assert canceled_download is not None
    upload_channels=[t.accept(10) for _ in range(5)]
    assert all(upload_channels)
    dest=FILES_ROOT / 'shell-root' / 'live project'
    assert (dest/'payload [1]*?.bin').read_bytes()==bytes(range(256))*1024
    assert (dest/'race.bin').read_bytes()==b'competitor data'
    end=time.monotonic()+5
    while list(dest.glob('.herdr-pocket-upload-*')) and time.monotonic()<end:
        time.sleep(.02)
    assert not list(dest.glob('.herdr-pocket-upload-*'))
    assert not (dest/'cancel-upload.bin').exists()
    delete_channels=[t.accept(10) for _ in range(5)]
    assert all(delete_channels)
    mkdir_channels=[t.accept(10) for _ in range(5)]
    assert all(mkdir_channels)
    dock_keys = b''
    while len(dock_keys) < 4:
        chunk = tui.recv(4 - len(dock_keys))
        assert chunk, 'PTY closed before the next-key bytes arrived'
        dock_keys += chunk
    assert dock_keys == b'\x1b\x03xy', repr(dock_keys)
    assert MKDIR_REQUESTS==['new [1]*? \\ folder','mkdir-race']
    assert (dest/'keep-folder'/'new [1]*? \\ folder').is_dir()
    assert (dest/'mkdir-race').read_bytes()==b'competitor data'
    assert DELETION_REQUESTS==['delete [1]*?.bin','ambiguous-delete.bin']
    assert not (dest/'delete [1]*?.bin').exists() and not (dest/'ambiguous-delete.bin').exists()
    assert (dest/'delete [1]abcX.bin').read_bytes()==b'neighbor'
    assert (dest/'escape-link').is_symlink() and (dest/'keep-folder').is_dir()
    assert (FILES_ROOT/'beta'/'other.txt').read_text()=='other agent'
    tui.sendall(b'dock-key bytes received')
    assert tui.recv(100) == b'drop switched'
    connection.shutdown(socket.SHUT_RDWR)

with socket.socket(socket.AF_INET) as listener:
    listener.bind(('127.0.0.1',0))
    listener.listen()
    listener.settimeout(25)
    pathlib.Path('fixture-info').write_text(f'{listener.getsockname()[1]}\n{pin}\n')
    events = []
    for index in range(11):
        connection, _ = listener.accept()
        s = Server()
        t = paramiko.Transport(connection)
        t.add_server_key(host)
        if index == 9:
            (FILES_ROOT / 'alpha' / 'subfolder').mkdir(parents=True)
            (FILES_ROOT / 'beta').mkdir()
            (FILES_ROOT / 'shell-root' / 'live project').mkdir(parents=True)
            (FILES_ROOT / 'shell-root' / 'live project' / 'inside.txt').write_text('current shell folder', encoding='utf-8')
            shell_path = FILES_ROOT / 'shell-root' / 'live project'
            (shell_path / 'full-source.txt').write_bytes(('\ufeff' + 'UTF-8 ✓ line\r\n' * 12_000 + 'tail\r').encode())
            (shell_path / 'escape-link').symlink_to('../../beta/other.txt')
            (shell_path / 'capture.bin').write_bytes(bytes(range(256)) * 900)
            (shell_path / 'slow.bin').write_bytes(bytes(range(256)) * 16_384)
            (shell_path / 'delete [1]*?.bin').write_bytes(b'literal delete')
            (shell_path / 'delete [1]abcX.bin').write_bytes(b'neighbor')
            (shell_path / 'ambiguous-delete.bin').write_bytes(b'closed after unlink')
            (shell_path / 'keep-folder').mkdir()
            (FILES_ROOT / 'alpha' / 'a*?.txt').write_text('agent text ✓', encoding='utf-8')
            (FILES_ROOT / 'beta' / 'other.txt').write_text('other agent', encoding='utf-8')
            (FILES_ROOT / 'alpha' / 'other-link').symlink_to('../beta/other.txt')
            t.set_subsystem_handler('sftp', HardlinkFixtureServer, FixtureSftp)
        try:
            t.start_server(server=s)
            if index == 9:
                exercise_same_transport(t, s, connection)
                continue
            if index == 10:
                resumed = expect_channel(t, s, SECOND_SUFFIX)
                resumed.sendall(b'resumed last target')
                assert resumed.recv(100) == b'exit resumed'
                resumed.send_exit_status(0)
                resumed.close()
                continue
            ch = t.accept(6)
            if ch is not None:
                assert s.exec.wait(4)
                assert_resolved_command(s.events, LIST_SUFFIX if index in (2, 3, 4) else ATTACH_SUFFIX)
                if index in (2, 3, 4):
                    ch.send(b'{"terminals":[{"address":"box/t3","name":"fixture","state":"live","agent":"bash"}],"hidden_finished":0}')
                    if index == 3:
                        try:
                            ch.sendall(b'x' * (1024 * 1024 + 1))
                        except OSError:
                            pass  # client aborts the transfer when it hits its cap
                        continue
                    if index == 4:
                        timeout = time.monotonic() + 40
                        while t.is_active() and time.monotonic() < timeout:
                            time.sleep(0.05)
                        assert not t.is_active(), "client did not close the stalled command"
                        continue
                    ch.sendall_stderr(b'Stderr must not corrupt JSON' + b'e' * 10000)
                    ch.send_exit_status(0)
                    ch.close()
                    continue
                ch.send(b'hello\r\n')
                ch.settimeout(4)
                sent = ch.recv(50)
                s.events.append(['input', sent.decode()])
                if index == 7:
                    assert sent == b'drop now'
                    connection.shutdown(socket.SHUT_RDWR)  # abrupt loss, no SSH close or exit-status
                    continue
                if index == 8:
                    assert sent == b'close channel'
                    ch.close()  # no exit-status, but SSH transport remains up
                    time.sleep(0.5)
                    continue
                assert s.resize.wait(4)
                ch.send_stderr(b'error\r\n')
                ch.send_exit_status(23)
                ch.close()
        except (EOFError, paramiko.SSHException):
            pass
        finally:
            t.close()
            events.append(s.events)
    pathlib.Path('fixture-events').write_text(json.dumps(events))
    assert events[0] == [] and events[1] == []
    for e in events[2:5]:
        assert [entry for entry in e if entry[0] != 'exec'] == [
            ['none', 'fixture'], ['publickey', 'fixture'], ['publickey', 'fixture']
        ]
        assert_resolved_command(e, LIST_SUFFIX)
    for e in events[5:7]:
        assert not any(x[0] == 'password' for x in e)
        assert_resolved_command(e, ATTACH_SUFFIX)
        assert ['pty','xterm-256color',90,27] in e
        assert ['size',104,33,700,400] in e
        assert ['input','test keystrokes'] in e

    assert ['input', 'drop now'] in events[7]
    assert ['input', 'close channel'] in events[8]

    assert [entry for entry in events[9] if entry[0] in ('none', 'publickey', 'password')] == [
        ['none', 'fixture'], ['publickey', 'fixture'], ['publickey', 'fixture']
    ]
    commands = [entry for entry in events[9] if entry[0] == 'exec']
    wanted = [ATTACH_SUFFIX, LIST_SUFFIX, FAIL_SUFFIX, CORE_CREATE_SHELL, LIST_SUFFIX, TUI_LIST_SUFFIX, TUI_BUSY_SUFFIX, TUI_GET_EXISTING, TUI_RENAME, TUI_CREATE_TAB, TUI_GET_NEW,
              LIST_SUFFIX, SECOND_SUFFIX, TUI_FAST_SUFFIX, TUI_LIST_SUFFIX]
    assert len(commands) == len(wanted)
    for entry, suffix in zip(commands, wanted):
        assert_resolved_command([entry], suffix)
    assert len([entry for entry in events[9] if entry[0] == 'pty']) == 5
