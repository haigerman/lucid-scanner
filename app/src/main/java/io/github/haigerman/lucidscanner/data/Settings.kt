package io.github.haigerman.lucidscanner.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.haigerman.lucidscanner.cloud.OpenCloudClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Config(
    val serverUrl: String = "",
    val user: String = "",
    val token: String = "",
    val folder: String = "Scans",
    val enhance: Boolean = true,
) {
    /** Alles da, um in OpenCloud abzulegen? */
    val complete: Boolean
        get() = OpenCloudClient.parseServerUrl(serverUrl) != null && user.isNotBlank() && token.isNotBlank()

    /** Kurzbeschreibung des Ablageorts, z. B. „cloud.example.com › Scans“. */
    val destination: String
        get() {
            val host = OpenCloudClient.parseServerUrl(serverUrl)?.host ?: return ""
            val path = OpenCloudClient.splitFolder(folder).joinToString(" › ")
            return if (path.isEmpty()) host else "$host › $path"
        }
}

/** Einstellungen der App. Der App-Token wird mit einem Schlüssel aus dem Android-Keystore verschlüsselt abgelegt. */
object Settings {
    private lateinit var prefs: SharedPreferences
    private val _config = MutableStateFlow(Config())
    val config: StateFlow<Config> = _config

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _config.value = Config(
            serverUrl = prefs.getString("serverUrl", "") ?: "",
            user = prefs.getString("user", "") ?: "",
            token = prefs.getString("token", null)?.let(Secrets::decrypt) ?: "",
            folder = prefs.getString("folder", "Scans") ?: "Scans",
            enhance = prefs.getBoolean("enhance", true),
        )
    }

    fun update(config: Config) {
        val old = _config.value
        if (config == old) return
        _config.value = config
        prefs.edit().apply {
            putString("serverUrl", config.serverUrl)
            putString("user", config.user)
            putString("folder", config.folder)
            putBoolean("enhance", config.enhance)
            if (config.token != old.token) {
                if (config.token.isEmpty()) remove("token") else putString("token", Secrets.encrypt(config.token))
            }
        }.apply()
    }
}

private object Secrets {
    private const val ALIAS = "lucid_scanner_token"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + data, Base64.NO_WRAP)
    } catch (_: Exception) {
        null
    }

    /** null, wenn sich der Token nicht mehr entschlüsseln lässt (z. B. nach einer Neuinstallation). */
    fun decrypt(stored: String): String? = try {
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        val ivLength = raw[0].toInt()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 1, ivLength))
        String(cipher.doFinal(raw, 1 + ivLength, raw.size - 1 - ivLength), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }
}
