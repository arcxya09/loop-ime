package app.loop.ime

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ModelStorageTest {
    @Test fun cleanupPreservesSelectedModelAndUnrelatedFiles() {
        val root=Files.createTempDirectory("model-cleanup").toFile()
        try {
            val active=java.io.File(root,"model-import-1").apply { mkdirs() }
            java.io.File(active,"encoder.onnx").writeBytes(ByteArray(12))
            val old=java.io.File(root,"model-import-2").apply { mkdirs() }
            java.io.File(old,"encoder.onnx").writeBytes(ByteArray(7))
            val unrelated=java.io.File(root,"database").apply { mkdirs() }
            val storage=ModelStorage(root,active.path)
            assertEquals(19L,storage.bytes());assertEquals(1,storage.clearUnused())
            assertTrue(active.exists());assertTrue(unrelated.exists());assertFalse(old.exists());assertEquals(12L,storage.bytes())
        } finally { root.deleteRecursively() }
    }
}
