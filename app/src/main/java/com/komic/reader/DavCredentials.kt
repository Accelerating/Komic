package com.komic.reader

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the reusable Basic authorization value encrypted with a device-local key. */
internal class DavCredentials(context: Context) {
    private val prefs = context.getSharedPreferences("dav_credentials", Context.MODE_PRIVATE)

    fun load(server: Server): String? {
        val encoded = prefs.getString(server.id, null) ?: return null
        return try {
            val (iv, ciphertext) = encoded.split('.', limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, decode(iv)))
            cipher.updateAAD(binding(server))
            cipher.doFinal(decode(ciphertext)).toString(Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            forget(server)
            null
        } catch (_: IllegalArgumentException) {
            forget(server)
            null
        } catch (_: IndexOutOfBoundsException) {
            forget(server)
            null
        }
    }

    fun save(server: Server, authorization: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(binding(server))
        val ciphertext = cipher.doFinal(authorization.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(server.id, encode(cipher.iv) + "." + encode(ciphertext)).apply()
    }

    fun forget(server: Server) = prefs.edit().remove(server.id).apply()

    private fun binding(server: Server) = (server.id + "|" + server.address).toByteArray(Charsets.UTF_8)
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)

    companion object {
        private const val alias = "komic.webdav.credentials.v1"
        @Synchronized
        private fun key(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(alias, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
    }
}
