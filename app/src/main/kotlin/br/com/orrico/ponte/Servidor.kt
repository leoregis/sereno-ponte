package br.com.orrico.ponte

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * A função saude-ponte do Sereno. Sem senha: o código de uso único (gerado
 * no web) vira o token do aparelho, que vai no cabeçalho de cada envio.
 */
class Servidor(private val endereco: String = BuildConfig.SERVIDOR) {
    data class Resposta(val http: Int, val corpo: JSONObject)

    private suspend fun post(corpo: JSONObject, token: String? = null): Resposta = withContext(Dispatchers.IO) {
        val c = URL(endereco).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 20_000
            c.readTimeout = 120_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            c.outputStream.use { it.write(corpo.toString().toByteArray(Charsets.UTF_8)) }
            val http = c.responseCode
            val texto = (if (http in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
            Resposta(http, runCatching { JSONObject(texto) }.getOrDefault(JSONObject()))
        } finally {
            c.disconnect()
        }
    }

    /** O token do aparelho, ou nulo se o código não vale (errado, usado ou vencido). */
    suspend fun parear(codigo: String, nome: String, versao: String): String? {
        val r = try {
            post(JSONObject().put("acao", "parear").put("codigo", codigo).put("nome", nome).put("versao", versao))
        } catch (e: IOException) {
            throw FalhaEnvio("rede")
        }
        if (r.http != 200) throw FalhaEnvio("servidor")
        return r.corpo.optString("token").takeIf { r.corpo.optBoolean("ok") && it.startsWith("sp_") }
    }

    /** O token ainda vale? (falso: desligada no web) */
    suspend fun conferir(token: String): Boolean {
        val r = try {
            post(JSONObject().put("acao", "conferir"), token)
        } catch (e: IOException) {
            throw FalhaEnvio("rede")
        }
        return when (r.http) {
            200 -> true
            401 -> false
            else -> throw FalhaEnvio("servidor")
        }
    }

    fun envio(token: String, fuso: () -> String, versao: String) = object : Envio {
        override suspend fun enviar(comandos: JSONArray, estado: JSONObject) {
            val lote = JSONObject()
                .put("fuso", fuso())
                .put("versao", versao)
                .put("estado", estado)
                .put("comandos", comandos)
            val r = try {
                post(JSONObject().put("acao", "enviar").put("lote", lote), token)
            } catch (e: IOException) {
                throw FalhaEnvio("rede")
            }
            when (r.http) {
                200 -> Unit
                401 -> throw FalhaEnvio("revogado")
                422 -> throw FalhaEnvio("dados_invalidos")
                else -> throw FalhaEnvio("servidor")
            }
        }
    }

    /** A versão publicada no site (/ponte/versao.json), ou nulo. */
    suspend fun versaoPublicada(site: String = BuildConfig.SITE): Int? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL("$site/ponte/versao.json").openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 10_000
                c.readTimeout = 10_000
                if (c.responseCode != 200) return@runCatching null
                JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optInt("codigo").takeIf { it > 0 }
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }
}
