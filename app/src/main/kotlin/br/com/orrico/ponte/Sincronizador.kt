package br.com.orrico.ponte

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Onde a ponte guarda os tokens de alteração do Health Connect, por tipo. */
interface Marcadores {
    fun token(tipo: Tipo): String?

    fun gravar(tipo: Tipo, token: String?)
}

/** O envio ao Sereno (a função saude-ponte); os testes trocam. */
interface Envio {
    /** Manda um lote. Lança [FalhaEnvio] se o servidor não aceitou. */
    suspend fun enviar(comandos: JSONArray, estado: JSONObject)
}

/** revogado (desligada no web), dados_invalidos, rede ou servidor. */
class FalhaEnvio(val codigo: String) : Exception(codigo)

data class Resumo(
    val lidos: Map<Tipo, Int> = emptyMap(),
    val erro: String? = null,
    val estado: EstadoSaude? = null,
)

/**
 * Lê o Health Connect e manda ao Sereno — o mesmo caminho do
 * SincronizadorSaude (Dart) do Sereno Android, com a gravação no servidor
 * (ponte_receber), que usa os mesmos ids do app.
 *
 * - Primeira vez de cada tipo: token ANTES da leitura e os últimos 30 dias,
 *   em blocos de 30 dias.
 * - Depois: só as alterações desde o token. Token vencido: relê 30 dias.
 * - Passos, calorias e FC: as alterações só dizem QUAIS dias mudaram; a
 *   ponte pede os agregados por hora desses dias (nunca as amostras).
 * - O token de um tipo só avança depois que o servidor aceitou tudo daquele
 *   tipo (se a rede cair no meio, a próxima leitura manda de novo; o
 *   servidor não duplica).
 */
