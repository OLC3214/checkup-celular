package br.com.checkupcelular

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import java.io.File
import java.util.Locale

/** junk = pode apagar com segurança. suspicious = instalador APK perigoso. big = só informação (não apaga). */
data class FileItem(val file: File, val kind: String, val size: Long, val junk: Boolean, val suspicious: Boolean = false)

object Cleaner {

    private val TEMP_EXT = setOf("tmp", "temp", "log", "bak", "crdownload", "part", "dmp")
    private const val BIG = 200L * 1024 * 1024

    /**
     * Rápida: só a pasta Download e a raiz da memória.
     * Completa: memória inteira (até 8 níveis), analisa cada APK por dentro, acha pastas vazias e arquivos grandes.
     */
    fun scan(ctx: Context, deep: Boolean, progress: (String) -> Unit): List<FileItem> {
        val root = Environment.getExternalStorageDirectory()
        val out = mutableListOf<FileItem>()
        if (deep) {
            walk(ctx, root, 0, 8, true, out, progress)
        } else {
            walk(ctx, root, 0, 0, false, out, progress)
            walk(ctx, File(root, Environment.DIRECTORY_DOWNLOADS), 0, 2, false, out, progress)
        }
        return out.distinctBy { it.file.absolutePath }
            .sortedWith(compareByDescending<FileItem> { it.suspicious }.thenByDescending { it.size })
    }

    private var counter = 0

    private fun walk(
        ctx: Context, dir: File, depth: Int, maxDepth: Int, deep: Boolean,
        out: MutableList<FileItem>, progress: (String) -> Unit
    ) {
        val children = dir.listFiles() ?: return
        // Pastas padrão do Android (DCIM, Documents...) ficam na raiz: nunca sugerir apagar
        if (deep && depth >= 2 && children.isEmpty()) {
            out += FileItem(dir, "Pasta vazia", 0, junk = true)
            return
        }
        for (f in children) {
            // Nunca mexe na pasta Android (dados dos apps)
            if (depth == 0 && f.name == "Android") continue
            if (++counter % 200 == 0) progress("Verificando arquivos: ${f.parentFile?.name ?: ""}")

            if (f.isDirectory) {
                if (f.name.equals(".thumbnails", ignoreCase = true)) {
                    val s = sizeOf(f)
                    if (s > 0) out += FileItem(f, "Miniaturas (o sistema recria)", s, junk = true)
                } else if (depth < maxDepth) {
                    walk(ctx, f, depth + 1, maxDepth, deep, out, progress)
                }
                continue
            }

            val ext = f.extension.lowercase(Locale.ROOT)
            when {
                ext == "apk" -> {
                    val danger = if (deep) analyzeApk(ctx, f) else null
                    if (danger != null) out += FileItem(f, "⚠️ Instalador suspeito: $danger", f.length(), junk = true, suspicious = true)
                    else out += FileItem(f, "Instalador APK esquecido", f.length(), junk = true)
                }
                ext in TEMP_EXT -> out += FileItem(f, "Arquivo temporário", f.length(), junk = true)
                deep && f.length() >= BIG -> out += FileItem(f, "Arquivo grande (só aviso, não será apagado)", f.length(), junk = false)
            }
        }
    }

    /** Abre o APK por dentro (sem instalar) e diz se ele pede coisas perigosas. */
    private fun analyzeApk(ctx: Context, f: File): String? = try {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageArchiveInfo(f.absolutePath, PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES)
        if (info == null) null else {
            val perms = info.requestedPermissions?.toSet() ?: emptySet()
            val accessibility = info.services?.any { it.permission == Manifest.permission.BIND_ACCESSIBILITY_SERVICE } ?: false
            val ai = info.applicationInfo
            val label = if (ai != null) {
                ai.sourceDir = f.absolutePath; ai.publicSourceDir = f.absolutePath
                ai.loadLabel(pm).toString()
            } else info.packageName
            val fake = Scanner.FAMOUS_NAMES.any { label.lowercase(Locale.ROOT).contains(it) }
            val reasons = mutableListOf<String>()
            if (accessibility) reasons += "quer controlar a tela"
            if (Manifest.permission.READ_SMS in perms || Manifest.permission.RECEIVE_SMS in perms) reasons += "lê SMS"
            // "instala outros apps" sozinho é comum (lojas, atualizadores); só pesa junto com outro sinal
            if (reasons.isNotEmpty() && Manifest.permission.REQUEST_INSTALL_PACKAGES in perms) reasons += "instala outros apps"
            if (fake && reasons.isNotEmpty()) reasons += "se chama \"$label\""
            if (reasons.isEmpty()) null else reasons.joinToString(", ")
        }
    } catch (e: Exception) { null }

    private fun sizeOf(f: File): Long =
        if (f.isFile) f.length() else (f.listFiles()?.sumOf { sizeOf(it) } ?: 0L)

    fun delete(items: List<FileItem>): Long {
        var freed = 0L
        for (i in items) if (i.junk && i.file.deleteRecursively()) freed += i.size
        return freed
    }
}
