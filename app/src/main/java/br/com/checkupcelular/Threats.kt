package br.com.checkupcelular

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Detecção de ameaças CONHECIDAS (não é suspeita, é confirmação):
 *
 * 1. Banco de apps espiões (stalkerware) da Echap
 *    https://github.com/AssoEchap/stalkerware-indicators (licença CC-BY 4.0)
 *    Compara o nome do pacote e a assinatura digital de cada app.
 *    Funciona sem internet (cópia embutida) e se atualiza quando tem internet.
 *
 * 2. VirusTotal (opcional, precisa de chave grátis em virustotal.com)
 *    Calcula o SHA-256 de cada app e pergunta se ~70 antivírus já o identificaram como vírus.
 */
object Threats {

    private const val IOC_URL =
        "https://raw.githubusercontent.com/AssoEchap/stalkerware-indicators/master/ioc.yaml"
    private const val CACHE = "stalkerware-ioc.yaml"

    class Db(val byPackage: Map<String, String>, val byCert: Map<String, String>, val source: String) {
        val size get() = byPackage.values.toSet().size
    }

    @Volatile private var db: Db? = null

    /** Carrega o banco: tenta baixar a versão mais nova; se não der, usa a última baixada ou a embutida no app. */
    fun load(ctx: Context, progress: (String) -> Unit): Db {
        db?.let { return it }
        val cache = File(ctx.filesDir, CACHE)
        var source = "embutido no app"
        progress("Atualizando banco de vírus espiões...")
        try {
            val text = httpGet(IOC_URL, emptyMap(), 8000)
            if (text != null && text.contains("packages:")) {
                cache.writeText(text); source = "atualizado agora"
            }
        } catch (_: Exception) { }
        val text = when {
            cache.exists() -> { if (source != "atualizado agora") source = "última atualização salva"; cache.readText() }
            else -> ctx.assets.open(CACHE).bufferedReader().use { it.readText() }
        }
        return parse(text, source).also { db = it }
    }

    private fun parse(yaml: String, source: String): Db {
        val byPkg = HashMap<String, String>()
        val byCert = HashMap<String, String>()
        var name = ""
        var key = ""
        for (raw in yaml.lineSequence()) {
            val line = raw.trimEnd()
            when {
                line.startsWith("- name:") -> { name = line.removePrefix("- name:").trim(); key = "" }
                line.startsWith("  ") && !line.startsWith("   ") && line.endsWith(":") -> key = line.trim().removeSuffix(":")
                line.startsWith("  - ") -> {
                    val v = line.removePrefix("  - ").trim().trim('\'', '"')
                    if (key == "packages") byPkg[v] = name
                    if (key == "certificates") byCert[v.uppercase().replace(":", "")] = name
                }
            }
        }
        return Db(byPkg, byCert, source)
    }

    /** Devolve o nome da família de app espião, se o app estiver no banco. */
    fun matchStalkerware(ctx: Context, db: Db, pkg: String): String? {
        db.byPackage[pkg]?.let { return it }
        for (sha1 in certSha1(ctx.packageManager, pkg)) db.byCert[sha1]?.let { return it }
        return null
    }

    @Suppress("DEPRECATION")
    private fun certSha1(pm: PackageManager, pkg: String): List<String> = try {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        }
        sigs?.map { hex(MessageDigest.getInstance("SHA-1").digest(it.toByteArray())) } ?: emptyList()
    } catch (e: Exception) { emptyList() }

    // ---------- VirusTotal ----------

    data class VtResult(val malicious: Int, val total: Int, val label: String?)

    fun sha256(file: File): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
        }
        hex(md.digest())
    } catch (e: Exception) { null }

    /** null = VirusTotal não conhece o arquivo (ou erro). Chave grátis: 4 consultas por minuto. */
    fun virusTotal(apiKey: String, sha256: String): VtResult? = try {
        val body = httpGet("https://www.virustotal.com/api/v3/files/${sha256.lowercase()}", mapOf("x-apikey" to apiKey), 15000)
        if (body == null) null else {
            val attr = JSONObject(body).getJSONObject("data").getJSONObject("attributes")
            val stats = attr.getJSONObject("last_analysis_stats")
            val mal = stats.optInt("malicious") + stats.optInt("suspicious")
            val total = stats.keys().asSequence().sumOf { stats.optInt(it) }
            val label = attr.optJSONObject("popular_threat_classification")?.optString("suggested_threat_label")
            VtResult(mal, total, label?.takeIf { it.isNotBlank() })
        }
    } catch (e: Exception) { null }

    fun testKey(apiKey: String): Boolean = try {
        // Hash do arquivo de teste EICAR, que todo antivírus conhece
        virusTotal(apiKey, "275a021bbfb6489e54d471899f7db9d1663fc695ec2fe2a2c4538aabf651fd0f") != null
    } catch (e: Exception) { false }

    // ---------- auxiliares ----------

    private fun httpGet(url: String, headers: Map<String, String>, timeout: Int): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeout; c.readTimeout = timeout
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        return try {
            if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null
        } finally { c.disconnect() }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }
}
