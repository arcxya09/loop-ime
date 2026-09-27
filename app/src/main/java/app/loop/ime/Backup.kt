package app.loop.ime

import org.json.JSONObject
import java.io.*
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Independently authenticated records, bounded memory; the empty final record authenticates EOF. */
object Backup {
    private val magic="LOOPB01\n".toByteArray()
    private fun key(password: CharArray,salt: ByteArray): SecretKeySpec {
        require(password.size>=8) { "备份密码至少 8 位" }
        val spec=PBEKeySpec(password,salt,210000,256)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,"AES") } finally { spec.clearPassword() }
    }
    private fun crypt(mode: Int, key: SecretKeySpec, header: ByteArray,index: Int, bytes: ByteArray): ByteArray {
        val suffix=ByteBuffer.allocate(4).putInt(index).array()
        val nonce=header.copyOfRange(24,32)+suffix
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode,key,GCMParameterSpec(128,nonce));updateAAD(header+suffix);doFinal(bytes)
        }
    }
    fun export(store: PersonalStore,output: OutputStream,password: CharArray) {
        val random=SecureRandom();val salt=ByteArray(16).also { random.nextBytes(it) };val nonce=ByteArray(8).also { random.nextBytes(it) }
        val header=magic+salt+nonce;val k=key(password,salt)
        DataOutputStream(BufferedOutputStream(output)).use { out ->
            out.write(header);var i=0
            fun frame(bytes: ByteArray) { require(i<Int.MAX_VALUE);val sealed=crypt(Cipher.ENCRYPT_MODE,k,header,i++,bytes);out.writeInt(sealed.size);out.write(sealed) }
            store.exportRows { frame(it.toString().toByteArray()) };frame(byteArrayOf())
        }
    }
    fun restore(store: PersonalStore,input: InputStream,password: CharArray,preview: Boolean=false): PersonalStore.ImportResult {
        DataInputStream(BufferedInputStream(input)).use { source ->
            val header=ByteArray(32);source.readFully(header);require(header.copyOfRange(0,8).contentEquals(magic)) { "不是 Loop 加密备份" }
            val k=key(password,header.copyOfRange(8,24))
            val rows=sequence {
                var i=0
                while(true) {
                    require(i<10000000) { "备份记录数量超限" }
                    val n=source.readInt();require(n in 16..2000000) { "备份记录损坏" }
                    val bytes=ByteArray(n);source.readFully(bytes)
                    val plain=crypt(Cipher.DECRYPT_MODE,k,header,i++,bytes)
                    if(plain.isEmpty()) { require(source.read()==-1) { "备份包含多余数据" };break }
                    yield(JSONObject(plain.toString(Charsets.UTF_8)))
                }
            }
            return store.importRows(rows,preview) // Authentication failure, truncation or preview rolls back the entire import.
        }
    }
}
