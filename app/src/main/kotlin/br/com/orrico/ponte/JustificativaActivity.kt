package br.com.orrico.ponte

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Justificativa de privacidade exigida pelo Health Connect: aberta pelo link
 * "Política de privacidade" na tela de permissões do sistema. Sem rede.
 */
class JustificativaActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt() }
        val coluna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        fun titulo(t: String) = TextView(this).apply {
            text = t
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(8))
        }
        fun paragrafo(t: String) = TextView(this).apply {
            text = t
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setLineSpacing(0f, 1.25f)
        }
        coluna.addView(titulo("Como a ponte usa seus dados de saúde"))
        coluna.addView(
            paragrafo(
                "A ponte só LÊ do Health Connect: passos, sono, batimentos (incluindo os de repouso e a " +
                    "variabilidade), SpO2, exercícios, calorias ativas, peso, gordura corporal e massa " +
                    "magra. Ela nunca grava nada no Health Connect.",
            ),
        )
        coluna.addView(titulo("Para quê"))
        coluna.addView(
            paragrafo(
                "Para levar esses dados à sua conta do Sereno, onde aparecem junto do que você já " +
                    "registra (Sono, Atividade física, Medidas e Batimentos), sem digitar de novo.",
            ),
        )
        coluna.addView(titulo("Onde ficam"))
        coluna.addView(
            paragrafo(
                "Na sua conta do Sereno, onde só você tem acesso. A ponte não guarda os dados no " +
                    "celular: lê e envia. Os batimentos minuto a minuto não são guardados, só os " +
                    "resumos por hora e por dia. Nada é vendido nem enviado a terceiros.",
            ),
        )
        coluna.addView(titulo("Como desligar"))
        coluna.addView(
            paragrafo(
                "No Sereno: Configurações → Integrações → Pulseira e balança → Desligar. No Health " +
                    "Connect, você pode revogar as permissões da ponte a qualquer momento.",
            ),
        )
        setContentView(ScrollView(this).apply { addView(coluna) })
    }
}
