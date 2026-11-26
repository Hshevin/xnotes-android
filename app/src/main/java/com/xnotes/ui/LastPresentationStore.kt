package com.xnotes.ui

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal data class LastPresentationObservation(val read: LastPresentationRead)

internal class LastPresentationStore(private val filesDir: File) {
    private val file = File(filesDir, "last-presentation.json")

    fun observe() = LastPresentationObservation(try {
        if (!file.exists()) LastPresentationRead.Absent
        else file.inputStream().use { LastPresentationCodec.read(it) }?.let { LastPresentationRead.Ready(it) }
            ?: LastPresentationRead.Invalid
    } catch (_: Exception) { LastPresentationRead.Invalid })

    fun write(value: LastPresentation, current: () -> Boolean = { true }): Boolean {
        val temp = try { File.createTempFile("presentation-", ".tmp", filesDir) } catch (_: java.io.IOException) { return false }
        try {
            temp.writeBytes(LastPresentationCodec.encode(value))
            synchronized(file.canonicalPath.intern()) {
                if (!current()) return false
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
            return true
        } catch (_: Exception) { return false }
        finally { temp.delete() }
    }
}
