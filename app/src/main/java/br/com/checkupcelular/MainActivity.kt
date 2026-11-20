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
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private enum class Screen { APPS, DEVICE, CLEAN }

    private lateinit var results: LinearLayout
    private var current: Screen? = null

    private val green = 0xFF1B8A3C.toInt()
    private val orange = 0xFFE07B00.toInt()
    private val red = 0xFFC62828.toInt()
    private val dark = 0xFF1F2328.toInt()
    private val gray = 0xFF5F6368.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        root.addView(label("Check-up Celular", 26f, true))
        root.addView(label("por ${Config.NOME} · ${Config.SERVICO}", 14f, true, 0xFF1A73E8.toInt()))
        root.addView(label("Encontra apps suspeitos, revisa a segurança e limpa arquivos inúteis. Nada é apagado sem você confirmar.", 14f, color = gray))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(4)) }
        buttons.addView(bigButton("🛡️  Procurar apps suspeitos") { show(Screen.APPS) })
        buttons.addView(bigButton("🔒  Verificar segurança do celular") { show(Screen.DEVICE) })
        buttons.addView(bigButton("🧹  Limpeza de arquivos") { show(Screen.CLEAN) })
        root.addView(buttons)

        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(results)

        root.addView(card().apply {
            addView(label("Precisa de ajuda?", 16f, true))
            addView(label("Achou algo estranho ou o app não sai? Fale com ${Config.NOME}: ${Config.WHATSAPP_EXIBIDO}", 14f, color = gray))
            addView(smallButton("Chamar no WhatsApp", green) {
                safeStart(Intent(Intent.ACTION_VIEW, Uri.parse(Config.WHATSAPP_LINK)))
            })
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFFF4F6F8.toInt())
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        // Ao voltar da tela de desinstalar/configurações, refaz a última verificação
        current?.let { show(it) }
    }

    private fun show(screen: Screen) {
        current = screen
        results.removeAllViews()
        results.addView(label("Verificando...", 15f, color = gray))
        Thread {
            when (screen) {
                Screen.APPS -> { val r = Scanner.scanApps(this); runOnUiThread { renderApps(r) } }
                Screen.DEVICE -> { val r = Scanner.checkDevice(this); runOnUiThread { renderDevice(r) } }
                Screen.CLEAN -> {
                    if (!hasFileAccess()) runOnUiThread { renderNeedFiles() }
                    else { val r = Cleaner.scan(); runOnUiThread { renderClean(r) } }
                }
            }
        }.start()
    }

    // ---------- Apps suspeitos ----------

    private fun renderApps(list: List<AppRisk>) {
        results.removeAllViews()
        if (list.isEmpty()) {
            results.addView(card().apply {
                addView(label("✅ Nenhum app suspeito encontrado", 17f, true, green))
                addView(label("Todos os apps instalados por você vieram de lojas oficiais e não pedem permissões perigosas.", 14f, color = gray))
            })
        } else {
            results.addView(label("${list.size} app(s) para revisar", 18f, true).apply { setPadding(0, dp(12), 0, 0) })
            results.addView(label("Se você não reconhece o app, desinstale. Se reconhece e confia, pode deixar. Na dúvida, chame ${Config.NOME} no WhatsApp (botão no fim da tela).", 13f, color = gray))
        }
        for (a in list) {
            val lvl = Scanner.level(a.score)
            val c = when (lvl) { "ALTO" -> red; "MÉDIO" -> orange; else -> gray }
            results.addView(card().apply {
                addView(label(a.label, 17f, true))
                addView(label(a.pkg, 12f, color = gray))
                addView(label("Risco $lvl", 14f, true, c).apply { setPadding(0, dp(6), 0, dp(2)) })
                for (r in a.reasons) addView(label("• $r", 14f))
                if (a.isAdmin) addView(label("Para desinstalar, primeiro tire ele de \"Apps de administração do dispositivo\" (botão abaixo).", 13f, color = orange))
                addView(row(
                    smallButton("Desinstalar", red) {
                        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}")))
                    },
                    smallButton("Detalhes", dark) {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")))
                    }
                ))
                if (a.isAdmin) addView(smallButton("Remover administrador", orange) { safeStart(Intent(Settings.ACTION_SECURITY_SETTINGS)) })
            })
        }
        results.addView(card().apply {
            addView(label("Dica: app que não deixa desinstalar", 15f, true))
            addView(label("Reinicie em Modo Seguro (segure o botão de desligar e depois segure \"Desligar\" na tela). Nesse modo, apps baixados ficam travados e dá pra desinstalar normalmente.", 13f, color = gray))
        })
    }

    // ---------- Segurança do aparelho ----------

    private fun renderDevice(list: List<DeviceCheck>) {
        results.removeAllViews()
        val bad = list.count { !it.ok }
        results.addView(label(if (bad == 0) "✅ Tudo certo" else "$bad ponto(s) para ajustar", 18f, true, if (bad == 0) green else orange).apply { setPadding(0, dp(12), 0, 0) })
        for (c in list) {
            results.addView(card().apply {
                addView(label((if (c.ok) "✅ " else "⚠️ ") + c.title, 16f, true, if (c.ok) dark else orange))
                addView(label(c.detail, 14f, color = gray))
                val fix = c.fix
                if (!c.ok && fix != null) addView(smallButton("Abrir configuração", dark) { safeStart(fix) })
            })
        }
    }

    // ---------- Limpeza ----------

    private fun hasFileAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun renderNeedFiles() {
        results.removeAllViews()
        results.addView(card().apply {
            addView(label("Permissão de arquivos", 16f, true))
            addView(label("Para achar APKs esquecidos e arquivos temporários, o app precisa acessar a memória do celular. Ele só apaga o que você confirmar.", 14f, color = gray))
            addView(smallButton("Dar permissão", dark) {
                if (Build.VERSION.SDK_INT >= 30) {
                    safeStart(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
                }
            })
        })
        addCacheCard()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (current == Screen.CLEAN) show(Screen.CLEAN)
    }

    private fun renderClean(items: List<JunkItem>) {
        results.removeAllViews()
        val total = items.sumOf { it.size }
        if (items.isEmpty()) {
            results.addView(card().apply { addView(label("✅ Nenhum arquivo inútil encontrado", 16f, true, green)) })
        } else {
            results.addView(card().apply {
                addView(label("${items.size} arquivo(s) · ${fmt(total)}", 18f, true))
                addView(label("APKs são instaladores que ficam esquecidos em Downloads. Alguns vírus chegam assim, por WhatsApp.", 13f, color = gray))
                addView(smallButton("Apagar tudo (${fmt(total)})", red) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Apagar ${items.size} arquivo(s)?")
                        .setMessage("Isso libera ${fmt(total)}. Fotos, vídeos e documentos não são tocados.")
                        .setPositiveButton("Apagar") { _, _ ->
                            Thread {
                                val freed = Cleaner.delete(items)
                                runOnUiThread {
                                    show(Screen.CLEAN)
                                    results.addView(label("Liberado: ${fmt(freed)}", 15f, true, green), 0)
                                }
                            }.start()
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                })
            })
            for (i in items.take(60)) {
                results.addView(card().apply {
                    addView(label(i.file.name, 14f, true))
                    addView(label("${i.kind} · ${fmt(i.size)}", 13f, color = gray))
                    addView(label(i.file.parent ?: "", 11f, color = gray))
                })
            }
        }
        addCacheCard()
    }

    private fun addCacheCard() {
        results.addView(card().apply {
            addView(label("Cache dos outros apps", 16f, true))
            addView(label("O Android não deixa um app limpar o cache dos outros. Abra o armazenamento e limpe o cache dos apps maiores (WhatsApp, Instagram, Chrome...).", 13f, color = gray))
            addView(smallButton("Abrir armazenamento", dark) { safeStart(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) })
        })
    }

    // ---------- Helpers de interface ----------

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

    private fun bigButton(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        textSize = 16f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { setColor(0xFF1A73E8.toInt()); cornerRadius = dp(12).toFloat() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
        ).apply { topMargin = dp(8) }
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
