package cn.edu.qut.campus.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 Android Keystore 中的 AES/GCM 密钥加密本地敏感信息（目前只有教务密码）。
 *
 * 设计要点：
 * - 密钥硬件/系统级托管，不落盘，root 直接读 SharedPreferences XML 也拿不到明文；
 * - 换机或恢复备份后密钥失效，[decrypt] 返回 null，调用方必须引导用户重新登录，
 *   而不是把损坏的密文当密码去登录；
 * - 任何异常都返回 null，绝不抛给调用方导致崩溃。
 */
object CredentialCipher {

    private const val KEY_ALIAS = "qut_campus_credential_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128
    private const val PREFIX = "enc:v1:"

    private val keyStore: KeyStore? by lazy {
        runCatching { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }.getOrNull()
    }

    private fun secretKey(createIfMissing: Boolean): SecretKey? {
        val store = keyStore ?: return null
        runCatching { store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry }
            .getOrNull()?.let { return it.secretKey }
        if (!createIfMissing) return null
        return runCatching {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
            }.generateKey()
        }.getOrNull()
    }

    /** 加密；失败返回 null（调用方此时应放弃持久化，而不是退回明文） */
    fun encrypt(plain: String): String? {
        if (plain.isEmpty()) return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(createIfMissing = true) ?: return null)
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(cipher.iv + body, Base64.NO_WRAP)
        }.getOrNull()
    }

    /** 解密；无密文返回空串，密钥失效/数据损坏返回 null */
    fun decrypt(stored: String): String? {
        if (stored.isEmpty()) return ""
        if (!stored.startsWith(PREFIX)) return null
        return runCatching {
            val payload = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            if (payload.size <= IV_LENGTH) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(createIfMissing = false) ?: return null,
                GCMParameterSpec(TAG_LENGTH_BITS, payload, 0, IV_LENGTH)
            )
            String(cipher.doFinal(payload, IV_LENGTH, payload.size - IV_LENGTH), Charsets.UTF_8)
        }.getOrNull()
    }
}
