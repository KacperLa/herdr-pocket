package dev.kaspy.herdr
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
fun main() {
    val (port, pin) = File("fixture-info").readLines()
    val base = SshConfig("127.0.0.1", port.toInt(), "fixture", null)
    val events = LinkedBlockingQueue<SshSession.Event>()
    val cmd = CoreSessions.listCommand()
    fun passphrase() = checkNotNull(System.getenv("HERDR_FIXTURE_KEY_PASSPHRASE")).toByteArray()
    fun event(timeout: Long = 8L) = checkNotNull(events.poll(timeout, TimeUnit.SECONDS)) { "No event received" }
    SshSession { _, e -> events.add(e) }.use { ssh ->
        checkNotNull(ssh.connect(base, byteArrayOf(), null, cmd))
        check(event() == SshSession.Event.RejectedHostKey(pin, null))
        val wrong = sshSha256Fingerprint(byteArrayOf(1,2,3))
        checkNotNull(ssh.connect(base.copy(expectedPin=wrong), File("local-client").readBytes(), passphrase(), cmd))
        check(event() == SshSession.Event.RejectedHostKey(pin, wrong))
        val endpoint=HostEndpoint.parse(base.host,port)!!
        val savedPins=mutableMapOf(endpoint to wrong)
        val trust=HostTrust(savedPins::get) { host, fingerprint ->
            savedPins[host]=fingerprint
            true
        }
        // Explicit review opens a separate unauthenticated probe, never a fallback login.
        checkNotNull(ssh.connect(base, byteArrayOf(), null, "true"))
        val review=event() as SshSession.Event.RejectedHostKey
        check(review.expected==null && review.actual==pin)
        val offer=checkNotNull(trust.replacementOffer(endpoint,review.actual,1))
        check(trust.pin(endpoint)==wrong)
        check(trust.accept(offer,endpoint,1))
        checkNotNull(ssh.connect(base.copy(expectedPin=trust.pin(endpoint)), File("local-client").readBytes(), passphrase(), cmd, pty=false))
        val listing = StringBuilder()
        val diagnostics = StringBuilder()
        while (true) {
            when (val e = event()) {
                SshSession.Event.Connected -> ssh.resize(104,33,700,400)
                is SshSession.Event.Output -> listing.append(e.bytes.toString(Charsets.UTF_8))
                is SshSession.Event.ErrorOutput -> diagnostics.append(e.bytes.toString(Charsets.UTF_8))
                is SshSession.Event.Closed -> { check(e.exitStatus == 0); break }
                else -> error(e)
            }
        }
        val address = CoreSessions.parse(listing.toString()).single().address
        check(address == "box/t3")
        check(diagnostics.length == 8192 && diagnostics.startsWith("Stderr must not corrupt JSON"))
        repeat(2) { index ->
            checkNotNull(ssh.connect(base.copy(expectedPin=pin), File("local-client").readBytes(), passphrase(), cmd, pty=false))
            var totalBytes = 0
            while (true) {
                when (val e = event(38)) {
                    SshSession.Event.Connected -> Unit
                    is SshSession.Event.Output -> totalBytes += e.bytes.size
                    is SshSession.Event.Failed -> {
                        check(e.message == if (index == 0) "Session list exceeds 1 MiB."
                            else "Session list did not finish within 30 seconds.")
                        break
                    }
                    else -> error(e)
                }
            }
            check(totalBytes <= 1024 * 1024)
        }
        repeat(2) {
            val userKey = File("local-client").readBytes()
            val pass = passphrase()
            checkNotNull(ssh.connect(base.copy(expectedPin=pin), userKey, pass, CoreSessions.attachCommand(address), 90, 27))
            userKey.fill(0); pass.fill(0)
            val out = StringBuilder()
            var exitCode:Int? = null
            while (exitCode == null) {
                when(val e = event()) {
                    SshSession.Event.Connected -> {
                        check(ssh.send("test keystrokes".toByteArray()))
                        ssh.resize(104,33,700,400)
                    }
                    is SshSession.Event.Output -> out.append(e.bytes.toString(Charsets.UTF_8))
                    is SshSession.Event.ErrorOutput -> out.append(e.bytes.toString(Charsets.UTF_8))
                    is SshSession.Event.Closed -> exitCode = checkNotNull(e.exitStatus)
                    else -> error(e)
                }
            }
            check(exitCode == 23 && "hello" in out && "error" in out) { "Exit $exitCode output $out" }
        }
        listOf("drop now", "close channel").forEach { signal ->
            checkNotNull(ssh.connect(base.copy(expectedPin = pin), File("local-client").readBytes(),
                passphrase(), CoreSessions.attachCommand(address)))
            try {
                while (true) {
                    when (val e = event()) {
                        SshSession.Event.Connected -> check(ssh.send(signal.toByteArray()))
                        is SshSession.Event.Output -> Unit
                        SshSession.Event.TransportLost -> { check(signal == "drop now"); break }
                        is SshSession.Event.Closed -> { check(signal == "close channel" && e.exitStatus == null); break }
                        else -> error(e)
                    }
                }
            } finally {
                ssh.disconnect()
            }
        }
        val config = base.copy(expectedPin = pin)
        fun attach(target: String) = checkNotNull(ssh.connect(config, File("local-client").readBytes(),
            passphrase(), CoreSessions.attachCommand(target)))
        fun expectText(expected: String, observe: (ByteArray) -> Unit = {}) {
            var text = ""
            while (expected !in text) {
                val e = event() as? SshSession.Event.Output ?: error("Expected output: $expected")
                observe(e.bytes)
                text += e.bytes.toString(Charsets.UTF_8)
            }
        }
        fun awaitReady(output: String) {
            var connected = false
            var text = ""
            while (!connected || output !in text) {
                when (val e = event()) {
                    SshSession.Event.Connected -> connected = true
                    is SshSession.Event.Output -> text += e.bytes.toString(Charsets.UTF_8)
                    else -> error(e)
                }
            }
        }
        val liveGeneration = attach("box/t3")
        awaitReady("old ready")
        val oldTerminal = checkNotNull(ssh.terminalIdentity())
        val sftpResults = LinkedBlockingQueue<SftpRead>()
        fun readFiles(root: String, parts: List<String> = emptyList(), text: Boolean = false, canonical: Boolean = true) {
            checkNotNull(ssh.readFiles(oldTerminal, root, parts, text, canonical) { _, result -> sftpResults.add(result) })
        }
        fun sftpResult(): SftpRead = checkNotNull(sftpResults.poll(8, TimeUnit.SECONDS)) { "Missing SFTP result" }
        readFiles("/alpha", canonical = false)
        check(ssh.sendToTerminal(oldTerminal, "during sftp".toByteArray()))
        expectText("old while browsing")
        val firstFolder = sftpResult() as SftpRead.Folder
        check(firstFolder.root == "/alpha" && firstFolder.entries.map { it.name } ==
            listOf("subfolder", "a*?.txt", "other-link"))
        check(!firstFolder.entries.last().regular && !firstFolder.entries.last().directory)
        readFiles("/alpha", listOf("a*?.txt"), text = true)
        check(sftpResult() == SftpRead.Preview("agent text ✓", false))
        readFiles("/beta", canonical = false)
        check((sftpResult() as SftpRead.Folder).entries.single().name == "other.txt")
        readFiles("/alpha", listOf("other-link"), text = true)
        check(sftpResult() is SftpRead.Failure)
        check(ssh.terminalIdentity() === oldTerminal)
        val listId = checkNotNull(ssh.listOnTransport(cmd))
        check(ssh.switchPty(CoreSessions.attachCommand("box/t4")) == null)
        check(ssh.send("during list".toByteArray()))
        expectText("old while listing")
        val list = event() as SshSession.Event.OperationList
        check(list.id == listId && list.exitStatus == 0 && list.stderr.toString(Charsets.UTF_8) == "list diagnostic")
        val selected = CoreSessions.parse(list.stdout.toString(Charsets.UTF_8)).single().address
        check(selected == "box/t4")
        val historyCommand="/bin/sh -c " + TuiAgents.quote("fixture=1\nexec codex app-server --listen stdio://")
        val request="{\"id\":2}\n".toByteArray()
        val exchangeId=checkNotNull(ssh.exchangeOnTransport(historyCommand,
            SshSession.StdioExchange(request) { it.lineSequence().any { line -> line == "{\"id\":2,\"result\":{}}" } && it.endsWith('\n') }))
        val exchanged=event() as SshSession.Event.OperationList
        check(exchanged.id==exchangeId && exchanged.exitStatus==0)
        check(exchanged.stdout.decodeToString().endsWith("{\"id\":2,\"result\":{}}\n"))
        check(ssh.terminalIdentity()===oldTerminal)
        val failureId = checkNotNull(ssh.switchPty(CoreSessions.attachCommand("box/bad")))
        check((event() as SshSession.Event.OperationFailed).id == failureId)
        check(ssh.send("after failure".toByteArray()))
        expectText("old after failure")
        fun rpc(command:String):String {
            val id=checkNotNull(ssh.listOnTransport(command))
            val reply=event() as SshSession.Event.OperationList
            check(reply.id==id && reply.exitStatus==0)
            check(ssh.terminalIdentity()===oldTerminal)
            return reply.stdout.toString(Charsets.UTF_8)
        }
        rpc(CoreSessions.createShellCommand("shell-fixture"))
        val coreShell=checkNotNull(CoreSessions.createdShell(CoreSessions.parse(rpc(CoreSessions.listCommand())),"shell-fixture"))
        check(coreShell.address=="t9" && !coreShell.isAgent)
        val (tuiAgents,tuiShells)=groupedLiveRoster(TuiAgents.parse(rpc(TuiAgents.listCommand("office"))))
        val tuiAgent=tuiAgents.single()
        check(tuiAgent.name=="Codex 1" && tuiAgent.address=="term_busy")
        check(tuiShells.single().address=="term_shell")
        val tuiBusyId=checkNotNull(ssh.switchPty(AgentBackend.TUI.switchCommand(tuiAgent.address,"office"),tui=true))
        val tuiBusy=event() as SshSession.Event.OperationFailed
        check(tuiBusy.id==tuiBusyId && tuiBusy.message.contains("No takeover was attempted."))
        check(ssh.terminalIdentity()===oldTerminal)
        check(ssh.send("after owner refusal".toByteArray()))
        expectText("old after owner refusal")

        val existing=checkNotNull(tuiAgent.tuiLocation)
        check(TuiMutations.confirmsPane(rpc(TuiMutations.inspectPane(existing,"office")),existing))
        check(TuiMutations.confirmsPane(rpc(TuiMutations.renamePane(existing,"Jetson AI 2","office")),existing,"Jetson AI 2"))
        val tabTarget=TuiMutations.RenameTarget.TAB
        check(TuiMutations.confirmsTarget(rpc(TuiMutations.inspectTarget(existing,tabTarget,"office")),existing,tabTarget))
        check(TuiMutations.confirmsTarget(
            rpc(TuiMutations.renameTarget(existing,tabTarget,"Review tab","office")),existing,tabTarget,"Review tab"))
        val fresh=checkNotNull(TuiMutations.newShellPane(rpc(TuiMutations.createShellTab(existing.workspaceId,"office")),existing.workspaceId))
        check(fresh.terminalId!=existing.terminalId && fresh.paneId!=existing.paneId)
        check(TuiMutations.confirmsPane(rpc(TuiMutations.inspectPane(fresh,"office")),fresh))
        val freshShell=TuiAgents.plainTerminal(fresh)
        check(!freshShell.isAgent && freshShell.address=="term_fast")
        check(ssh.sendToTerminal(oldTerminal,"after roster mutations".toByteArray()))
        expectText("old after roster mutations")
        val cancelId = checkNotNull(ssh.listOnTransport(cmd))
        check(ssh.send("confirm pending".toByteArray()))
        expectText("cancel ready")
        check(ssh.cancelOperation(cancelId))
        expectText("old after cancel")
        var switchId: Long? = null
        repeat(100) {
            if (switchId == null) {
                switchId = ssh.switchPty(CoreSessions.attachCommand(selected))
                if (switchId == null) Thread.sleep(15)
            }
        }
        checkNotNull(switchId)
        check((event() as SshSession.Event.OperationSwitched).id == switchId)
        check(ssh.isCurrent(liveGeneration) && !ssh.cancelOperation(switchId!!))
        expectText("new early")
        val newTerminal = checkNotNull(ssh.terminalIdentity())
        check(newTerminal !== oldTerminal)
        check(!ssh.sendToTerminal(oldTerminal, "stale input".toByteArray()))
        check(!ssh.sendToTerminal(Any(), "unrelated input".toByteArray()))
        ssh.resize(73, 31, 712, 489)
        check(ssh.send("new input".toByteArray()))
        expectText("new again")
        val mouse = TerminalMouse()
        check(!mouse.wheelEnabled)
        check(ssh.send("request mouse modes".toByteArray()))
        expectText("mouse enabled") { mouse.observe(it) }
        check(mouse.alternateScreen && mouse.wheelEnabled)
        check(ssh.sendToTerminal(newTerminal, checkNotNull(mouse.wheel(TerminalMouse.Direction.UP, 12, 7))))
        check(ssh.sendToTerminal(newTerminal, checkNotNull(mouse.wheel(TerminalMouse.Direction.DOWN, 15, 9))))
        val click = checkNotNull(mouse.click(23, 14)) // within the confirmed resized 73×31 grid.
        check(!ssh.sendToTerminal(oldTerminal,click))
        check(ssh.sendToTerminal(newTerminal,click))
        expectText("mouse disabled") { mouse.observe(it) }
        check(!mouse.alternateScreen && !mouse.wheelEnabled)
        check(mouse.wheel(TerminalMouse.Direction.DOWN, 15, 9) == null)
        check(mouse.click(23, 14) == null)
        val tuiStarted = System.nanoTime()
        val tuiSwitchId = checkNotNull(ssh.switchPty(AgentBackend.TUI.switchCommand(freshShell.address,"office"),tui=true))
        val opening = event()
        check(opening is SshSession.Event.Output && opening.bytes.toString(Charsets.UTF_8)=="previous PTY still live")
        check(ssh.terminalIdentity()===newTerminal)
        check(ssh.sendToTerminal(newTerminal,"still live while opening".toByteArray()))
        check((event() as SshSession.Event.OperationSwitched).id==tuiSwitchId)
        val tuiMs = (System.nanoTime()-tuiStarted)/1_000_000
        check(tuiMs<2_000) { "complete loopback TUI frame should beat old 2.5s wait: ${tuiMs}ms" }
        println("TUI switch after complete first frame: ${tuiMs}ms (same authenticated transport).")
        expectText("Visit https://example.test/agent?q=1 now")
        val tuiTerminal = checkNotNull(ssh.terminalIdentity())
        check(tuiTerminal !== newTerminal && ssh.isCurrent(liveGeneration))
        check(!ssh.sendToTerminal(newTerminal,"stale input".toByteArray()))
        check(ssh.sendToTerminal(tuiTerminal,"cd '/shell-root/live project'\r".toByteArray()))
        expectText("synthetic shell changed directory")
        val shellLookup=checkNotNull(ssh.listOnTransport(TuiAgents.listCommand("office")))
        val refreshed=event() as SshSession.Event.OperationList
        check(refreshed.id==shellLookup && refreshed.exitStatus==0)
        val rootReport=refreshed.stdout.toString(Charsets.UTF_8)
        val shellRoot=checkNotNull(AgentFiles.root(rootReport,freshShell.address))
        check(shellRoot==AgentFilesRoot("/shell-root/live project",false))
        check(AgentFiles.root(rootReport,"term_busy")==AgentFilesRoot("/alpha",false))
        check(ssh.readFiles(newTerminal,shellRoot.path,emptyList(),false,false) {_,_ ->
            error("A stale Core PTY must not browse the shell's directory")
        }==null)
        checkNotNull(ssh.readFiles(tuiTerminal,shellRoot.path,emptyList(),false,false) {_,result -> sftpResults.add(result)})
        check(ssh.sendToTerminal(tuiTerminal,"still at shell".toByteArray()))
        expectText("shell active while browsing")
        val shellFolder=sftpResult() as SftpRead.Folder
        check(shellFolder.root==shellRoot.path && shellFolder.entries.map { it.name }==listOf("empty [1]*?","empty [1]neighbor","keep-folder","rmdir-race","ambiguous-delete.bin","capture.bin","delete [1]*?.bin","delete [1]abcX.bin","escape-link","folder-link","full-source.txt","inside.txt","slow.bin"))
        check(ssh.terminalIdentity()===tuiTerminal)
        val downloads=LinkedBlockingQueue<SftpDownload>()
        val complete=File.createTempFile("outside-preview",".tmp",File("."))
        check(ssh.downloadFile(newTerminal,shellRoot.path,listOf("full-source.txt"),complete) {_,_ ->
            error("A stale PTY must not submit a download")
        }==null)
        checkNotNull(ssh.downloadFile(tuiTerminal,shellRoot.path,listOf("full-source.txt"),complete) {_,result ->
            downloads.add(result)
        })
        val expectedBytes=("\uFEFF" + "UTF-8 ✓ line\r\n".repeat(12_000) + "tail\r").toByteArray()
        check(expectedBytes.size>128*1024)
        val completeResult=downloads.poll(10,TimeUnit.SECONDS)
        check(completeResult==SftpDownload.Success(expectedBytes.size.toLong())) {
            "Expected ${expectedBytes.size} original bytes, got $completeResult"
        }
        check(complete.readBytes().contentEquals(expectedBytes))
        check(complete.delete())
        val escape=File.createTempFile("reject-link",".tmp",File("."))
        checkNotNull(ssh.downloadFile(tuiTerminal,shellRoot.path,listOf("escape-link"),escape) {_,result ->
            downloads.add(result)
        })
        check(downloads.poll(10,TimeUnit.SECONDS) is SftpDownload.Failure && !escape.exists())
        check(ssh.terminalIdentity()===tuiTerminal)
        val binaryName="capture.bin"
        checkNotNull(ssh.readFiles(tuiTerminal,shellRoot.path,listOf(binaryName),true,true) {_,result ->
            sftpResults.add(result)
        })
        check(sftpResult()===SftpRead.Unrenderable)
        checkNotNull(ssh.readFiles(tuiTerminal,shellRoot.path,listOf("missing.bin"),true,true) {_,result ->
            sftpResults.add(result)
        })
        check(sftpResult() is SftpRead.Failure)
        val binary=File.createTempFile("binary-exact",".tmp",File("."))
        check(ssh.downloadFile(newTerminal,shellRoot.path,listOf(binaryName),binary) {_,_ ->
            error("A stale PTY must not submit a binary download")
        }==null)
        checkNotNull(ssh.downloadFile(tuiTerminal,shellRoot.path,listOf(binaryName),binary) {_,result ->
            downloads.add(result)
        })
        val expectedBinary=ByteArray(256*900) { it.toByte() }
        check(downloads.poll(10,TimeUnit.SECONDS)==SftpDownload.Success(expectedBinary.size.toLong()))
        check(binary.readBytes().contentEquals(expectedBinary) && binary.delete())
        val partial=File.createTempFile("binary-cancel",".tmp",File("."))
        val cancelRequest=checkNotNull(ssh.downloadFile(tuiTerminal,shellRoot.path,listOf("slow.bin"),partial) {_,result ->
            downloads.add(result)
        })
        val stageDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(7)
        while(partial.length()==0L && System.nanoTime()<stageDeadline) Thread.sleep(10)
        check(partial.length() in 1 until (256L*16_384)) {"Expected a partial stage before cancellation"}
        ssh.cancelFiles(cancelRequest)
        check(cancelRequest.canceled && !partial.exists())
        check(downloads.poll(300,TimeUnit.MILLISECONDS)==null) {"Canceled binary download returned a result"}
        check(ssh.terminalIdentity()===tuiTerminal)
        println("Binary preview fallback, exact 230400 download bytes, stale route and cancellation verified.")
        val uploads=LinkedBlockingQueue<SftpUpload>()
        val payload=ByteArray(256*1024) {it.toByte()}
        val filename="payload [1]*?.bin"
        check(ssh.uploadFile(newTerminal,shellRoot.path,emptyList(),filename,{error("stale source opened")}) {_,_ ->
            error("Stale PTY cannot upload")
        }==null)
        fun upload(name:String,source:()->java.io.InputStream) {
            checkNotNull(ssh.uploadFile(tuiTerminal,shellRoot.path,emptyList(),name,source) {_,result -> uploads.add(result)})
        }
        upload(filename){payload.inputStream()}
        val uploadResult=uploads.poll(10,TimeUnit.SECONDS)
        check(uploadResult==SftpUpload.Success(payload.size.toLong())) {"Literal upload result: $uploadResult"}
        upload("race.bin"){payload.inputStream()}
        check((uploads.poll(10,TimeUnit.SECONDS) as? SftpUpload.Failure)?.message?.contains("Nothing was overwritten")==true)
        upload(filename){error("Collision opened the source")}
        check((uploads.poll(10,TimeUnit.SECONDS) as? SftpUpload.Failure)?.message?.contains("Nothing was overwritten")==true)
        val uploadOpened=java.util.concurrent.CountDownLatch(1)
        val releaseUpload=java.util.concurrent.CountDownLatch(1)
        val waitedOnce=java.util.concurrent.atomic.AtomicBoolean()
        val canceledUpload=checkNotNull(ssh.uploadFile(tuiTerminal,shellRoot.path,emptyList(),"cancel-upload.bin",{
            object : java.io.ByteArrayInputStream(payload) {
                override fun read(bytes:ByteArray,offset:Int,length:Int):Int {
                    if(waitedOnce.compareAndSet(false,true)) {
                        uploadOpened.countDown()
                        check(releaseUpload.await(5,TimeUnit.SECONDS))
                    }
                    return super.read(bytes,offset,length)
                }
            }
        }) {_,result -> uploads.add(result)})
        check(uploadOpened.await(7,TimeUnit.SECONDS))
        ssh.cancelFiles(canceledUpload)
        releaseUpload.countDown()
        check(uploads.poll(300,TimeUnit.MILLISECONDS)==null)
        println("Upload verified with literal glob name, exact 262144 bytes, collision race and temporary cleanup.")
        val deletions=LinkedBlockingQueue<SftpDelete>()
        val literalDelete="delete [1]*?.bin"
        check(ssh.deleteFile(newTerminal,shellRoot.path,emptyList(),literalDelete) {_,_ ->
            error("Stale PTY cannot delete")
        }==null)
        fun remove(name:String, identity:SftpFileIdentity?=null,directory:Boolean=false):SftpDelete {
            checkNotNull(ssh.deleteFile(tuiTerminal,shellRoot.path,emptyList(),name,identity,directory) {_,result ->
                deletions.add(result)
            })
            return checkNotNull(deletions.poll(10,TimeUnit.SECONDS))
        }
        val captured=checkNotNull(shellFolder.entries.single {it.name==literalDelete}.identity)
        check(remove(literalDelete,captured)===SftpDelete.Success)
        check(remove("escape-link") is SftpDelete.Failure)
        check(remove("keep-folder") is SftpDelete.Failure)
        fun removeDirectory(name:String):SftpDelete {
            val entry=shellFolder.entries.single {it.name==name}
            return remove(name,entry.identity,directory=true)
        }
        check(removeDirectory("empty [1]*?")===SftpDelete.Success)
        check((removeDirectory("keep-folder") as SftpDelete.Failure).message.contains("Only empty folders"))
        check(removeDirectory("folder-link") is SftpDelete.Failure)
        check((removeDirectory("rmdir-race") as SftpDelete.Failure).message.contains("Could not confirm"))

        val stale=checkNotNull(shellFolder.entries.single {it.name=="delete [1]abcX.bin"}.identity)
        check((remove("delete [1]abcX.bin",stale.copy(size=stale.size+1)) as SftpDelete.Failure).message.contains("changed"))
        check((remove("ambiguous-delete.bin") as SftpDelete.Failure).message.contains("Could not confirm"))
        check(ssh.terminalIdentity()===tuiTerminal)
        println("Delete verified: literal regular file only, literal empty directory only, nonempty and link refused, ambiguous outcome not retried.")
        val mkdirs=LinkedBlockingQueue<SftpMkdir>()
        val literalFolder="""new [1]*? \ folder"""
        check(ssh.createFolder(newTerminal,shellRoot.path,emptyList(),literalFolder) {_,_ ->
            error("Stale PTY cannot create a folder")
        }==null)
        check(ssh.createFolder(tuiTerminal,shellRoot.path,emptyList(),"../escape") {_,_ ->
            error("Multi-component folder name must not reach SFTP")
        }==null)
        fun mkdir(parts:List<String>,name:String):SftpMkdir {
            checkNotNull(ssh.createFolder(tuiTerminal,shellRoot.path,parts,name) {_,result -> mkdirs.add(result)})
            return checkNotNull(mkdirs.poll(10,TimeUnit.SECONDS))
        }
        check(mkdir(listOf("keep-folder"),literalFolder)===SftpMkdir.Success)
        check((mkdir(listOf("keep-folder"),literalFolder) as SftpMkdir.Failure).message.contains("already"))
        check(mkdir(listOf("escape-link"),"outside") is SftpMkdir.Failure)
        check(mkdir(listOf("inside.txt"),"under-file") is SftpMkdir.Failure)
        check((mkdir(emptyList(),"mkdir-race") as SftpMkdir.Failure).message.contains("Could not confirm"))
        check(ssh.terminalIdentity()===tuiTerminal)
        println("Mkdir verified: exact nested literal, conflict/race preserved, invalid/link/file parent and stale PTY refused.")
        // Representative output from the pinned encoder: first logical key is
        // Ctrl+Alt+C, then the tail and the next key are unmodified.
        val modifiedCommit = byteArrayOf(0x1b,0x03,'x'.code.toByte())
        check(!ssh.sendToTerminal(newTerminal,modifiedCommit))
        check(ssh.sendToTerminal(tuiTerminal,modifiedCommit))
        check(ssh.sendToTerminal(tuiTerminal,"y".toByteArray()))
        expectText("dock-key bytes received")
        check(ssh.sendToTerminal(tuiTerminal,"drop switched".toByteArray()))
        check(event() == SshSession.Event.TransportLost)
        check(attach(selected) != liveGeneration)
        awaitReady("resumed last target")
        check(ssh.send("exit resumed".toByteArray()))
        check(event() == SshSession.Event.Closed(0))
        val idle = checkNotNull(ssh.connect(config, File("local-client").readBytes(), passphrase(), "unused",
            pty = false, transportOnly = true))
        check(event() == SshSession.Event.TransportReady)
        check(ssh.hasIdleTransport() && ssh.terminalIdentity() == null)
        check(!ssh.send("no pane yet".toByteArray()))
        val roster = checkNotNull(ssh.listOnTransport(cmd))
        val rosterReply = event() as SshSession.Event.OperationList
        check(rosterReply.id == roster && rosterReply.exitStatus == 0 && ssh.isCurrent(idle))
        check(CoreSessions.parse(rosterReply.stdout.toString(Charsets.UTF_8)).single().address == selected)
        check(ssh.hasIdleTransport() && ssh.terminalIdentity() == null)
        val first = checkNotNull(ssh.switchPty(AgentBackend.CORE.switchCommand(selected,null)))
        check(event() == SshSession.Event.OperationSwitched(first))
        check(ssh.isCurrent(idle) && !ssh.hasIdleTransport() && ssh.terminalIdentity() != null)
        expectText("first pane on existing transport")
        val firstIdentity=checkNotNull(ssh.terminalIdentity())
        check(ssh.send("exit first".toByteArray()))
        check(event() == SshSession.Event.TerminalEnded(firstIdentity,0))
        check(ssh.isCurrent(idle) && ssh.hasIdleTransport() && ssh.terminalIdentity()==null)
        check(!ssh.sendToTerminal(firstIdentity,"stale input".toByteArray()))
        val afterClose=checkNotNull(ssh.listOnTransport(cmd))
        val updated=event() as SshSession.Event.OperationList
        check(updated.id==afterClose && updated.exitStatus==0)
        check(CoreSessions.parse(updated.stdout.toString(Charsets.UTF_8)).single().address=="box/t3")
        val afterSwitch=checkNotNull(ssh.switchPty(AgentBackend.CORE.switchCommand("box/t3",null)))
        check(event() == SshSession.Event.OperationSwitched(afterSwitch))
        check(ssh.isCurrent(idle) && ssh.terminalIdentity()!==firstIdentity)
        expectText("second pane after exit")
        val secondIdentity=checkNotNull(ssh.terminalIdentity())
        check(ssh.send("close second without status".toByteArray()))
        check(event() == SshSession.Event.TerminalEnded(secondIdentity,null))
        check(ssh.hasIdleTransport())
        ssh.disconnect()
        check(!ssh.hasIdleTransport())
    }
    println("Cold roster, both PTYs, and roster after close reused one pinned SSH connection.")
    println("Loopback SSH integration: unknown/wrong rejected, encrypted Ed25519 key, non-PTY listing/isolated stderr and output/timeout bounds, bidirectional PTY, resize, exit, abrupt transport loss versus channel close, same-transport switch and resize, TUI discovery/occupied-terminal refusal without takeover, negotiated SGR wheel/click and disable, and explicit reattach passed.")
}
