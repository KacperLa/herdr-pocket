package dev.kaspy.herdr

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InterruptedIOException
import java.io.InputStream
import java.util.UUID
import java.nio.ByteBuffer

internal sealed interface SftpRead {
    data class Folder(val root: String, val path: String, val entries: List<Entry>) : SftpRead
    data class Entry(val name: String, val directory: Boolean, val regular: Boolean, val identity:SftpFileIdentity?=null)
    data class Preview(val text: String, val truncated: Boolean) : SftpRead
    data class Image(val bytes: ByteArray) : SftpRead
    data object Unrenderable : SftpRead
    data class Failure(val message: String) : SftpRead
}

internal data class SftpFileIdentity(val size:Long,val modifiedSeconds:Int)

internal sealed interface SftpMkdir {
    data object Success : SftpMkdir
    data class Failure(val message:String) : SftpMkdir
}

internal sealed interface SftpDelete {
    data object Success : SftpDelete
    data class Failure(val message:String) : SftpDelete
}

internal sealed interface SftpDownload {
    data class Success(val bytes: Long) : SftpDownload
    data class Failure(val message: String) : SftpDownload
}

internal sealed interface SftpUpload {
    data class Success(val bytes: Long) : SftpUpload
    data class Failure(val message: String) : SftpUpload
}

internal data class SftpUploadStage(val root:String,val folder:List<String>,val directory:String,val payload:String)

