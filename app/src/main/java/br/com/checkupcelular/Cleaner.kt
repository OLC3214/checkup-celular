package br.com.checkupcelular

import android.os.Environment
import java.io.File

data class JunkItem(val file: File, val kind: String, val size: Long)

object Cleaner {

    private val TEMP_EXT = setOf("tmp", "temp", "log", "bak", "crdownload", "part")

    /** Procura lixo na memória interna: APKs esquecidos, pastas de miniaturas e arquivos temporários. */
    fun scan(): List<JunkItem> {
        val root = Environment.getExternalStorageDirectory()
        val out = mutableListOf<JunkItem>()
        walk(root, 0, out)
        return out.sortedByDescending { it.size }
    }

    private fun walk(dir: File, depth: Int, out: MutableList<JunkItem>) {
        if (depth > 4) return
        val children = dir.listFiles() ?: return
        for (f in children) {
            // Nunca mexe na pasta Android (dados dos apps) nem nas fotos/vídeos em si
            if (depth == 0 && f.name == "Android") continue
            if (f.isDirectory) {
                if (f.name == ".thumbnails" || f.name == ".Thumbnails") {
                    val s = sizeOf(f)
                    if (s > 0) out += JunkItem(f, "Miniaturas (o sistema recria)", s)
                } else {
                    walk(f, depth + 1, out)
                }
            } else {
                val ext = f.extension.lowercase()
                when {
                    ext == "apk" -> out += JunkItem(f, "Instalador APK esquecido", f.length())
                    ext in TEMP_EXT -> out += JunkItem(f, "Arquivo temporário", f.length())
                }
            }
        }
    }

    private fun sizeOf(f: File): Long =
        if (f.isFile) f.length() else (f.listFiles()?.sumOf { sizeOf(it) } ?: 0L)

    fun delete(items: List<JunkItem>): Long {
        var freed = 0L
        for (i in items) if (i.file.deleteRecursively()) freed += i.size
        return freed
    }
}
