package br.com.orrico.ponte

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** Tipos lidos do Health Connect (os mesmos códigos do Sereno). Só leitura. */
enum class Tipo(val codigo: String) {
    PASSOS("passos"),
    CALORIAS_ATIVAS("calorias_ativas"),
    SONO("sono"),
    FC("fc"),
    FC_REPOUSO("fc_repouso"),
    VFC("vfc"),
    SPO2("spo2"),
    EXERCICIO("exercicio"),
    PESO("peso"),
    GORDURA("gordura"),
    MASSA_MAGRA("massa_magra");

    /** Séries que viram agregados por hora e por dia (nunca guardadas cruas). */
    val agregado: Boolean
        get() = this in setOf(PASSOS, CALORIAS_ATIVAS, FC, FC_REPOUSO, VFC, SPO2)

    companion object {
        fun doCodigo(c: String?): Tipo? = entries.firstOrNull { it.codigo == c }
    }
}

/** Disponibilidade e permissões no aparelho. */
data class EstadoSaude(
    /** disponivel | instalar | atualizar | indisponivel */
    val sdk: String,
    val concedidos: Set<Tipo> = emptySet(),
    val segundoPlano: Boolean = false,
) {
    val disponivel: Boolean get() = sdk == "disponivel"
}

/** Um registro lido (só o que o Sereno usa). */
data class Registro(
    val id: String,
    val tipo: Tipo,
    val origem: String,
    val alterado: Instant? = null,
    val aparelho: String? = null,
    val inicio: Instant? = null,
    val fim: Instant? = null,
    val hora: Instant? = null,
    val valor: Double? = null,
    val estagios: List<Estagio> = emptyList(),
    val exercicio: Int? = null,
    val titulo: String? = null,
) {
    /** O formato que a função saude-ponte recebe (o mesmo mapa da ponte do Android). */
    fun json(): JSONObject = JSONObject().apply {
        put("id", id)
        put("origem", origem)
        alterado?.let { put("alterado", it.toString()) }
        aparelho?.let { put("aparelho", it) }
        inicio?.let { put("inicio", it.toString()) }
        fim?.let { put("fim", it.toString()) }
        hora?.let { put("hora", it.toString()) }
        valor?.takeIf { it.isFinite() }?.let { put("valor", it) }
        if (estagios.isNotEmpty()) {
            put("estagios", JSONArray(estagios.map { JSONArray(listOf(it.inicio.toString(), it.fim.toString(), it.estagio)) }))
        }
        exercicio?.let { put("exercicio", it) }
        titulo?.let { put("titulo", it) }
    }
}

/** Estágio do sono (códigos do Health Connect: 1 acordado … 6 REM, 7 acordado na cama). */
data class Estagio(val inicio: Instant, val fim: Instant, val estagio: Int)

data class Pagina(
    val registros: List<Registro> = emptyList(),
    val apagados: List<String> = emptyList(),
    val proximo: String? = null,
    val mais: Boolean = false,
    /** O token venceu (mais de 30 dias sem ler): reler os 30 dias. */
    val expirado: Boolean = false,
)

/** Agregado de uma hora (passos, FC mín./média/máx./amostras, calorias). */
data class AgregadoHora(
    val inicio: Instant,
    val fim: Instant,
    val valores: Map<String, Double>,
    val fontes: List<String> = emptyList(),
)

/** Código simples (sem_permissao, limite, erro, indisponivel). */
class FalhaSaude(val codigo: String) : Exception(codigo)

/** A fonte de dados de saúde: o Health Connect, ou a falsa dos testes. */
interface FonteSaude {
    suspend fun estado(): EstadoSaude

    suspend fun token(tipo: Tipo): String

    suspend fun alteracoes(token: String): Pagina

    /** Todos os registros do tipo no intervalo (páginas lidas por dentro). */
    suspend fun ler(tipo: Tipo, de: Instant, ate: Instant): List<Registro>

    /** Agregados por hora (passos, FC, calorias ativas). */
    suspend fun agregarHoras(tipo: Tipo, de: Instant, ate: Instant): List<AgregadoHora>

    /** Totais de um intervalo: passos, kcal, fcMedia e fcMax, só dos [tipos]. */
    suspend fun resumir(tipos: Set<Tipo>, de: Instant, ate: Instant): Map<String, Double>
}
