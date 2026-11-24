package com.xnotes.storage.lite

import com.xnotes.kio.FileIdentity
import com.xnotes.kio.Uuids
import com.xnotes.kio.atomicWrite
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

data class LiteRecovery(val original: Path, val documentId: String, val record: Path)

internal data class Stamp(val identity: FileIdentity, val hash: String)
internal data class RecoveryEntry(val recovery: LiteRecovery, val stamp: Stamp, val body: Path, val hash: String)
internal data class Protected(val body: Path, val recovery: LiteRecovery?, val problem: Exception? = null)

internal fun Path.recoveryBody(kind: LiteKind) = this / "body.${kind.extension}"

internal class LiteRecoveryFiles(private val fs: FileSystem, private val root: Path, private val documents: Path) {
    fun write(path: Path, id: String, stamp: Stamp, writeBody: (Path) -> String, verifyBody: (Path, String) -> Unit): Protected {
        fs.createDirectories(root)
        val token = Uuids.random()
        val stage = root / "unclaimed-$token"
        val ready = root / token
        val kind = requireNotNull(LiteKind.of(path))
        fs.createDirectory(stage, mustCreate = true)
        var written = false
        try {
            val file = stage.recoveryBody(kind)
            val hash = writeBody(file)
            written = true
            try {
                verifyBody(file, hash)
                atomicWrite(fs, stage / "record") { out ->
                    out.writeUtf8(MAGIC)
                    out.writeInt(VERSION)
                    out.field(path.relativeTo(documents).toString())
                    out.field(id)
                    out.writeLong(stamp.identity.device).writeLong(stamp.identity.inode)
                    out.field(stamp.hash)
                    out.field(hash)
                }
                fs.atomicMove(stage, ready)
            } catch (failure: Exception) {
                return Protected(file, null, failure)
            }
            return Protected(ready.recoveryBody(kind), LiteRecovery(path, id, ready / "record"))
        } finally {
            if (!written) fs.deleteRecursively(stage)
        }
    }

    fun pending(requireComplete: Boolean = false): List<LiteRecovery> {
        val directories = if (requireComplete) {
            if (fs.metadataOrNull(root) == null) return emptyList()
            fs.list(root)
        } else fs.listOrNull(root).orEmpty()
        return directories.filter { Uuids.isValid(it.name) }.mapNotNull {
            try { read(it / "record").recovery } catch (failure: Exception) {
                if (requireComplete) throw failure else null
            }
        }
    }

    fun lookup(recovery: LiteRecovery): RecoveryEntry = read(recovery.record).also { require(it.recovery == recovery) }

    fun discard(recovery: LiteRecovery) {
        val dir = lookup(recovery).body.parent!!
        fs.deleteRecursively(dir)
    }

    private fun read(record: Path): RecoveryEntry {
        val dir = requireNotNull(record.parent)
        require(record.name == "record" && dir.parent == root && Uuids.isValid(dir.name))
        require(fs.metadata(dir).isDirectory && fs.metadata(record).isRegularFile)
        return fs.read(record) {
            require(readUtf8(MAGIC.length.toLong()) == MAGIC)
            val version = readInt()
            if (version > VERSION) throw FutureVersion()
            require(version == VERSION)
            val relative = field().toPath()
            require(relative.isRelative && relative.segments.isNotEmpty() && relative.segments.none { it.startsWith('.') })
            val path = documents / relative
            val kind = requireNotNull(LiteKind.of(path))
            val id = field().also { require(Uuids.isValid(it)) }
            val identity = FileIdentity(readLong(), readLong())
            val stamp = Stamp(identity, digestField())
            val hash = digestField()
            require(exhausted())
            val body = dir.recoveryBody(kind)
            require(fs.metadata(body).isRegularFile)
            RecoveryEntry(LiteRecovery(path, id, record), stamp, body, hash)
        }
    }

    private fun BufferedSink.field(value: String) {
        val bytes = value.encodeToByteArray()
        require(bytes.size in 1..8192)
        writeInt(bytes.size).write(bytes)
    }

    private fun BufferedSource.field(): String {
        val size = readInt()
        require(size in 1..8192)
        return readByteArray(size.toLong()).decodeToString(throwOnInvalidSequence = true)
    }

    private fun BufferedSource.digestField() = field().also { require(it.length == 64 && it.all { ch -> ch in "0123456789abcdef" }) }

    private companion object {
        const val MAGIC = "XNotesLite\n"
        const val VERSION = 1
    }
}
