package dev.kaspy.herdr

import org.json.JSONObject

/** Query only the current binding in the two standard local nobs stores; never start nobs. */
internal object LiveNobsHistory {
    private val sessionId = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")
    fun validSessionId(value: String): Boolean = sessionId.matches(value)
    fun validWorkspace(value: String): Boolean =
        value.startsWith("/") && value.length in 1..4096 && value.none { it.isISOControl() }

    private val databaseKey = Regex("[gw]:[0-9a-f]{64}")
    fun validDatabaseKey(value: String): Boolean = databaseKey.matches(value)

    fun command(binding: LiveStructuredHistory.Binding, expectedDatabase: String?): String {
        require(binding.provider == LiveStructuredHistory.Provider.NOBS && validSessionId(binding.id))
        val cwd = requireNotNull(binding.workspace).also { require(validWorkspace(it)) }
        require(expectedDatabase == null || validDatabaseKey(expectedDatabase))
        val args = listOf(binding.id,cwd,expectedDatabase.orEmpty()).joinToString(" ",transform=TuiAgents::quote)
        return "/bin/sh -c "+TuiAgents.quote("exec python3 - "+args+" <<'POCKET_NOBS_READ'\n"+
            reader+"\nPOCKET_NOBS_READ")
    }

    fun parse(binding:LiveStructuredHistory.Binding, root:JSONObject):LiveStructuredHistory.Page? {
        if(binding.provider!=LiveStructuredHistory.Provider.NOBS || root.opt("session")!=binding.id ||
            root.opt("workspace")!=binding.workspace) return null
        val source=(root.opt("database") as? String)?.takeIf(::validDatabaseKey) ?: return null
        val rows=root.getJSONArray("turns")
        if(rows.length()>64) return null
        val turns=(0 until rows.length()).map { i ->
            val row=rows.getJSONObject(i)
            val id=(row.opt("id") as? String)?.takeIf { it.matches(Regex("[0-9]{1,19}:[0-9a-f]{64}")) } ?: return null
            LiveStructuredHistory.Turn(id,row.opt("prompt") as? String,row.opt("final") as? String)
        }
        if(turns.map {it.id}.distinct().size!=turns.size) return null
        return LiveStructuredHistory.Page(turns,root.opt("full")==true,source)
    }

    private val reader="""
        import hashlib, json, os, pathlib, sqlite3, stat, sys, time
        uid, workspace, expected = sys.argv[1:4]
        if workspace != os.path.realpath(workspace) or not os.path.isdir(workspace):
            sys.exit(1)
        home = os.path.realpath(os.path.expanduser('~'))
        choices = [('g',os.path.join(home,'.local/state/nobs/nobs.db')),
                   ('w',os.path.join(workspace,'.nobs/nobs.db'))]
        deadline = time.monotonic() + 2
        found = []
        for label, path in choices:
            if not os.path.lexists(path):
                continue
            st = os.lstat(path)
            if not stat.S_ISREG(st.st_mode) or os.path.realpath(path) != path:
                sys.exit(1)
            cn = sqlite3.connect(pathlib.Path(path).as_uri()+'?mode=ro', uri=True, timeout=.3)
            cn.set_progress_handler(lambda: int(time.monotonic() > deadline), 1000)
            cn.execute('PRAGMA query_only=ON')
            cn.execute('PRAGMA trusted_schema=OFF')
            cn.execute('BEGIN')
            schema = cn.execute("SELECT name,type,sql FROM sqlite_schema WHERE name IN ('sessions','session_messages')").fetchall()
            if len(schema) != 2 or any(kind != 'table' or not sql or not sql.upper().startswith('CREATE TABLE ') for _,kind,sql in schema):
                sys.exit(1)
            matches = cn.execute('SELECT id,message_count FROM sessions WHERE session_id=? AND repo_dir=? LIMIT 2',
                                 (uid,workspace)).fetchall()
            if len(matches) > 1:
                sys.exit(1)
            if matches:
                rowid, count = matches[0]
                if not isinstance(rowid,str) or not rowid or len(rowid)>1024 or type(count) is not int or count < 0:
                    sys.exit(1)
                key = label+':'+hashlib.sha256(os.fsencode(path)+b'\0'+str(st.st_dev).encode()+b'\0'+str(st.st_ino).encode()+b'\0'+rowid.encode()).hexdigest()
                found.append((path,st,cn,rowid,count,key))
            else:
                cn.close()
        # Never read message payloads if zero, duplicated, or replaced.
        if len(found) != 1:
            sys.exit(1)
        path,st,cn,rowid,count,key = found[0]
        if expected and expected != key:
            sys.exit(1)
        rows = cn.execute('SELECT position,CASE WHEN length(CAST(message_json AS BLOB))<=400000 THEN message_json END '
                          'FROM session_messages WHERE session_id=? ORDER BY position DESC LIMIT 64',(rowid,)).fetchall()
        if len(rows) != min(64,count) or any(p != count-1-i or not isinstance(t,str) for i,(p,t) in enumerate(rows)):
            sys.exit(1)
        if not os.path.samestat(st,os.lstat(path)) or os.path.realpath(path) != path:
            sys.exit(1)
        turns = []
        for position, raw in reversed(rows):
            msg = json.loads(raw)
            role = msg.get('role')
            parts = msg.get('parts')
            if not isinstance(parts,list) or any(not isinstance(p,dict) for p in parts):
                sys.exit(1)
            types = [p.get('type') for p in parts]
            if role == 'user' and not (types and all(t in ('tool_result','tool-result') for t in types)):
                text = ''.join(p['text'] for p in parts) if types and all(p.get('type') == 'text' and
                       isinstance(p.get('text'),str) for p in parts) else None
                turn = str(position)+':'+hashlib.sha256(raw.encode()).hexdigest()
                turns.append({'id':turn,'prompt':text if text is not None and len(text)<=4000 else None,'final':None})
            elif role in ('assistant','user','tool'):
                if not turns:
                    continue
                if (role == 'assistant' and msg.get('finish_reason') == 'stop' and not msg.get('error')
                    and types and all(t in ('text','reasoning','thinking') for t in types)
                    and all(isinstance(p.get('text'),str) for p in parts if p.get('type')=='text')):
                    final = ''.join(p['text'] for p in parts if p.get('type')=='text').strip()
                    turns[-1]['final'] = final or None
                else:
                    turns[-1]['final'] = None
            elif role != 'system' or turns:
                sys.exit(1)
        # Baseline comparison needs only prior IDs. Never export old user text,
        # assistant text, tool calls, tool output, or reasoning.
        for turn in turns[:-1]:
            turn['prompt'] = None
            turn['final'] = None
        reply = json.dumps({'session':uid,'workspace':workspace,'database':key,
                            'full':count<=64,'turns':turns},ensure_ascii=False).encode()
        if len(reply)>800000:
            sys.exit(1)
        sys.stdout.buffer.write(reply+b'\n')
    """.trimIndent()
}
