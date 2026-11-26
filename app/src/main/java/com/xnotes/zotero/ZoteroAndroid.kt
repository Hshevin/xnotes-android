package com.xnotes.zotero

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** [ZoteroHttp] over HttpURLConnection: GET, `instanceFollowRedirects = false`, 20 s connect / 60 s read timeouts; IOException → [ZoteroFailure.NETWORK]. */
class UrlConnectionZoteroHttp : ZoteroHttp {
    override fun get(url: String, headers: Map<String, String>): ZoteroResponse {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 20_000
                connection.readTimeout = 60_000
                headers.forEach(connection::setRequestProperty)
                val status = connection.responseCode
                val responseHeaders = connection.headerFields.mapNotNull { (name, values) ->
                    name?.lowercase()?.let { it to values }
                }.toMap()
                val stream = if (status < 400) connection.inputStream else connection.errorStream
                val body = stream ?: ByteArray(0).inputStream()
                return ZoteroResponse(status, responseHeaders, body) {
                    try { body.close() } finally { connection.disconnect() }
                }
            } catch (e: Exception) {
                connection.disconnect()
                throw e
            }
        } catch (e: IOException) {
            throw ZoteroException(ZoteroFailure.NETWORK, cause = e)
        }
    }
}

/** [ZoteroConfig] encrypted with an AndroidKeyStore AES/GCM key (alias `zotero`) in `noBackupFilesDir/zotero.config`, written atomically, like `AiConfigStore`. */
class ZoteroConfigStore(context: android.content.Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "zotero.config"))

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("zotero", null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder("zotero", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }

    /** Null when nothing is saved or the file cannot be decrypted or decoded (that file is left in place). */
    fun load(): ZoteroConfig? = try {
        val bytes = file.readFully()
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOf(12)))
        ZoteroConfigCodec.decode(cipher.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8))
    } catch (_: Exception) {
        null
    }

    fun save(config: ZoteroConfig) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        require(cipher.iv.size == 12)
        val bytes = cipher.iv + cipher.doFinal(ZoteroConfigCodec.encode(config).toByteArray())
        val out = file.startWrite()
        try {
            out.write(bytes)
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    fun clear() = file.delete()
}
