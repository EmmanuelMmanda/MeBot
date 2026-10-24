package app.mynguli.feed.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A single authenticated, atomic record prevents mixing a rotated refresh token with old metadata. */
internal class EncryptedChatGptStore(context: Context) : ChatGptCredentialStore {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "chatgpt-session.v1"))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val alias = "mebot.chatgpt.v1"
    private val aad = "MeBot.ChatGPT.Session.v1".toByteArray(Charsets.UTF_8)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    @Synchronized override fun read(): ChatGptPrivateState? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        try {
            val bytes = file.openRead().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    if (output.size() + size > MAX_BYTES) throw ChatGptAuthException("chatgpt_storage")
                    output.write(buffer, 0, size)
                }
                output.toByteArray()
            }
            if (bytes.size < 33 || bytes[0] != 1.toByte()) throw ChatGptAuthException("chatgpt_storage")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            cipher.updateAAD(aad)
            val plain = cipher.doFinal(bytes.copyOfRange(13, bytes.size))
            try { return json.decodeFromString<ChatGptPrivateState>(plain.toString(Charsets.UTF_8)) }
            finally { plain.fill(0) }
        } catch (error: ChatGptAuthException) { throw error }
        catch (_: Exception) { throw ChatGptAuthException("chatgpt_storage") }
    }

    @Synchronized override fun write(state: ChatGptPrivateState) {
        try {
            val plain = json.encodeToString(state).toByteArray(Charsets.UTF_8)
            if (plain.size > MAX_BYTES - 32) throw ChatGptAuthException("chatgpt_storage")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key()); updateAAD(aad)
            }
            val encrypted = try { cipher.doFinal(plain) } finally { plain.fill(0) }
            val bytes = ByteBuffer.allocate(1 + cipher.iv.size + encrypted.size)
                .put(1.toByte()).put(cipher.iv).put(encrypted).array()
            val output = file.startWrite()
            try { output.write(bytes); file.finishWrite(output) }
            catch (error: Exception) { file.failWrite(output); throw error }
        } catch (error: ChatGptAuthException) { throw error }
        catch (_: Exception) { throw ChatGptAuthException("chatgpt_storage") }
    }

    @Synchronized override fun clear() {
        file.delete()
        if (listOf(file.baseFile, File(file.baseFile.path + ".bak"), File(file.baseFile.path + ".new")).any { it.exists() })
            throw ChatGptAuthException("chatgpt_storage")
    }

    companion object { private const val MAX_BYTES = 2 * 1024 * 1024 }
}
