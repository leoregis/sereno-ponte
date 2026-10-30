package br.com.orrico.ponte

import android.content.Context
import org.json.JSONObject

/**
 * O que a ponte guarda no próprio celular (arquivo privado do app, fora de
 * qualquer backup): o token do aparelho, os tokens de alteração do Health
 * Connect e o resumo da última leitura para a tela.
 */
class Armazem(ctx: Context) : Marcadores {
    private val p = ctx.applicationContext.getSharedPreferences("ponte", Context.MODE_PRIVATE)

    var tokenAparelho: String?
        get() = p.getString("aparelho.token", null)
        set(v) = p.edit().putString("aparelho.token", v).apply()

    var nomeAparelho: String?
        get() = p.getString("aparelho.nome", null)
        set(v) = p.edit().putString("aparelho.nome", v).apply()

    /** Hora do último envio aceito (ms) e o último resumo, para a tela. */
    var ultimoEnvio: Long
        get() = p.getLong("envio.ultimo", 0)
        set(v) = p.edit().putLong("envio.ultimo", v).apply()

    var ultimoErro: String?
        get() = p.getString("envio.erro", null)
        set(v) = p.edit().putString("envio.erro", v).apply()

    var ultimosLidos: JSONObject
        get() = runCatching { JSONObject(p.getString("envio.lidos", "{}")!!) }.getOrDefault(JSONObject())
        set(v) = p.edit().putString("envio.lidos", v.toString()).apply()

    override fun token(tipo: Tipo): String? = p.getString("hc.${tipo.codigo}", null)

    override fun gravar(tipo: Tipo, token: String?) {
        p.edit().apply { if (token == null) remove("hc.${tipo.codigo}") else putString("hc.${tipo.codigo}", token) }.apply()
    }

    /** Desligar: some o token do aparelho e os marcadores (a próxima ligação relê 30 dias). */
    fun esquecer() {
        p.edit().clear().apply()
    }
}