internal class AgentSftp(private val channel: ChannelSftp) {
    fun read(root: String, parts: List<String>, preview: Boolean, canonical: Boolean): SftpRead {
        require(!preview || parts.isNotEmpty())
        val (base, physical) = resolve(root, parts, canonical)
        val literal = AgentFiles.literal(physical)
        val attributes = channel.lstat(literal)
        if (preview) {
            if (!attributes.isReg) throw Unavailable()
            val bytes = channel.get(literal).buffered().use { input ->
                val prefix = input.readNBytes(16)
                val image = knownRasterHeader(prefix)
                if (image && attributes.size !in 1..IMAGE_LIMIT.toLong()) return SftpRead.Unrenderable
                val limit = if (image) IMAGE_LIMIT else PREVIEW_LIMIT
                val out = ByteArrayOutputStream()
                out.write(prefix)
                val buffer = ByteArray(8192)
                while (out.size() <= limit) {
                    val n = input.read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
                    if (n <= 0) break
                    out.write(buffer, 0, n)
                }
                val sample = out.toByteArray()
                if (image) return if (sample.size <= limit && sample.size.toLong() == attributes.size)
                    SftpRead.Image(sample) else SftpRead.Unrenderable
                sample
            }
            val truncated = bytes.size > PREVIEW_LIMIT
            val length = minOf(bytes.size, PREVIEW_LIMIT)
            // Decode UTF-8 strictly. A bounded sample may end during a multibyte character.
            val decoded = if (bytes.any { it == 0.toByte() }) null else
                (0..if (truncated) 3 else 0).firstNotNullOfOrNull { trim ->
                    runCatching { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, 0, length - trim)).toString() }.getOrNull()
                }
            if (decoded == null || decoded.any { it.isISOControl() && it !in "\t\r\n" }) {
                return SftpRead.Unrenderable
            }
            return SftpRead.Preview(decoded, truncated)
        }

        if (!attributes.isDir) throw Unavailable()
        val items = ArrayList<SftpRead.Entry>()
        var seen = 0
        channel.ls(literal) { entry ->
            seen++
            if (seen > MAX_ENTRIES) return@ls ChannelSftp.LsEntrySelector.BREAK
            val name = entry.filename
            if (AgentFiles.entryName(name)) {
                val attrs=entry.attrs
                val identity=attrs.takeIf {(it.isReg || it.isDir) && !it.isLink}
                    ?.let {SftpFileIdentity(it.size,it.getMTime())}
                items.add(SftpRead.Entry(name, attrs.isDir, attrs.isReg && !attrs.isLink, identity))
            }
            ChannelSftp.LsEntrySelector.CONTINUE
        }
        if (seen > MAX_ENTRIES) return SftpRead.Failure("This folder has too many entries to display.")
        return SftpRead.Folder(base, physical, items.sortedWith(
            compareByDescending<SftpRead.Entry> { it.directory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.name }
        ))
    }

    fun download(root: String, parts: List<String>, destination: File, stillCurrent: () -> Boolean): SftpDownload {
        require(parts.isNotEmpty())
        val (_, physical) = resolve(root, parts, canonical = true)
        val literal = AgentFiles.literal(physical)
        val attributes = channel.lstat(literal)
        if (!attributes.isReg) throw Unavailable()
        if (attributes.size !in 0..DOWNLOAD_LIMIT) return SftpDownload.Failure(DOWNLOAD_LIMIT_MESSAGE)
        var written = 0L
        channel.get(literal).use { input ->
            destination.outputStream().buffered().use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    if (!stillCurrent()) throw InterruptedIOException()
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (!stillCurrent()) throw InterruptedIOException()
                    if (n == 0) continue
                    if (written + n > DOWNLOAD_LIMIT) return SftpDownload.Failure(DOWNLOAD_LIMIT_MESSAGE)
                    output.write(buffer, 0, n)
                    written += n
                }
            }
        }
        if (!stillCurrent()) throw InterruptedIOException()
        val current = channel.lstat(literal)
        if (!current.isReg || current.size != written || attributes.size != written || attributes.getMTime() != current.getMTime() ||
            channel.realpath(physical) != physical) {
            return SftpDownload.Failure("The file changed during download. Try again.")
        }
        return SftpDownload.Success(written)
    }

    fun upload(root:String, folder:List<String>, name:String, source:()->InputStream,
        stillCurrent:()->Boolean, stageCreated:(SftpUploadStage)->Unit):SftpUpload {
        require(AgentFiles.entryName(name))
        fun checkCurrent() {if(!stillCurrent()) throw InterruptedIOException()}
        checkCurrent()
        if(channel.getExtension("hardlink@openssh.com")!="1")
            return SftpUpload.Failure("This SFTP server does not support uploads without overwriting.")
        val (_,parent)=resolve(root,folder,canonical=true)
        if(!channel.lstat(AgentFiles.literal(parent)).isDir) throw Unavailable()
        val destination=AgentFiles.literal(AgentFiles.child(parent,name))
        fun exists():Boolean = try {
            channel.lstat(destination)
            true
        } catch(error:SftpException) {
            if(error.id!=ChannelSftp.SSH_FX_NO_SUCH_FILE) throw error
            false
        }
        if(exists()) return SftpUpload.Failure(UPLOAD_EXISTS)
        checkCurrent()
        val directory=AgentFiles.child(parent,".herdr-pocket-upload-${UUID.randomUUID()}")
        val payload=AgentFiles.child(directory,"payload-${UUID.randomUUID()}")
        channel.mkdir(directory) // JSch mkdir takes the raw path, unlike its glob-aware calls.
        stageCreated(SftpUploadStage(root,folder,directory,payload))
        channel.chmod(0b111000000,AgentFiles.literal(directory))
        if(channel.realpath(directory)!=directory) throw Unavailable()
        var written=0L
        source().use {input ->
            checkCurrent()
            channel.put(AgentFiles.literal(payload),ChannelSftp.OVERWRITE).use { output ->
                val buffer=ByteArray(32*1024)
                while(true) {
                    checkCurrent()
                    val size=input.read(buffer)
                    checkCurrent()
                    if(size<0) break
                    if(size==0) throw java.io.IOException("No upload progress")
                    if(written+size>DOWNLOAD_LIMIT) return SftpUpload.Failure("This file is larger than the 128 MiB upload limit.")
                    output.write(buffer,0,size)
                    written+=size
                }
            }
        }
        if(resolve(root,folder,canonical=true).second!=parent || channel.realpath(directory)!=directory ||
            channel.realpath(payload)!=payload) throw Unavailable()
        val staged=channel.lstat(AgentFiles.literal(payload))
        if(!staged.isReg || staged.size!=written) throw Unavailable()
        checkCurrent()
        try {
            // OpenSSH hardlink invokes link(2), which atomically fails if any entry
            // (file, directory, or symlink) already has the destination name.
            // JSch rename is deliberately never used: posix-rename may replace.
            channel.hardlink(AgentFiles.literal(payload),destination)
        } catch(error:SftpException) {
            if(exists()) return SftpUpload.Failure(UPLOAD_EXISTS)
            throw error
        }
        return SftpUpload.Success(written)
    }

    fun cleanupUpload(stage:SftpUploadStage):Boolean = runCatching {
        val (_,parent)=resolve(stage.root,stage.folder,canonical=true)
        if(!stage.directory.startsWith(parent.trimEnd('/')+"/.herdr-pocket-upload-") ||
            channel.realpath(stage.directory)!=stage.directory ||
            !channel.lstat(AgentFiles.literal(stage.directory)).isDir) throw Unavailable()
        try {
            channel.rm(AgentFiles.literal(stage.payload))
        } catch(error:SftpException) {
            if(error.id!=ChannelSftp.SSH_FX_NO_SUCH_FILE) throw error
        }
        channel.rmdir(AgentFiles.literal(stage.directory))
    }.isSuccess

    fun delete(root:String,folder:List<String>,name:String,expected:SftpFileIdentity?,directory:Boolean,
        stillCurrent:()->Boolean,onRemoveSent:()->Unit):SftpDelete {
        require(AgentFiles.entryName(name))
        if(!stillCurrent()) throw InterruptedIOException()
        val (_,parent)=resolve(root,folder,canonical=true)
        if(!channel.lstat(AgentFiles.literal(parent)).isDir) throw Unavailable()
        val target=AgentFiles.literal(AgentFiles.child(parent,name))
        val attributes=channel.lstat(target) // Never resolve the last component through its target.
        val kind=if(directory) "folder" else "file"
        if(attributes.isLink || !(if(directory) attributes.isDir else attributes.isReg))
            return SftpDelete.Failure("The selected $kind is no longer the expected type. Links are not deleted.")
        val identity=SftpFileIdentity(attributes.size,attributes.getMTime())
        if(expected!=null && expected!=identity)
            return SftpDelete.Failure("The $kind changed after it was listed. Refresh and confirm again.")
        if(directory) {
            var nonEmpty=false
            channel.ls(target) { entry ->
                if(entry.filename=="." || entry.filename=="..") ChannelSftp.LsEntrySelector.CONTINUE
                else {
                    nonEmpty=true
                    ChannelSftp.LsEntrySelector.BREAK
                }
            }
            if(nonEmpty) return SftpDelete.Failure("Only empty folders can be deleted. Move or delete its contents first.")
        }
        if(resolve(root,folder,canonical=true).second!=parent) throw Unavailable()
        if(!stillCurrent()) throw InterruptedIOException()
        onRemoveSent()
        // RMDIR is nonrecursive and fails if a file was added during preflight.
        // Neither operation follows the final component; SFTP cannot atomically verify its inode.
        if(directory) channel.rmdir(target) else channel.rm(target)
        return SftpDelete.Success
    }

    fun createFolder(root:String,folder:List<String>,name:String,
        stillCurrent:()->Boolean,beginRequest:()->Unit):SftpMkdir {
        require(AgentFiles.newFolderName(name))
        if(!stillCurrent()) throw InterruptedIOException()
        val (_,parent)=resolve(root,folder,canonical=true)
        if(!channel.lstat(AgentFiles.literal(parent)).isDir) throw Unavailable()
        val destination=AgentFiles.child(parent,name)
        require(AgentFiles.absolute(destination)==destination)
        try {
            channel.lstat(AgentFiles.literal(destination))
            return SftpMkdir.Failure("A file or folder already has that name.")
        } catch(error:SftpException) {
            if(error.id!=ChannelSftp.SSH_FX_NO_SUCH_FILE) throw error
        }
        if(resolve(root,folder,canonical=true).second!=parent) throw Unavailable()
        if(!stillCurrent()) throw InterruptedIOException()
        beginRequest()
        // Unlike lstat/rm, JSch mkdir sends the absolute path as-is. A single
        // server mkdir won't replace an entry created after the preflight.
        channel.mkdir(destination)
        return SftpMkdir.Success
    }

    private fun resolve(root: String, parts: List<String>, canonical: Boolean): Pair<String,String> {
        require(AgentFiles.absolute(root) == root && parts.size <= 64 && parts.all(AgentFiles::entryName))
        val base = AgentFiles.absolute(channel.realpath(root)) ?: throw Unavailable()
        if (canonical && base != root) throw Unavailable()
        val requested = parts.fold(base, AgentFiles::child)
        if (AgentFiles.absolute(requested) != requested) throw Unavailable()
        val physical = AgentFiles.absolute(channel.realpath(requested)) ?: throw Unavailable()
        if (physical != requested || !AgentFiles.contains(base, physical)) throw Unavailable()
        return base to physical
    }

    class Unavailable : Exception()

    companion object {
        private const val PREVIEW_LIMIT = 128 * 1024
        private const val IMAGE_LIMIT = 8 * 1024 * 1024

        internal fun knownRasterHeader(bytes: ByteArray): Boolean {
            fun at(offset: Int, vararg magic: Int): Boolean =
                bytes.size >= offset + magic.size && magic.indices.all { (bytes[offset + it].toInt() and 255) == magic[it] }
            return at(0, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) ||
                at(0, 0xff, 0xd8, 0xff) ||
                at(0, 0x47, 0x49, 0x46, 0x38, 0x37, 0x61) || at(0, 0x47, 0x49, 0x46, 0x38, 0x39, 0x61) ||
                (at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50)) ||
                (at(0, 0x42, 0x4d) && at(6, 0, 0, 0, 0))
        }
        private const val MAX_ENTRIES = 3000
        private const val DOWNLOAD_LIMIT = 128L * 1024 * 1024
        private const val UPLOAD_EXISTS = "A file or folder already has that name. Nothing was overwritten."
        private const val DOWNLOAD_LIMIT_MESSAGE = "This file is larger than the 128 MiB download limit."

        fun failure(error: Exception): SftpRead.Failure = SftpRead.Failure(when {
            error is SftpException && error.id == ChannelSftp.SSH_FX_PERMISSION_DENIED -> "You don’t have permission to read this item."
            error is SftpException && error.id == ChannelSftp.SSH_FX_NO_SUCH_FILE -> "This file or folder no longer exists."
            error is Unavailable -> "This item moved or is not a regular file or folder. Links outside the folder are not opened."
            else -> "Could not read this item over SFTP."
        })
    }
}
