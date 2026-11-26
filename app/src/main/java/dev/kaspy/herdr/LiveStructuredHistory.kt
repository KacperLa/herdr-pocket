package dev.kaspy.herdr

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Resolve only allowlisted bindings for the already-selected Herdr pane. */
internal object LiveStructuredHistory {
    enum class Provider(val label:String) { CODEX("Codex"), CLAUDE("Claude Code"), OPENCODE("OpenCode"), NOBS("nobs") }
    data class Binding(val provider:Provider,val id:String,val workspace:String?=null)
    data class Turn(val id:String,val prompt:String?,val final:String?)
    data class Page(val turns:List<Turn>,val completeHistory:Boolean,val sourceKey:String?=null)

    fun provider(kind:String)=when(kind.lowercase(java.util.Locale.ROOT)) {
        "codex" -> Provider.CODEX
        "claude" -> Provider.CLAUDE
        "opencode" -> Provider.OPENCODE
        "nobs" -> Provider.NOBS
        else -> null
    }

    fun binding(instance:LiveAgentHerdr.Instance):Binding? = runCatching {
        val provider=provider(instance.kind) ?: return null
        if(provider==Provider.CODEX) return LiveCodexHistory.threadId(instance)?.let { Binding(provider,it) }
        val kind=instance.kind.lowercase(java.util.Locale.ROOT)
        val source=if(provider==Provider.NOBS) "custom:nobs" else "herdr:$kind"
        val ref=JSONArray(instance.providerSession ?: return null)
        if(ref.length()!=4 || ref.opt(0)!=source || ref.opt(1)!=kind || ref.opt(2)!="id") return null
        val id=ref.opt(3) as? String ?: return null
        if(provider==Provider.NOBS) {
            val workspace=instance.foregroundCwd?.takeIf(LiveNobsHistory::validWorkspace) ?: return null
            return Binding(provider,id,workspace).takeIf {LiveNobsHistory.validSessionId(id)}
        }
        val valid=if(provider==Provider.CLAUDE) UUID.fromString(id).toString()==id
            else id.matches(Regex("ses_[A-Za-z0-9]{4,96}"))
        if(valid) Binding(provider,id) else null
    }.getOrNull()

    fun match(before:Page,after:Page,prompt:String):LiveCodexHistory.Match {
        if(before.sourceKey!=after.sourceKey) return LiveCodexHistory.Match.Uncertain
        val old=before.turns.lastOrNull()
        val edge=if(old==null) {
            if(!before.completeHistory || !after.completeHistory) return LiveCodexHistory.Match.Uncertain
            -1
        } else after.turns.indexOfFirst {it.id==old.id}.takeIf {it>=0} ?: return LiveCodexHistory.Match.Uncertain
        val fresh=after.turns.drop(edge+1)
        if(fresh.isEmpty()) return LiveCodexHistory.Match.Waiting
        if(fresh.size!=1 || fresh.single().prompt!=prompt) return LiveCodexHistory.Match.Uncertain
        val answer=fresh.single().final ?: return LiveCodexHistory.Match.Waiting
        if(answer.any {it.isISOControl() && it !in "\n\t"}) return LiveCodexHistory.Match.Uncertain
        return if(LiveReadback.validText(answer)) LiveCodexHistory.Match.Completed(answer) else LiveCodexHistory.Match.Uncertain
    }

    fun command(binding:Binding,expectedDatabase:String?=null):String {
        require(binding.provider!=Provider.CODEX)
        if(binding.provider==Provider.NOBS) return LiveNobsHistory.command(binding,expectedDatabase)
        val id=binding.id
        if(binding.provider==Provider.CLAUDE) {
            require(UUID.fromString(id).toString()==id)
            return "/bin/sh -c "+TuiAgents.quote("exec python3 - "+TuiAgents.quote(id)+" <<'POCKET_CLAUDE_READ'\n"+
                claudeReader+"\nPOCKET_CLAUDE_READ")
        }
        require(id.matches(Regex("ses_[A-Za-z0-9]{4,96}")))
        return "/bin/sh -c "+TuiAgents.quote("exec python3 - "+TuiAgents.quote(id)+" <<'POCKET_OPENCODE_READ'\n"+
            openCodeReader+"\nPOCKET_OPENCODE_READ")
    }

