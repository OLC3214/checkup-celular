package br.com.checkupcelular

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Atualização automática pelo GitHub Releases.
 * Toda vez que um código novo sobe, o GitHub gera a versão v1.0.N (N = número da compilação).
 * O app compara N com a própria versão e, se tiver mais nova, baixa e abre o instalador.
 */
object Updater {

    data class Release(val code: Int, val name: String, val apkUrl: String)

    @Suppress("DEPRECATION")
    fun currentCode(ctx: Context): Int = try {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
    } catch (e: Exception) { 0 }

    fun currentName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (e: Exception) { "" }

    /** Devolve a versão mais nova se ela for maior que a instalada; senão null. */
    fun check(ctx: Context): Release? = try {
        val c = URL("https://api.github.com/repos/${Config.REPO}/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "CheckupCelular")
        val body = try { if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null } finally { c.disconnect() }
        if (body == null) null else {
            val j = JSONObject(body)
            val tag = j.getString("tag_name")                       // ex.: v1.0.12
            val code = tag.substringAfterLast('.').toIntOrNull() ?: 0
            val assets = j.getJSONArray("assets")
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) url = a.getString("browser_download_url")
            }
            if (url != null && code > currentCode(ctx)) Release(code, tag, url) else null
        }
    } catch (e: Exception) { null }

    /** Android 8+: precisa autorizar o app a instalar atualizações. */
    fun canInstall(ctx: Context) =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun askInstallPermission(a: Activity) {
        if (Build.VERSION.SDK_INT >= 26) {
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
        }
    }

    /** Baixa o APK novo e, quando terminar, abre a tela de instalação do Android. */
    fun downloadAndInstall(a: Activity, r: Release, onStatus: (String) -> Unit) {
        val dm = a.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val file = "CheckupCelular-${r.name}.apk"
        a.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { java.io.File(it, file).delete() }
        val id = dm.enqueue(
            DownloadManager.Request(Uri.parse(r.apkUrl))
                .setTitle("Check-up Celular ${r.name}")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(a, Environment.DIRECTORY_DOWNLOADS, file)
        )
        onStatus("Baixando atualização...")

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                try { a.unregisterReceiver(this) } catch (_: Exception) { }
                val uri = dm.getUriForDownloadedFile(id)
                if (uri == null) { onStatus("Falha ao baixar. Tente de novo."); return }
                onStatus("Abrindo instalador...")
                a.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else a.registerReceiver(receiver, filter)
    }
}
