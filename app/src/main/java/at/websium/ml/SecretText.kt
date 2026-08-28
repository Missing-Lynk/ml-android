package at.websium.ml

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Text held at rest under a key the app never sees. Used for the destination set, whose URLs
 * carry stream keys: a credential that lets a stranger broadcast to the user's channel.
 *
 * The key lives in the Android Keystore under [alias], is generated on first use, and is backed
 * by hardware where the device offers it. Only this process can ask the Keystore to use it, so a
 * copy of the preferences file taken off the device carries nothing usable.
 *
 * AES-GCM, which authenticates as well as encrypts, so text altered in storage fails to decrypt
 * rather than decoding to something else. Each [encrypt] takes a fresh initialisation vector,
 * which is stored beside the ciphertext because decryption needs it and it is not a secret.
 */
class SecretText(private val alias: String) {

    /** [plain] as a string safe to keep in shared preferences. */
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyForAlias())

        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return encode(cipher.iv) + SEPARATOR + encode(ciphertext)
    }

    /**
     * The text [stored] was made from, or null when it cannot be read back: the value is
     * malformed, or the key that made it is gone, which is what a restore onto another device
     * leaves behind. A caller holding credentials treats null as "the user must paste them
     * again", since nothing can recover them.
     */
    fun decrypt(stored: String): String? {
        val parts = stored.split(SEPARATOR)
        if (parts.size != 2) {
            return null
        }

        return runCatching {
            val initialisationVector = decode(parts[0])
            val ciphertext = decode(parts[1])

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                keyForAlias(),
                GCMParameterSpec(TAG_BITS, initialisationVector),
            )

            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun keyForAlias(): SecretKey {
        val keystore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = keystore.getKey(alias, null) as? SecretKey
        if (existing != null) {
            return existing
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build()
        )

        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128

        /** ':' appears in no base64 alphabet, so it splits the two fields unambiguously */
        const val SEPARATOR = ":"

        fun encode(bytes: ByteArray): String {
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        }

        fun decode(text: String): ByteArray {
            return Base64.decode(text, Base64.NO_WRAP)
        }
    }
}