    // Use the agent's supported JSON export, strip reasoning and tool payloads
    // before anything goes over SSH, and reject oversized or stalled exports.
    private val openCodeReader="""
        import json, os, selectors, shutil, subprocess, sys, time
        uid = sys.argv[1]
        home = os.path.expanduser('~')
        options = [shutil.which('opencode'), home+'/.opencode/bin/opencode', home+'/.local/bin/opencode',
                   home+'/.nix-profile/bin/opencode', '/run/current-system/sw/bin/opencode']
        binary = next((p for p in options if p and os.path.isfile(p) and os.access(p,os.X_OK)), None)
        if binary is None:
            sys.exit(1)
        until = time.monotonic() + 10
        limit = 12 * 1024 * 1024
        def export(args):
            with subprocess.Popen([binary, *args, uid], stdin=subprocess.DEVNULL,
                                  stdout=subprocess.PIPE, stderr=subprocess.DEVNULL) as p:
                try:
                    data = bytearray()
                    with selectors.DefaultSelector() as sel:
                        sel.register(p.stdout, selectors.EVENT_READ)
                        while True:
                            remaining = until - time.monotonic()
                            if remaining <= 0 or not sel.select(remaining):
                                sys.exit(1)
                            read = os.read(p.stdout.fileno(), 65536)
                            if not read:
                                break
                            data.extend(read)
                            if len(data) > limit:
                                sys.exit(1)
                    rc = p.wait(timeout=max(0,until-time.monotonic()))
                    if rc and data:
                        sys.exit(1)
                    return data if rc == 0 else None
                finally:
                    if p.poll() is None:
                        p.kill()
        data = export(['session','export'])
        if data is None:
            data = export(['export'])
        if not data:
            sys.exit(1)
        root = json.loads(data)
        info, rows = root['info'], root['messages']
        if info.get('id') != uid or info.get('parentID') or info.get('revert') or not isinstance(rows,list):
            sys.exit(1)
        result = []
        for i, msg in enumerate(rows[-48:]):
            raw = msg['info']
            last_message = i == min(48,len(rows))-1
            parts = []
            for p in msg['parts']:
                lean = {k:p[k] for k in ('type','messageID','sessionID','ignored','synthetic') if k in p}
                if p.get('type') == 'text':
                    t = p.get('text')
                    lean['text'] = (t if last_message or (raw.get('role')=='user' and len(t)<=4000) else '') if isinstance(t,str) else None
                parts.append(lean)
            keep = {k:raw[k] for k in ('id','sessionID','role','parentID','finish','time') if k in raw}
            if raw.get('error') is not None:
                keep['error'] = True
            result.append({'info':keep,'parts':parts})
        projection = json.dumps({'info':{'id':uid},'messages':result,'windowed':len(rows)>48},ensure_ascii=False).encode()
        if len(projection)>800000:
            sys.exit(1)
        sys.stdout.buffer.write(projection+b'\n')
    """.trimIndent()

    // Only the exact UUID basename, exactly one regular file beneath the Claude projects
    // root. Never follow pane-provided paths, symlinks, tool outputs, or subagent transcripts.
    private val claudeReader="""
        import collections, json, os, stat, sys
        root = os.path.join(os.environ.get('CLAUDE_CONFIG_DIR') or os.path.expanduser('~/.claude'), 'projects')
        uid = sys.argv[1]
        flags = os.O_RDONLY | os.O_DIRECTORY
        hits = []
        with os.scandir(root) as dirs:
            for i, entry in enumerate(dirs):
                if i >= 4096:
                    sys.exit(1)
                if not entry.is_dir(follow_symlinks=False):
                    continue
                d = os.open(entry.path, flags | os.O_NOFOLLOW)
                try:
                    try:
                        f = os.open(uid + '.jsonl', os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=d)
                    except FileNotFoundError:
                        continue
                    if not stat.S_ISREG(os.fstat(f).st_mode):
                        os.close(f)
                        sys.exit(1)
                    hits.append(f)
                    if len(hits) != 1:
                        sys.exit(1)
                finally:
                    os.close(d)
        if len(hits) != 1:
            sys.exit(1)
        with os.fdopen(hits[0], 'rb') as f:
            end = os.fstat(f.fileno()).st_size
            start = max(0, end - 768 * 1024)
            if start:
                f.seek(start - 1)
                if f.read(1) != b'\n':
                    f.readline(768 * 1024)
            tail = f.read(max(0, end - f.tell()))
        if tail and not tail.endswith(b'\n'):
            tail = tail[:tail.rfind(b'\n') + 1]
            complete = False
        else:
            complete = True
        events = collections.deque(maxlen=140)
        picked = 0
        parents = {}
        last_user = None
        def belongs_to_prompt(parent):
            seen = set()
            while isinstance(parent, str) and parent not in seen:
                if parent == last_user and last_user:
                    return True
                seen.add(parent)
                parent = parents.get(parent)
            return False
        for raw in tail.splitlines():
            row = json.loads(raw)
            kind = row.get('type')
            if kind not in ('user', 'assistant') or row.get('isSidechain'):
                continue
            if row.get('sessionId') != uid:
                sys.exit(1)
            node = row.get('uuid')
            parent = row.get('parentUuid')
            if not isinstance(node, str) or node in parents:
                sys.exit(1)
            parents[node] = parent
            if row.get('isMeta'):
                continue
            msg = row.get('message')
            if not isinstance(msg, dict) or msg.get('role') != kind:
                sys.exit(1)
            parts = msg.get('content')
            if isinstance(parts, str):
                parts = [{'type':'text','text':parts}]
            if not isinstance(parts, list) or any(not isinstance(p, dict) for p in parts):
                sys.exit(1)
            names = [p.get('type') for p in parts]
            if kind == 'user' and names and all(t == 'tool_result' for t in names):
                events.append({'kind':'tool'})
            else:
                text = '\n'.join(p['text'] for p in parts if p.get('type') == 'text' and isinstance(p.get('text'), str))
                if kind == 'user':
                    last_user = node
                    events.append({'kind':'user','id':node,'text':text[:4001] if names and all(t == 'text' for t in names) and not row.get('isCompactSummary') else None})
                else:
                    final = msg.get('stop_reason') in ('end_turn', 'stop_sequence') and not row.get('isApiErrorMessage') and not row.get('isCompactSummary')
                    final = final and belongs_to_prompt(parent) and all(t in ('text','thinking','redacted_thinking') for t in names)
                    events.append({'kind':'assistant','final':text if final else None})
            picked += 1
        projected = list(events)
        for older in projected[:-1]:
            if 'final' in older:
                older['final'] = None
        projection = json.dumps({'session':uid,'complete':complete,'full':not start and picked <= 140,'events':projected},ensure_ascii=False).encode()
        if len(projection)>800000:
            sys.exit(1)
        sys.stdout.buffer.write(projection+b'\n')
    """.trimIndent()

