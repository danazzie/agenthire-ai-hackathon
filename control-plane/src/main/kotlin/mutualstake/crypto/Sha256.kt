package mutualstake.crypto

import java.security.MessageDigest
import java.util.HexFormat

object Sha256 {
    fun hex(text: String): String = hex(text.toByteArray(Charsets.UTF_8))

    fun hex(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
