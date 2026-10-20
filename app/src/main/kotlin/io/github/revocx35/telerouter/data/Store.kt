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

/**
 * The address you sign in with, this phone's device token (encrypted), and the app's own settings. The dashboard's
 * sign-in itself (the proxy's and telerouter's cookies) lives in the WebView's cookie store.
 */
class Store(context: Context, private val sealer: Sealer = KeystoreSealer()) {
    private val prefs = context.getSharedPreferences("telerouter", Context.MODE_PRIVATE)

    init {
        // 0.1.x: set up by a link with a LAN address and a pinned CA. The token stays (same device in telerouter);
        // the address is asked for again, as the one you sign in with.
        if (prefs.contains("ca_sha256")) prefs.edit { for (k in listOf("public", "lan", "ca_sha256", "ca")) remove(k) }
    }

    var baseUrl: String?
        get() = prefs.getString("base", null)
        set(v) = prefs.edit { putString("base", v) }

    /** The device this phone is in telerouter, or null until it added itself (after the first sign-in). */
    fun server(): ServerConfig? {
        val base = baseUrl ?: return null
        val token = try {
            sealer.open(prefs.getString("token", null) ?: return null)
        } catch (e: Exception) {
            return null                      // e.g. the Keystore key is gone after a restore: added again on sign-in
        }
        return ServerConfig(base, token, prefs.getString("client_id", "")!!, prefs.getString("name", "Phone")!!)
    }

    /** Cheap check (no decryption): has the phone been added? */
    val hasDevice get() = baseUrl != null && prefs.contains("token")

    fun saveServer(cfg: ServerConfig) {
        prefs.edit {
            putString("base", cfg.baseUrl); putString("token", sealer.seal(cfg.token))
            putString("client_id", cfg.clientId); putString("name", cfg.name)
        }
    }

    /** telerouter told us which device we are (0.1.x tokens didn't know). */
    fun device(clientId: String, name: String) = prefs.edit {
        if (clientId.isNotEmpty()) putString("client_id", clientId)
        if (name.isNotEmpty()) putString("name", name)
    }

    /** The token no longer works (the device was removed): the app adds the phone again on the next sign-in. */
    fun dropDevice() = prefs.edit { for (k in listOf("token", "client_id", "name")) remove(k) }

    /** Sign out: the device is gone; [everything] also forgets the address. */
    fun forget(everything: Boolean = false) {
        dropDevice()
        if (everything) prefs.edit { remove("base") }
        wakeWord = false
    }

    var wakeWord: Boolean
        get() = prefs.getBoolean("wake_word", false)
        set(v) = prefs.edit { putBoolean("wake_word", v) }

    /** Jarvis speaks its answers (telerouter's voice); off: text only. */
    var spokenAnswers: Boolean
        get() = prefs.getBoolean("spoken_answers", true)
        set(v) = prefs.edit { putBoolean("spoken_answers", v) }

    /** "Hey Jarvis" turns the screen on and shows the conversation (over the lock screen). */
    var wakeScreen: Boolean
        get() = prefs.getBoolean("wake_screen", true)
        set(v) = prefs.edit { putBoolean("wake_screen", v) }
}