    fun parse(binding:Binding,output:String):Page? = runCatching {
        val root=JSONObject(output)
        if(binding.provider==Provider.CLAUDE) parseClaude(binding.id,root)
        else if(binding.provider==Provider.OPENCODE) parseOpenCode(binding.id,root)
        else if(binding.provider==Provider.NOBS) LiveNobsHistory.parse(binding,root)
        else null
    }.getOrNull()

    private fun parseClaude(id:String,root:JSONObject):Page? {
        if(root.opt("session")!=id || root.opt("complete")!=true) return null
        val events=root.getJSONArray("events")
        val turns=mutableListOf<Turn>()
        for(i in 0 until events.length()) {
            val event=events.getJSONObject(i)
            when(event.opt("kind")) {
                "user" -> turns.add(Turn(messageId(event.opt("id")) ?: return null,event.opt("text") as? String,null))
                "assistant","tool" -> if(turns.isNotEmpty()) {
                    val prior=turns.last()
                    turns[turns.lastIndex]=prior.copy(final=(event.opt("final") as? String)?.takeIf {it.isNotBlank()})
                }
                else -> return null
            }
        }
        return page(turns,root.opt("full")==true)
    }

    private fun parseOpenCode(id:String,root:JSONObject):Page? {
        val session=root.getJSONObject("info")
        if(session.opt("id")!=id || !session.isNull("parentID")) return null
        val messages=root.getJSONArray("messages")
        val windowed=root.opt("windowed")==true
        val turns=mutableListOf<Turn>()
        val ids=HashSet<String>()
        for(i in 0 until messages.length()) {
            val message=messages.getJSONObject(i)
            val info=message.getJSONObject("info")
            if(info.opt("sessionID")!=id) return null
            val mid=messageId(info.opt("id")) ?: return null
            if(!ids.add(mid)) return null
            val parts=message.getJSONArray("parts").let {arr -> (0 until arr.length()).map {arr.getJSONObject(it)}}
            if(parts.any {it.opt("sessionID")!=id || it.opt("messageID")!=mid}) return null
            val text=parts.filter {it.opt("type")=="text" && it.opt("synthetic")!=true && it.opt("ignored")!=true}
                .map {it.opt("text") as? String ?: return null}.joinToString("\n")
            when(info.opt("role")) {
                "user" -> turns.add(Turn(mid,text.takeIf {parts.isNotEmpty() && parts.all {p ->
                    p.opt("type")=="text" && p.opt("synthetic")!=true && p.opt("ignored")!=true
                }},null))
                "assistant" -> {
                    if(turns.isEmpty() && windowed) continue
                    if(turns.isEmpty() || info.opt("parentID")!=turns.last().id) return null
                    val last=turns.last()
                    val final=info.opt("finish")=="stop" && info.isNull("error") &&
                        (info.optJSONObject("time")?.opt("completed") as? Number ?: 0).toLong()>0 &&
                        parts.none {it.opt("type")=="tool"}
                    turns[turns.lastIndex]=last.copy(final=text.takeIf {final && it.isNotBlank()})
                }
                else -> return null
            }
        }
        return page(turns,!windowed)
    }

    private fun messageId(value:Any?)=(value as? String)?.takeIf {it.isNotBlank() && it.length<=160}
    private fun page(turns:List<Turn>,full:Boolean):Page? {
        if(turns.map {it.id}.toSet().size!=turns.size) return null
        return Page(turns.takeLast(8),full && turns.size<=8)
    }
}
