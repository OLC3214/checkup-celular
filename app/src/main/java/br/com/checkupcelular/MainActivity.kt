package br.com.checkupcelular

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class MainActivity : Activity() {

    private class Report(
        val deep: Boolean,
        var apps: List<AppRisk>,
        val device: List<DeviceCheck>,
        val files: List<FileItem>?,
        val fileThreats: Map<String, String>,
        val dbFamilies: Int,
        val dbSource: String,
        val vtChecked: Int,
        val vtStatus: String,
        val seconds: Long
    )

    private lateinit var results: LinearLayout
    private lateinit var scroll: ScrollView
    private var report: Report? = null
    @Volatile private var running = false

    private val green = 0xFF1B8A3C.toInt()
    private val orange = 0xFFE07B00.toInt()
    private val red = 0xFFC62828.toInt()
    private val dark = 0xFF1F2328.toInt()
    private val gray = 0xFF5F6368.toInt()
    private val blue = 0xFF1A73E8.toInt()

    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }
    private val vtKey get() = prefs.getString("vt_key", "")?.trim() ?: ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        root.addView(label("Check-up Celular", 26f, true))
        root.addView(label("por ${Config.NOME} · ${Config.SERVICO}", 14f, true, blue))
        root.addView(label("Procura vírus conhecidos, apps suspeitos, problemas de segurança e arquivos inúteis. Nada é apagado sem você confirmar.", 14f, color = gray))

        root.addView(scanButton("⚡  Varredura rápida", "Uns 15 segundos · apps, banco de apps espiões, segurança e Downloads", blue) { startScan(false) })
        root.addView(scanButton("🔍  Varredura completa", "1 a 5 minutos · tudo da rápida + VirusTotal, assinaturas, memória inteira, root, VPN e certificados", dark) { startScan(true) })

        root.addView(TextView(this).apply {
            text = if (vtKey.isEmpty()) "⚙️ Configurar VirusTotal (deixa a varredura completa muito mais forte)" else "⚙️ VirusTotal configurado ✓"
            setTextColor(blue); textSize = 14f
            setPadding(0, dp(12), 0, dp(4))
            setOnClickListener { showVtSetup() }
        })

        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(results)

        root.addView(card().apply {
            addView(label("Precisa de ajuda?", 16f, true))
            addView(label("Achou algo estranho ou o app não sai? Fale com ${Config.NOME}: ${Config.WHATSAPP_EXIBIDO}", 14f, color = gray))
            addView(smallButton("Chamar no WhatsApp", green) {
                safeStart(Intent(Intent.ACTION_VIEW, Uri.parse(Config.WHATSAPP_LINK)))
            })
        })
        root.addView(label("Banco de apps espiões: Echap stalkerware-indicators (CC-BY 4.0).", 11f, color = gray).apply { setPadding(0, dp(12), 0, 0) })

        scroll = ScrollView(this).apply {
            setBackgroundColor(0xFFF4F6F8.toInt())
            addView(root)
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        // Voltou da tela de desinstalar? Tira do relatório o que já foi removido, sem refazer a varredura.
        val r = report ?: return
        if (running) return
        r.apps = r.apps.filter { isInstalled(it.pkg) }
        render(r)
    }

    // ================= VARREDURA =================

    private fun startScan(deep: Boolean) {
        if (running) return
        running = true
        results.removeAllViews()
        val status = label("Preparando...", 15f, color = gray)
        val box = card().apply {
            addView(label(if (deep) "🔍 Varredura completa em andamento" else "⚡ Varredura rápida em andamento", 16f, true))
            addView(status)
            addView(label("Não feche o app.", 12f, color = gray))
        }
        results.addView(box)
        val progress: (String) -> Unit = { msg -> runOnUiThread { status.text = msg } }

        Thread {
            val t0 = System.currentTimeMillis()
            val r = try { runScan(deep, progress, t0) } catch (e: Exception) { null }
            running = false
            runOnUiThread {
                if (r == null) {
                    results.removeAllViews()
                    results.addView(card().apply { addView(label("Erro na varredura. Tente de novo.", 15f, true, red)) })
                } else {
                    report = r; render(r)
                    scroll.post { scroll.smoothScrollTo(0, results.top) }
                }
            }
        }.start()
    }

    private fun runScan(deep: Boolean, progress: (String) -> Unit, t0: Long): Report {
        val apps = Scanner.scanApps(this, deep, progress)
        val db = Threats.load(this, progress)

        progress("Verificando configurações de segurança...")
        val device = Scanner.checkDevice(this, deep)

        val files = if (hasFileAccess()) Cleaner.scan(this, deep, progress) else null

        // VirusTotal: só na completa e só se tiver chave
        val fileThreats = HashMap<String, String>()
        var vtChecked = 0
        val key = vtKey
        val vtStatus: String
        if (!deep) {
            vtStatus = "Só na varredura completa"
        } else if (key.isEmpty()) {
            vtStatus = "Não configurado"
        } else {
            val appTargets = apps.filter { it.outsideStore && it.apkPath != null && it.confirmed == null }
                .sortedByDescending { it.score }.take(15)
            val fileTargets = files.orEmpty().filter { it.file.extension.equals("apk", true) }.take(5)
            val total = appTargets.size + fileTargets.size
            var i = 0
            fun waitRate() { if (i > 0) Thread.sleep(15_500) } // chave grátis: 4 consultas/min

            for (a in appTargets) {
                waitRate(); i++
                progress("VirusTotal: consultando ${a.label} ($i de $total)...")
                val hash = Threats.sha256(File(a.apkPath!!)) ?: continue
                val vt = Threats.virusTotal(key, hash); vtChecked++
                applyVt(a, vt)
            }
            for (f in fileTargets) {
                waitRate(); i++
                progress("VirusTotal: consultando ${f.file.name} ($i de $total)...")
                val hash = Threats.sha256(f.file) ?: continue
                val vt = Threats.virusTotal(key, hash); vtChecked++
                if (vt != null && vt.malicious >= 3) {
                    fileThreats[f.file.absolutePath] = "VirusTotal: ${vt.malicious} de ${vt.total} antivírus" + (vt.label?.let { " ($it)" } ?: "")
                }
            }
            vtStatus = if (total == 0) "Nenhum app de fora da loja para consultar" else "$vtChecked consultado(s)"
        }

        return Report(
            deep, apps.sortedByDescending { it.score }, device, files, fileThreats,
            db.size, db.source, vtChecked, vtStatus, (System.currentTimeMillis() - t0) / 1000
        )
    }

    private fun applyVt(a: AppRisk, vt: Threats.VtResult?) {
        if (vt == null) return
        val label = vt.label?.let { " ($it)" } ?: ""
        if (vt.malicious >= 3) {
            a.confirmed = "VirusTotal: ${vt.malicious} de ${vt.total} antivírus dizem que é vírus$label"
        } else if (vt.malicious in 1..2) {
            a.reasons.add("${vt.malicious} antivírus no VirusTotal acharam algo (pode ser alarme falso)")
        } else {
            a.reasons.add("VirusTotal: nenhum antivírus acusou este app ✓")
        }
    }

    // ================= RELATÓRIO =================

    private fun render(r: Report) {
        results.removeAllViews()
        val confirmed = r.apps.filter { it.confirmed != null }
        val suspects = r.apps.filter { it.confirmed == null }
        val high = suspects.count { Scanner.level(it.score) == "ALTO" }
        val med = suspects.count { Scanner.level(it.score) == "MÉDIO" }
        val low = suspects.size - high - med
        val badDevice = r.device.count { !it.ok }
        val confirmedTotal = confirmed.size + r.fileThreats.size
        val junk = r.files.orEmpty().filter { it.junk }
        val junkSize = junk.sumOf { it.size }

        val score = (100 - confirmedTotal * 40 - high * 15 - med * 7 - low * 2 - badDevice * 6).coerceIn(0, 100)
        val scoreColor = when { confirmedTotal > 0 || score < 50 -> red; score < 80 -> orange; else -> green }

        // ---- Resumo ----
        results.addView(card().apply {
            addView(label(if (r.deep) "Resultado da varredura completa" else "Resultado da varredura rápida", 14f, true, gray))
            addView(label("Nota: $score/100", 30f, true, scoreColor))
            addView(label(
                when {
                    confirmedTotal > 0 -> "🦠 $confirmedTotal vírus confirmado(s). Remova agora."
                    high > 0 -> "Nenhum vírus conhecido, mas tem app muito suspeito."
                    score >= 80 -> "Celular saudável."
                    else -> "Nenhum vírus conhecido. Tem pontos para melhorar."
                }, 16f, true, scoreColor
            ))
            addView(label("🦠 Vírus confirmados: $confirmedTotal", 14f))
            addView(label("⚠️ Apps suspeitos: ${suspects.size} ($high alto, $med médio, $low baixo)", 14f))
            addView(label("🔒 Pontos de segurança: $badDevice", 14f))
            addView(label("🧹 Lixo encontrado: " + (if (r.files == null) "sem permissão" else fmt(junkSize)), 14f))
            addView(label("\nO que foi comparado:", 13f, true, gray))
            addView(label("• Banco de apps espiões: ${r.dbFamilies} famílias (${r.dbSource})", 12f, color = gray))
            addView(label("• VirusTotal (~70 antivírus): ${r.vtStatus}", 12f, color = gray))
            addView(label("• Tempo: ${r.seconds}s", 12f, color = gray))
            if (r.deep && vtKey.isEmpty()) addView(smallButton("Configurar VirusTotal", blue) { showVtSetup() })
        })

        // ---- Vírus confirmados ----
        if (confirmedTotal > 0) {
            results.addView(section("🦠 Vírus confirmados", red))
            results.addView(label("Estes estão em bancos de dados de vírus. Desinstale agora.", 13f, color = gray))
            for (a in confirmed) results.addView(appCard(a, red))
            for ((path, why) in r.fileThreats) {
                val f = File(path)
                results.addView(card().apply {
                    addView(label(f.name, 16f, true, red))
                    addView(label("Arquivo instalador · $why", 14f))
                    addView(label(f.parent ?: "", 11f, color = gray))
                    addView(smallButton("Apagar arquivo", red) { confirmDelete(listOf(FileItem(f, "", f.length(), true))) })
                })
            }
        } else {
            results.addView(card().apply {
                addView(label("✅ Nenhum vírus conhecido encontrado", 16f, true, green))
                addView(label("Vírus muito novos podem ainda não estar nos bancos de dados. Por isso o app também mostra os suspeitos abaixo.", 13f, color = gray))
            })
        }

        // ---- Suspeitos ----
        if (suspects.isNotEmpty()) {
            results.addView(section("⚠️ Apps suspeitos (não confirmados)", orange))
            results.addView(label("Não está provado que são vírus. Se você não reconhece o app, desinstale. Se reconhece e confia, pode deixar.", 13f, color = gray))
            for (a in suspects) {
                val c = when (Scanner.level(a.score)) { "ALTO" -> red; "MÉDIO" -> orange; else -> gray }
                results.addView(appCard(a, c))
            }
        }

        // ---- Segurança ----
        results.addView(section("🔒 Segurança do celular", dark))
        for (c in r.device) {
            results.addView(card().apply {
                addView(label((if (c.ok) "✅ " else "⚠️ ") + c.title, 15f, true, if (c.ok) dark else orange))
                addView(label(c.detail, 13f, color = gray))
                val fix = c.fix
                if (!c.ok && fix != null) addView(smallButton("Abrir configuração", dark) { safeStart(fix) })
            })
        }

        // ---- Arquivos ----
        results.addView(section("🧹 Arquivos", dark))
        if (r.files == null) {
            results.addView(card().apply {
                addView(label("Permissão de arquivos", 15f, true))
                addView(label("Para procurar instaladores suspeitos e lixo, o app precisa acessar a memória. Dê a permissão e rode a varredura de novo.", 13f, color = gray))
                addView(smallButton("Dar permissão", dark) { requestFileAccess() })
            })
        } else {
            if (junk.isEmpty()) {
                results.addView(card().apply { addView(label("✅ Nenhum arquivo inútil encontrado", 15f, true, green)) })
            } else {
                results.addView(card().apply {
                    addView(label("${junk.size} arquivo(s) para limpar · ${fmt(junkSize)}", 16f, true))
                    addView(label("Instaladores APK esquecidos, miniaturas, temporários e pastas vazias. Fotos, vídeos e documentos não são tocados.", 13f, color = gray))
                    addView(smallButton("Limpar tudo (${fmt(junkSize)})", red) { confirmDelete(junk) })
                })
                for (i in junk.take(40)) {
                    results.addView(card().apply {
                        addView(label(i.file.name, 14f, true, if (i.suspicious) red else dark))
                        addView(label("${i.kind} · ${fmt(i.size)}", 12f, color = if (i.suspicious) red else gray))
                        addView(label(i.file.parent ?: "", 11f, color = gray))
                    })
                }
            }
            val big = r.files.filter { !it.junk }
            if (big.isNotEmpty()) {
                results.addView(card().apply {
                    addView(label("Arquivos grandes (só aviso)", 15f, true))
                    for (b in big.take(10)) addView(label("• ${b.file.name} · ${fmt(b.size)}", 13f, color = gray))
                    addView(label("Veja se ainda precisa deles. O app não apaga estes.", 12f, color = gray))
                })
            }
        }
        results.addView(card().apply {
            addView(label("Cache dos outros apps", 15f, true))
            addView(label("O Android não deixa um app limpar o cache dos outros. Abra o armazenamento e limpe o cache dos apps maiores (WhatsApp, Instagram, Chrome...).", 13f, color = gray))
            addView(smallButton("Abrir armazenamento", dark) { safeStart(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) })
        })
        results.addView(card().apply {
            addView(label("App que não deixa desinstalar?", 15f, true))
            addView(label("Reinicie em Modo Seguro: segure o botão de desligar e depois segure \"Desligar\" na tela. Nesse modo, apps baixados ficam travados e dá pra desinstalar normalmente.", 13f, color = gray))
        })
    }

    private fun appCard(a: AppRisk, c: Int) = card().apply {
        addView(label(a.label, 17f, true, if (a.confirmed != null) red else dark))
        addView(label(a.pkg, 11f, color = gray))
        if (a.confirmed != null) addView(label("🦠 ${a.confirmed}", 14f, true, red).apply { setPadding(0, dp(6), 0, dp(2)) })
        else addView(label("Risco ${Scanner.level(a.score)}", 14f, true, c).apply { setPadding(0, dp(6), 0, dp(2)) })
        for (r in a.reasons) addView(label("• $r", 13f))
        if (a.isAdmin) addView(label("Para desinstalar, primeiro tire ele de \"Apps de administração do dispositivo\".", 12f, color = orange))
        addView(row(
            smallButton("Desinstalar", red) { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}"))) },
            smallButton("Detalhes", dark) { safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}"))) }
        ))
        if (a.isAdmin) addView(smallButton("Remover administrador", orange) { safeStart(Intent(Settings.ACTION_SECURITY_SETTINGS)) })
    }

    private fun confirmDelete(items: List<FileItem>) {
        val total = items.sumOf { it.size }
        AlertDialog.Builder(this)
            .setTitle("Apagar ${items.size} item(ns)?")
            .setMessage("Isso libera ${fmt(total)}. Não dá para desfazer.")
            .setPositiveButton("Apagar") { _, _ ->
                Thread {
                    val freed = Cleaner.delete(items)
                    runOnUiThread {
                        val r = report
                        if (r != null) {
                            val gone = items.map { it.file.absolutePath }.toSet()
                            report = Report(r.deep, r.apps, r.device, r.files?.filter { it.file.absolutePath !in gone },
                                r.fileThreats.filterKeys { it !in gone }, r.dbFamilies, r.dbSource, r.vtChecked, r.vtStatus, r.seconds)
                            render(report!!)
                        }
                        results.addView(label("Liberado: ${fmt(freed)}", 15f, true, green), 0)
                    }
                }.start()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ================= VIRUSTOTAL =================

    private fun showVtSetup() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        box.addView(label("O VirusTotal compara cada app com ~70 antivírus e diz se ele já é um vírus conhecido. É grátis:", 14f))
        box.addView(label("1. Crie uma conta em virustotal.com\n2. Clique no seu nome > API key\n3. Copie a chave e cole aqui", 14f, color = gray))
        val input = EditText(this).apply {
            hint = "Cole a chave aqui"
            setText(vtKey)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        box.addView(input)
        box.addView(label("A chave fica salva só neste celular. Com a chave grátis, cada app leva uns 15 segundos.", 12f, color = gray))
        AlertDialog.Builder(this)
            .setTitle("Configurar VirusTotal")
            .setView(box)
            .setNeutralButton("Abrir site") { _, _ -> safeStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.virustotal.com/gui/my-apikey"))) }
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar") { _, _ ->
                val k = input.text.toString().trim()
                prefs.edit().putString("vt_key", k).apply()
                if (k.isEmpty()) { recreate(); return@setPositiveButton }
                Thread {
                    val ok = Threats.testKey(k)
                    runOnUiThread {
                        AlertDialog.Builder(this)
                            .setMessage(if (ok) "✅ Chave funcionando! Agora rode a varredura completa." else "❌ A chave não funcionou. Confira se copiou certo e se tem internet.")
                            .setPositiveButton("OK") { _, _ -> recreate() }
                            .show()
                    }
                }.start()
            }
            .show()
    }

    // ================= PERMISSÕES / AUXILIARES =================

    private fun hasFileAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestFileAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            safeStart(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
    }

    private fun isInstalled(pkg: String) = try { packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

    private fun safeStart(i: Intent) {
        try { startActivity(i) } catch (e: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun fmt(b: Long) = Formatter.formatShortFileSize(this, b)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(s: String, size: Float, bold: Boolean = false, color: Int = dark) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun section(s: String, color: Int) = label(s, 19f, true, color).apply { setPadding(0, dp(20), 0, dp(2)) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), 0xFFDADCE0.toInt())
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
    }

    private fun scanButton(title: String, sub: String, color: Int, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(14).toFloat() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) }
        addView(label(title, 18f, true, Color.WHITE))
        addView(label(sub, 13f, color = 0xDDFFFFFF.toInt()))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun smallButton(s: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(8).toFloat() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(42)
        ).apply { topMargin = dp(8); marginEnd = dp(8) }
        setPadding(dp(16), 0, dp(16), 0)
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it) }
    }
}
