package app.loop.ime

import java.io.File

/** Only imported model directories owned by Loop are eligible; selected weights stay intact. */
internal class ModelStorage(private val root: File, private val selected: String) {
    private fun imports(): List<File> = root.listFiles().orEmpty().filter {
        it.name.matches(Regex("model-import-[0-9]+")) && it.isDirectory && it.canonicalFile==it.absoluteFile && it.canonicalFile.parentFile==root.canonicalFile
    }
    fun bytes(): Long = imports().sumOf { directory -> directory.walkTopDown().onEnter { it.canonicalFile==it.absoluteFile }.filter { it.isFile && it.canonicalFile==it.absoluteFile }.sumOf { it.length() } }
    fun clearUnused(): Int {
        val active=selected.takeIf { it.isNotBlank() }?.let { File(it).canonicalFile }
        var deleted=0
        imports().filter { it.canonicalFile!=active }.forEach { directory ->
            // Refuse a tree containing links, so cleanup cannot follow a path outside the model.
            check(directory.walkTopDown().onEnter { it.canonicalFile==it.absoluteFile }.all { it.canonicalFile==it.absoluteFile }) { "模型目录包含外部链接，未清理" }
            check(directory.deleteRecursively()) { "模型文件正在使用，请稍后重试" };deleted++
        }
        return deleted
    }
}
