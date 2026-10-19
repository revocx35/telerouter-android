package io.github.revocx35.telerouter.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import io.github.revocx35.telerouter.core.ServerConfig
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts the device token with an AES-GCM key that never leaves the Android Keystore. */
interface Sealer {
    fun seal(plain: String): String
    fun open(sealed: String): String
}

class KeystoreSealer : Sealer {
    private val alias = "telerouter-token"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun seal(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = c.iv + c.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    override fun open(sealed: String): String {
        val raw = Base64.decode(sealed, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        return String(c.doFinal(raw, 12, raw.size - 12))
    }
}

/** The server this phone belongs to, and the app's own settings. */
class Store(context: Context, private val sealer: Sealer = KeystoreSealer()) {
    private val prefs = context.getSharedPreferences("telerouter", Context.MODE_PRIVATE)

    fun server(): ServerConfig? {
        val sha = prefs.getString("ca_sha256", null) ?: return null
        val token = try {
            sealer.open(prefs.getString("token", null) ?: return null)
        } catch (e: Exception) {
            return null                      // e.g. the Keystore key is gone after a restore: set up again
        }
        return ServerConfig(prefs.getString("public", null), prefs.getString("lan", null), sha,
            prefs.getString("ca", null), token, prefs.getString("name", "Phone")!!)
    }

    fun saveServer(cfg: ServerConfig) {
        prefs.edit {
            putString("public", cfg.publicUrl); putString("lan", cfg.lanUrl); putString("ca_sha256", cfg.caSha256)
            putString("ca", cfg.caPem); putString("token", sealer.seal(cfg.token)); putString("name", cfg.name)
        }
    }

    /** The LAN CA, pinned on the phone's first visit home after a setup from away. */
    fun pinCa(pem: String) = prefs.edit { putString("ca", pem) }

    fun forget() {
        prefs.edit { for (k in listOf("public", "lan", "ca_sha256", "ca", "token", "name")) remove(k) }
        wakeWord = false
    }

    var wakeWord: Boolean
        get() = prefs.getBoolean("wake_word", false)
        set(v) = prefs.edit { putBoolean("wake_word", v) }
}
