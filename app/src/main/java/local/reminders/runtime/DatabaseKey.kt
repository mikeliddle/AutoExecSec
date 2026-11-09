package local.reminders.runtime

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object DatabaseKey {
    private const val ALIAS = "local.reminders.database.wrapper.v1"
    @Synchronized fun load(context: Context): ByteArray {
        val wrapped = AtomicFile(File(context.noBackupFilesDir, "database-key.v1"))
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        // AtomicFile.openRead also restores an interrupted write's backup.
        val exists = wrapped.baseFile.exists() || File(wrapped.baseFile.path + ".bak").exists()
        if (exists) {
            check(keys.containsAlias(ALIAS)) { "Database key unavailable. Existing data was not reset." }
            val wrapper = keys.getKey(ALIAS, null) as SecretKey
            val bytes = wrapped.openRead().use { it.readBytes() }
            return DataInputStream(ByteArrayInputStream(bytes)).use {
                check(it.readInt() == 1) { "Unsupported database key format." }
                val length = it.readInt()
                check(length == 12) { "Invalid database key format." }
                val iv = ByteArray(length).also(it::readFully)
                check(it.available() == 48) { "Invalid database key format." }
                val encrypted = ByteArray(it.available()).also(it::readFully)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, wrapper, GCMParameterSpec(128, iv))
                cipher.updateAAD(ALIAS.toByteArray(Charsets.UTF_8))
                cipher.doFinal(encrypted).also { key -> check(key.size == 32) { "Invalid database key." } }
            }
        }
        check(!context.getDatabasePath(RuntimeDatabase.NAME).exists()) {
            "Encrypted database key is missing. Existing data was not reset."
        }
        val wrapper = if (keys.containsAlias(ALIAS)) keys.getKey(ALIAS, null) as SecretKey else {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .build())
            }.generateKey()
        }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrapper)
        cipher.updateAAD(ALIAS.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(key)
        val bytes = ByteArrayOutputStream().also { stream ->
            DataOutputStream(stream).use {
                it.writeInt(1); it.writeInt(cipher.iv.size); it.write(cipher.iv); it.write(encrypted)
            }
        }.toByteArray()
        val stream = wrapped.startWrite()
        try {
            stream.write(bytes)
            wrapped.finishWrite(stream)
        } catch (error: Exception) {
            wrapped.failWrite(stream)
            key.fill(0)
            throw error
        }
        return key
    }
}
