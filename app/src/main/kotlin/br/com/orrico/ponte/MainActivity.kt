package br.com.orrico.ponte

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/**
 * Duas telas: o código (ligar à conta) e o estado (permissões, último envio,
 * "Enviar agora"). Sem senha: o código vem de Configurações → Integrações →
 * Pulseira e balança, no Sereno.
 */
class MainActivity : ComponentActivity() {
    private lateinit var armazem: Armazem
    private lateinit var pedirPermissoes: ActivityResultLauncher<Set<String>>
    private var ocupado = false
    private var versaoNova = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        armazem = Armazem(this)
        pedirPermissoes = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
            mostrar()
            enviarAgora()
        }
        mostrar()
    }

    override fun onResume() {
        super.onResume()
        if (armazem.tokenAparelho != null) {
            Trabalho.agendar(this)
            lifecycleScope.launch {
                val publicada = Servidor().versaoPublicada()
                versaoNova = publicada != null && publicada > BuildConfig.VERSION_CODE
                mostrar()
            }
        }
        mostrar()
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun texto(t: String, tamanho: Float = 16f, negrito: Boolean = false, cor: Int? = null) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, tamanho)
        setLineSpacing(0f, 1.2f)
        if (negrito) setTypeface(typeface, Typeface.BOLD)
        cor?.let { setTextColor(it) }
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun botao(t: String, acao: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        minHeight = dp(48)
        setOnClickListener { acao() }
    }

    private fun mostrar() {
        val coluna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }
        if (armazem.tokenAparelho == null) telaCodigo(coluna) else telaEstado(coluna)
        setContentView(ScrollView(this).apply { addView(coluna) })
    }

    private fun telaCodigo(c: LinearLayout) {
        c.addView(texto("Ligar à sua conta do Sereno", 22f, negrito = true))
        c.addView(
            texto(
                "A ponte lê a pulseira, o relógio e a balança pelo Health Connect e manda para o Sereno " +
                    "a cada hora. No Sereno (no computador ou no celular), abra Configurações → " +
                    "Integrações → Pulseira e balança, toque em \"Gerar código\" e digite aqui.",
            ),
        )
        val codigo = EditText(this).apply {
            hint = "Código (ex.: ABCD-EFGH)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(12), InputFilter.AllCaps())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            minHeight = dp(56)
        }
        val nome = EditText(this).apply {
            hint = "Nome deste celular"
            setText(listOf(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL).distinct().joinToString(" "))
            inputType = InputType.TYPE_CLASS_TEXT
            filters = arrayOf(InputFilter.LengthFilter(80))
            minHeight = dp(48)
        }
        val aviso = texto("", cor = 0xFFB3261E.toInt()).apply { visibility = View.GONE }
        c.addView(codigo)
        c.addView(texto("Nome deste celular (aparece no Sereno):", 14f))
        c.addView(nome)
        c.addView(aviso)
        c.addView(
            botao("Ligar") {
                if (ocupado) return@botao
                ocupado = true
                aviso.visibility = View.GONE
                lifecycleScope.launch {
                    val r = runCatching {
                        Servidor().parear(codigo.text.toString().trim(), nome.text.toString().trim(), BuildConfig.VERSION_NAME)
                    }
                    ocupado = false
                    val token = r.getOrNull()
                    if (token != null) {
                        armazem.tokenAparelho = token
                        armazem.nomeAparelho = nome.text.toString().trim()
                        Trabalho.agendar(this@MainActivity)
                        mostrar()
                        permitir()
                    } else {
                        aviso.text = if (r.isFailure) {
                            "Sem conexão com o Sereno agora. Confira a internet e tente de novo."
                        } else {
                            "Esse código não vale (errado, já usado ou vencido: ele vale 15 minutos). Gere outro no Sereno."
                        }
                        aviso.visibility = View.VISIBLE
                    }
                }
            },
        )
    }

    private fun telaEstado(c: LinearLayout) {
        val sdk = HealthConnect.sdk(this)
        c.addView(texto("Ligada à sua conta do Sereno", 22f, negrito = true))
        armazem.nomeAparelho?.let { c.addView(texto("Este celular: $it", 14f)) }
        if (versaoNova) {
            c.addView(texto("Há uma versão nova da ponte.", negrito = true))
            c.addView(botao("Baixar a versão nova") { abrir("${BuildConfig.SITE}/ponte/") })
        }

        c.addView(texto("Health Connect", 18f, negrito = true))
        if (sdk != "disponivel") {
            c.addView(
                texto(
                    if (sdk == "indisponivel") {
                        "O Health Connect não está disponível neste celular."
                    } else {
                        "Instale ou atualize o Health Connect para a ponte ler a pulseira e a balança."
                    },
                ),
            )
            if (sdk != "indisponivel") {
                c.addView(botao("Abrir a loja") { abrir("market://details?id=${HealthConnect.PACOTE}") })
            }
        } else {
            val estado = texto("Conferindo as permissões…")
            c.addView(estado)
            val permitirBotao = botao("Permitir") { permitir() }
            c.addView(permitirBotao)
            lifecycleScope.launch {
                val concedidas = runCatching {
                    HealthConnectClient.getOrCreate(this@MainActivity).permissionController.getGrantedPermissions()
                }.getOrDefault(emptySet())
                val n = HealthConnect.permissoes.count { it in concedidas }
                val total = HealthConnect.permissoes.size
                estado.text = if (n == total) {
                    "Tudo permitido (inclusive ler com a ponte fechada)."
                } else {
                    "Permitido: $n de $total. Toque em Permitir e ligue tudo (inclusive \"Acessar dados em segundo plano\")."
                }
                permitirBotao.visibility = if (n == total) View.GONE else View.VISIBLE
            }
        }

        c.addView(texto("Envios", 18f, negrito = true))
        val ultimo = armazem.ultimoEnvio
        c.addView(
            texto(
                if (ultimo == 0L) {
                    "Ainda não enviou nada."
                } else {
                    "Último envio: " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ultimo))
                },
            ),
        )
        armazem.ultimoErro?.let { c.addView(texto(mensagemDoErro(it), cor = 0xFFB3261E.toInt())) }
        c.addView(texto("A ponte envia sozinha a cada hora. Na primeira vez, manda os últimos 30 dias.", 14f))
        c.addView(botao(if (ocupado) "Enviando…" else "Enviar agora") { enviarAgora() })

        val energia = getSystemService(PowerManager::class.java)
        if (!energia.isIgnoringBatteryOptimizations(packageName)) {
            c.addView(texto("Bateria", 18f, negrito = true))
            c.addView(
                texto(
                    "O Android pode pausar a ponte para economizar bateria, e aí os envios atrasam. " +
                        "Para enviar sempre, deixe a ponte sem restrição.",
                ),
            )
            c.addView(botao("Não pausar a ponte") { semRestricaoDeBateria() })
        }

        c.addView(texto("Desligar", 18f, negrito = true))
        c.addView(
            texto(
                "Para desligar de vez, use o Sereno (Pulseira e balança → Desligar). Aqui você só " +
                    "esquece a ligação neste celular.",
                14f,
            ),
        )
        c.addView(
            botao("Esquecer a ligação neste celular") {
                armazem.esquecer()
                Trabalho.cancelar(this)
                mostrar()
            },
        )
        c.addView(texto("Versão ${BuildConfig.VERSION_NAME}", 12f))
    }

    private fun mensagemDoErro(codigo: String) = when (codigo) {
        "sem_permissao" -> "O Health Connect não deixou ler. Toque em Permitir."
        "limite" -> "O Health Connect pediu uma pausa. A ponte tenta de novo sozinha."
        "rede" -> "Sem internet no último envio. A ponte tenta de novo sozinha."
        "dados_invalidos" -> "O Sereno recusou parte do que a ponte leu. Avise o Léo."
        else -> "A última leitura teve um problema ($codigo). A ponte tenta de novo sozinha."
    }

    private fun permitir() {
        if (HealthConnect.sdk(this) != "disponivel") return
        runCatching { pedirPermissoes.launch(HealthConnect.permissoes) }
    }

    private fun enviarAgora() {
        if (ocupado || armazem.tokenAparelho == null) return
        ocupado = true
        mostrar()
        lifecycleScope.launch {
            Leitura.executar(this@MainActivity)
            ocupado = false
            mostrar()
        }
    }

    @SuppressLint("BatteryLife")
    private fun semRestricaoDeBateria() {
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    private fun abrir(endereco: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(endereco))) }
    }
}
