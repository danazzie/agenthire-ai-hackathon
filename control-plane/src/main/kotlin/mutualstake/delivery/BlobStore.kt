package mutualstake.delivery

import mutualstake.crypto.Sha256
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/** Content-addressed, immutable bytes. The database checks each blob's hash against its bytes. */
@Repository
class BlobStore(private val jdbc: JdbcClient) {
    fun put(bytes: ByteArray, mediaType: String): String {
        val sha = Sha256.hex(bytes)
        jdbc.sql("insert into blob (sha256, media_type, size_bytes, bytes) values (:sha, :type, :size, :bytes) on conflict (sha256) do nothing")
            .param("sha", sha).param("type", mediaType).param("size", bytes.size).param("bytes", bytes).update()
        return sha
    }

    fun bytes(sha256: String): ByteArray =
        jdbc.sql("select bytes from blob where sha256 = :sha").param("sha", sha256).query(ByteArray::class.java).single()
}