class Sincronizador(
    private val fonte: FonteSaude,
    private val marcadores: Marcadores,
    private val envio: Envio,
    private val zona: () -> ZoneId = { ZoneId.systemDefault() },
    private val agora: () -> Instant = { Instant.now() },
    private val versao: String = "",
    private val pacotesProprios: Set<String> = setOf("br.com.orrico.ponte", "br.com.orrico.ponte.dev"),
) {
    companion object {
        /** Uma leitura por vez no processo (o trabalho e o botão "Enviar agora"). */
        val trava = Mutex()

        /** Comandos por envio (cada envio fica bem abaixo do limite de 4 MB). */
        const val POR_ENVIO = 150

        private val bloco: Duration = Duration.ofDays(30)

        /** Ordem: medidas e sessões primeiro (rápidas), depois as séries. */
        val ordem = listOf(
            Tipo.PESO, Tipo.GORDURA, Tipo.MASSA_MAGRA, Tipo.EXERCICIO, Tipo.SONO,
            Tipo.PASSOS, Tipo.CALORIAS_ATIVAS, Tipo.FC, Tipo.FC_REPOUSO, Tipo.VFC, Tipo.SPO2,
        )
    }

    private var estadoAtual = JSONObject()

    suspend fun sincronizar(): Resumo = trava.withLock { executar() }

    private suspend fun executar(): Resumo {
        val e = try {
            fonte.estado()
        } catch (f: FalhaSaude) {
            enviarEstado(null, emptyMap(), f.codigo)
            return Resumo(erro = f.codigo)
        }
        if (!e.disponivel) {
            enviarEstado(e, emptyMap(), e.sdk)
            return Resumo(erro = e.sdk, estado = e)
        }
        estadoAtual = estado(e, emptyMap(), null)
        val inicio = agora()
        val lidos = linkedMapOf<Tipo, Int>()
        var erro: String? = null
        for (t in ordem) {
            if (t !in e.concedidos) continue
            try {
                lidos[t] = tipo(t, e.concedidos, inicio)
            } catch (f: FalhaSaude) {
                // O tipo com problema fica de fora; os outros continuam.
                erro = erro ?: f.codigo
            } catch (f: FalhaEnvio) {
                if (f.codigo == "dados_invalidos") {
                    erro = erro ?: f.codigo
                } else {
                    throw f
                }
            }
        }
        enviarEstado(e, lidos, erro)
        return Resumo(lidos, erro, e)
    }

    private fun estado(e: EstadoSaude?, lidos: Map<Tipo, Int>, erro: String?) = JSONObject().apply {
        put("sdk", e?.sdk ?: "erro")
        put("permissoes", JSONArray((e?.concedidos ?: emptySet()).map { it.codigo }.sorted()))
        put("segundoPlano", e?.segundoPlano ?: false)
        put("lidos", JSONObject(lidos.mapKeys { it.key.codigo }))
        put("ultimaLeitura", agora().toString())
        erro?.let { put("erro", it) }
        if (versao.isNotEmpty()) put("versao", versao)
    }

    /** O fim de toda leitura: o estado (vale também como "a ponte está viva"). */
    private suspend fun enviarEstado(e: EstadoSaude?, lidos: Map<Tipo, Int>, erro: String?) {
        estadoAtual = estado(e, lidos, erro)
        envio.enviar(JSONArray(), estadoAtual)
    }

    private suspend fun enviarTudo(comandos: List<JSONObject>) {
        for (parte in comandos.chunked(POR_ENVIO)) envio.enviar(JSONArray(parte), estadoAtual)
    }

    private suspend fun tipo(t: Tipo, permitidos: Set<Tipo>, ate: Instant): Int {
        val comandos = mutableListOf<JSONObject>()
        val token = marcadores.token(t)
        if (token == null) {
            val novo = fonte.token(t)
            val n = janela(t, ate.minus(Duration.ofDays(30)), ate, permitidos, comandos)
            enviarTudo(comandos)
            marcadores.gravar(t, novo)
            return n
        }
        var n = 0
        var atual: String = token
        val diasMudados = sortedSetOf<Instant>()
        var apagouAgregado = false
        while (true) {
            val p = fonte.alteracoes(atual)
            if (p.expirado) {
                val novo = fonte.token(t)
                comandos.clear()
                n = janela(t, ate.minus(Duration.ofDays(30)), ate, permitidos, comandos)
                enviarTudo(comandos)
                marcadores.gravar(t, novo)
                return n
            }
            val registros = p.registros.filter { it.origem !in pacotesProprios }
            if (t.agregado) {
                for (r in registros) {
                    (r.inicio ?: r.hora)?.let { diasMudados += diaUtc(it) }
                    r.fim?.let { diasMudados += diaUtc(it) }
                }
                fontes(t, registros.map { it.origem }, comandos)
                apagouAgregado = apagouAgregado || p.apagados.isNotEmpty()
            } else {
                n += importar(t, registros, permitidos, comandos)
                for (id in p.apagados) comandos += JSONObject().put("tipo", "apagado").put("id", id)
            }
            atual = p.proximo ?: atual
            if (!p.mais) break
        }
        if (apagouAgregado) {
            // A exclusão só traz o id: relê os últimos 30 dias do tipo.
            n += janela(t, ate.minus(Duration.ofDays(30)), ate, permitidos, comandos)
        } else if (diasMudados.isNotEmpty()) {
            // Dias seguidos viram uma janela só (um pedido por bloco).
            val dias = diasMudados.toList()
            var ini = dias.first()
            var fim = ini
            for (d in dias.drop(1) + listOf<Instant?>(null)) {
                if (d != null && Duration.between(fim, d) <= Duration.ofDays(1)) {
                    fim = d
                    continue
                }
                n += janela(t, ini.minus(Duration.ofHours(12)), minOf(fim.plus(Duration.ofHours(36)), ate), permitidos, comandos)
                if (d != null) {
                    ini = d
                    fim = d
                }
            }
        }
        enviarTudo(comandos)
        marcadores.gravar(t, atual)
        return n
    }

    private fun diaUtc(i: Instant): Instant = i.truncatedTo(ChronoUnit.DAYS)

    private fun data(i: Instant): LocalDate = i.atZone(zona()).toLocalDate()

    private fun horaCivil(i: Instant): Int = i.atZone(zona()).hour

    private fun fontes(t: Tipo, pacotes: Collection<String>, comandos: MutableList<JSONObject>) {
        val unicos = pacotes.distinct()
        if (unicos.isEmpty()) return
        comandos += JSONObject().put("tipo", "fontes").put("dado", t.codigo).put("pacotes", JSONArray(unicos))
    }

    /** Lê [de]..[ate] do tipo, em blocos de 30 dias. */
    private suspend fun janela(t: Tipo, de: Instant, ate: Instant, permitidos: Set<Tipo>, comandos: MutableList<JSONObject>): Int {
        var ini = de.truncatedTo(ChronoUnit.HOURS)
        var n = 0
        while (ini.isBefore(ate)) {
            val fim = minOf(ini.plus(bloco), ate)
            n += bloco(t, ini, fim, permitidos, comandos)
            ini = fim
        }
        return n
    }

    private fun hora(data: LocalDate, hora: Int, valores: Map<String, Double?>) = JSONObject().apply {
        put("data", data.toString())
        put("hora", hora)
        put("valores", JSONObject().apply { for ((k, v) in valores) put(k, v?.takeIf { it.isFinite() } ?: JSONObject.NULL) })
    }

    private suspend fun bloco(t: Tipo, de: Instant, ate: Instant, permitidos: Set<Tipo>, comandos: MutableList<JSONObject>): Int {
        when (t) {
            Tipo.PASSOS, Tipo.CALORIAS_ATIVAS, Tipo.FC -> {
                val ags = fonte.agregarHoras(t, de, ate)
                fontes(t, ags.flatMap { it.fontes }, comandos)
                val chaves = when (t) {
                    Tipo.PASSOS -> listOf("passos")
                    Tipo.CALORIAS_ATIVAS -> listOf("kcal")
                    else -> listOf("fcMin", "fcMedia", "fcMax", "fcAmostras")
                }
                val horas = ags.map { a ->
                    hora(
                        data(a.inicio),
                        horaCivil(a.inicio),
                        when (t) {
                            Tipo.PASSOS -> mapOf("passos" to a.valores["passos"])
                            Tipo.CALORIAS_ATIVAS -> mapOf("kcal" to a.valores["kcal"])
                            else -> mapOf(
                                "fcMin" to a.valores["min"],
                                "fcMedia" to a.valores["media"],
                                "fcMax" to a.valores["max"],
                                "fcAmostras" to a.valores["n"],
                            )
                        },
                    )
                }
                comandos += comandoHoras(chaves, de, ate, horas)
                return horas.size
            }
            Tipo.SPO2 -> {
                val rs = fonte.ler(t, de, ate)
                fontes(t, rs.map { it.origem }, comandos)
                val porHora = linkedMapOf<Pair<LocalDate, Int>, MutableList<Double>>()
                for (r in rs) {
                    val h = r.hora ?: continue
                    val v = r.valor ?: continue
                    if (r.origem in pacotesProprios) continue
                    porHora.getOrPut(data(h) to horaCivil(h)) { mutableListOf() } += v
                }
                val horas = porHora.map { (k, vs) -> hora(k.first, k.second, mapOf("spo2Min" to vs.min(), "spo2Media" to vs.average())) }
                comandos += comandoHoras(listOf("spo2Min", "spo2Media"), de, ate, horas)
                return rs.size
            }
            Tipo.FC_REPOUSO, Tipo.VFC -> {
                val rs = fonte.ler(t, de, ate)
                fontes(t, rs.map { it.origem }, comandos)
                val porDia = mutableMapOf<LocalDate, MutableList<Double>>()
                for (r in rs) {
                    val h = r.hora ?: continue
                    val v = r.valor ?: continue
                    if (r.origem in pacotesProprios) continue
                    porDia.getOrPut(data(h)) { mutableListOf() } += v
                }
                // Todos os dias da janela: o que sumiu na fonte volta a nulo.
                val valores = JSONObject()
                var d = data(de)
                while (!d.isAfter(data(ate))) {
                    valores.put(d.toString(), porDia[d]?.average() ?: JSONObject.NULL)
                    d = d.plusDays(1)
                }
                comandos += JSONObject().put("tipo", "dias").put(if (t == Tipo.FC_REPOUSO) "repouso" else "vfc", valores)
                return rs.size
            }
            Tipo.SONO, Tipo.EXERCICIO, Tipo.PESO, Tipo.GORDURA, Tipo.MASSA_MAGRA -> {
                val rs = fonte.ler(t, de, ate).filter { it.origem !in pacotesProprios }
                return importar(t, rs, permitidos, comandos)
            }
        }
    }

    private fun comandoHoras(chaves: List<String>, de: Instant, ate: Instant, horas: List<JSONObject>) = JSONObject().apply {
        put("tipo", "horas")
        put("chaves", JSONArray(chaves))
        put("de", de.toString())
        put("ate", ate.toString())
        put("horas", JSONArray(horas))
    }

    /** Sono, exercícios e medidas (com os números da noite/sessão). */
    private suspend fun importar(t: Tipo, rs: List<Registro>, permitidos: Set<Tipo>, comandos: MutableList<JSONObject>): Int {
        if (rs.isEmpty()) return 0
        // A SpO2 das noites: lida uma vez para o intervalo todo.
        var spo2 = emptyList<Registro>()
        if (t == Tipo.SONO && Tipo.SPO2 in permitidos) {
            val inicios = rs.mapNotNull { it.inicio }
            val fins = rs.mapNotNull { it.fim }
            if (inicios.isNotEmpty() && fins.isNotEmpty()) {
                spo2 = fonte.ler(Tipo.SPO2, inicios.min(), fins.max())
                    .filter { it.hora != null && it.valor != null }
                    .sortedBy { it.hora }
            }
        }
        for (r in rs) {
            val c = JSONObject().put("tipo", "registro").put("dado", t.codigo).put("registro", r.json())
            val ini = r.inicio
            val fim = r.fim
            if (ini != null && fim != null) {
                if (t == Tipo.EXERCICIO) {
                    val quais = permitidos.intersect(setOf(Tipo.PASSOS, Tipo.CALORIAS_ATIVAS, Tipo.FC))
                    if (quais.isNotEmpty()) c.put("resumo", JSONObject(fonte.resumir(quais, ini, fim)))
                } else if (t == Tipo.SONO) {
                    if (Tipo.FC in permitidos) {
                        fonte.resumir(setOf(Tipo.FC), ini, fim)["fcMedia"]?.let { c.put("fcMedia", it) }
                    }
                    val daNoite = spo2.filter { !it.hora!!.isBefore(ini) && it.hora.isBefore(fim) }.map { it.valor!! }
                    if (daNoite.isNotEmpty()) c.put("spo2", JSONArray(listOf(daNoite.min(), daNoite.average())))
                }
            }
            comandos += c
        }
        return rs.size
    }
}
