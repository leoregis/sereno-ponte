package br.com.orrico.ponte

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Fonte do Health Connect em memória (dados FICTÍCIOS). */
class FonteFalsa(
    var estado: EstadoSaude = EstadoSaude("disponivel", Tipo.entries.toSet(), segundoPlano = true),
    val registros: MutableMap<Tipo, MutableList<Registro>> = mutableMapOf(),
    val horas: MutableMap<Tipo, List<AgregadoHora>> = mutableMapOf(),
    val paginas: MutableMap<String, Pagina> = mutableMapOf(),
    var resumo: Map<String, Double> = emptyMap(),
) : FonteSaude {
    val pedidosHoras = mutableListOf<Triple<Tipo, Instant, Instant>>()
    var falhaEm: Tipo? = null

    override suspend fun estado() = estado

    override suspend fun token(tipo: Tipo) = "t-${tipo.codigo}-novo"

    override suspend fun alteracoes(token: String) = paginas[token] ?: Pagina(proximo = token)

    override suspend fun ler(tipo: Tipo, de: Instant, ate: Instant): List<Registro> {
        if (tipo == falhaEm) throw FalhaSaude("sem_permissao")
        return registros[tipo].orEmpty().filter {
            val q = it.hora ?: it.inicio!!
            !q.isBefore(de) && q.isBefore(ate)
        }
    }

    override suspend fun agregarHoras(tipo: Tipo, de: Instant, ate: Instant): List<AgregadoHora> {
        pedidosHoras += Triple(tipo, de, ate)
        return horas[tipo].orEmpty().filter { !it.inicio.isBefore(de) && it.inicio.isBefore(ate) }
    }

    override suspend fun resumir(tipos: Set<Tipo>, de: Instant, ate: Instant) =
        resumo.filterKeys { k -> (k == "fcMedia" || k == "fcMax") && Tipo.FC in tipos || k == "passos" && Tipo.PASSOS in tipos || k == "kcal" && Tipo.CALORIAS_ATIVAS in tipos }
}

class MarcadoresMemoria : Marcadores {
    val tokens = mutableMapOf<Tipo, String>()
    override fun token(tipo: Tipo) = tokens[tipo]
    override fun gravar(tipo: Tipo, token: String?) {
        if (token == null) tokens.remove(tipo) else tokens[tipo] = token
    }
}

class EnvioFalso : Envio {
    val lotes = mutableListOf<Pair<JSONArray, JSONObject>>()
    var falha: String? = null
    var falhaQuando: (JSONArray) -> Boolean = { true }

    override suspend fun enviar(comandos: JSONArray, estado: JSONObject) {
        val f = falha
        if (f != null && falhaQuando(comandos)) throw FalhaEnvio(f)
        lotes += comandos to estado
    }

    val comandos: List<JSONObject>
        get() = lotes.flatMap { (c, _) -> (0 until c.length()).map { c.getJSONObject(it) } }
}

class SincronizadorTest {
    private val sp = ZoneId.of("America/Sao_Paulo")

    // 29/10/2026, 12h em São Paulo.
    private val agora = Instant.parse("2026-10-29T15:00:00Z")

    private fun sinc(fonte: FonteSaude, marcadores: Marcadores, envio: Envio) =
        Sincronizador(fonte, marcadores, envio, zona = { sp }, agora = { agora }, versao = "1.0.0")

    private fun so(vararg t: Tipo) = EstadoSaude("disponivel", t.toSet(), segundoPlano = true)

    @Test
    fun `primeira vez - 30 dias, horas no horario local, token so depois do envio`() = runTest {
        val fonte = FonteFalsa(
            estado = so(Tipo.PASSOS),
            horas = mutableMapOf(
                Tipo.PASSOS to listOf(
                    AgregadoHora(
                        Instant.parse("2026-10-28T10:00:00Z"),
                        Instant.parse("2026-10-28T11:00:00Z"),
                        mapOf("passos" to 1200.0),
                        listOf("com.xiaomi.wearable"),
                    ),
                ),
            ),
        )
        val m = MarcadoresMemoria()
        val envio = EnvioFalso()
        val r = sinc(fonte, m, envio).sincronizar()

        assertEquals(1, r.lidos[Tipo.PASSOS])
        assertEquals(Triple(Tipo.PASSOS, agora.minus(Duration.ofDays(30)), agora), fonte.pedidosHoras.single())
        val horas = envio.comandos.single { it.getString("tipo") == "horas" }
        assertEquals("passos", horas.getJSONArray("chaves").getString(0))
        val h = horas.getJSONArray("horas").getJSONObject(0)
        // 10h UTC = 7h em São Paulo.
        assertEquals("2026-10-28", h.getString("data"))
        assertEquals(7, h.getInt("hora"))
        assertEquals(1200.0, h.getJSONObject("valores").getDouble("passos"), 0.0)
        val fontes = envio.comandos.single { it.getString("tipo") == "fontes" }
        assertEquals("com.xiaomi.wearable", fontes.getJSONArray("pacotes").getString(0))
        assertEquals("t-passos-novo", m.tokens[Tipo.PASSOS])
        // O último envio leva o estado (permissões e o que leu).
        val estado = envio.lotes.last().second
        assertEquals("passos", estado.getJSONArray("permissoes").getString(0))
        assertEquals(1, estado.getJSONObject("lidos").getInt("passos"))
    }

    @Test
    fun `sem rede - o token nao avanca`() = runTest {
        val fonte = FonteFalsa(estado = so(Tipo.PASSOS))
        val m = MarcadoresMemoria()
        val envio = EnvioFalso().apply { falha = "rede" }
        try {
            sinc(fonte, m, envio).sincronizar()
            fail("devia falhar")
        } catch (f: FalhaEnvio) {
            assertEquals("rede", f.codigo)
        }
        assertNull(m.tokens[Tipo.PASSOS])
    }

    @Test
    fun `alteracao de passos - rele so os dias que mudaram`() = runTest {
        val dia = Instant.parse("2026-10-27T13:00:00Z")
        val fonte = FonteFalsa(
            estado = so(Tipo.PASSOS),
            paginas = mutableMapOf(
                "t1" to Pagina(
                    registros = listOf(Registro("p1", Tipo.PASSOS, "com.xiaomi.wearable", inicio = dia, fim = dia.plusSeconds(600))),
                    proximo = "t2",
                ),
            ),
        )
        val m = MarcadoresMemoria().apply { tokens[Tipo.PASSOS] = "t1" }
        val envio = EnvioFalso()
        sinc(fonte, m, envio).sincronizar()

        val meiaNoite = Instant.parse("2026-10-27T00:00:00Z")
        // Janela do dia (UTC) com folga: -12 h e +36 h, começando na hora cheia.
        assertEquals(Triple(Tipo.PASSOS, meiaNoite.minus(Duration.ofHours(12)), meiaNoite.plus(Duration.ofHours(36))), fonte.pedidosHoras.single())
        assertEquals("t2", m.tokens[Tipo.PASSOS])
    }

    @Test
    fun `sono - fc da noite e spo2 so dentro da noite`() = runTest {
        val ini = Instant.parse("2026-10-28T02:00:00Z")
        val fim = Instant.parse("2026-10-28T09:00:00Z")
        val fonte = FonteFalsa(
            estado = so(Tipo.SONO, Tipo.SPO2, Tipo.FC),
            registros = mutableMapOf(
                Tipo.SONO to mutableListOf(
                    Registro("s1", Tipo.SONO, "com.xiaomi.wearable", inicio = ini, fim = fim,
                        estagios = listOf(Estagio(ini, fim, 4))),
                ),
                Tipo.SPO2 to mutableListOf(
                    Registro("o1", Tipo.SPO2, "com.xiaomi.wearable", hora = ini.plusSeconds(3600), valor = 93.0),
                    Registro("o2", Tipo.SPO2, "com.xiaomi.wearable", hora = ini.plusSeconds(7200), valor = 97.0),
                    Registro("o3", Tipo.SPO2, "com.xiaomi.wearable", hora = fim.plusSeconds(3600), valor = 80.0),
                ),
            ),
            resumo = mapOf("fcMedia" to 58.2),
        )
        val envio = EnvioFalso()
        sinc(fonte, MarcadoresMemoria(), envio).sincronizar()

        val sono = envio.comandos.single { it.getString("tipo") == "registro" && it.getString("dado") == "sono" }
        assertEquals(58.2, sono.getDouble("fcMedia"), 0.0)
        assertEquals(93.0, sono.getJSONArray("spo2").getDouble(0), 0.0)
        assertEquals(95.0, sono.getJSONArray("spo2").getDouble(1), 0.0)
        val reg = sono.getJSONObject("registro")
        assertEquals("s1", reg.getString("id"))
        assertEquals("2026-10-28T02:00:00Z", reg.getJSONArray("estagios").getJSONArray(0).getString(0))
    }

    @Test
    fun `apagado no Health Connect vira comando apagado`() = runTest {
        val fonte = FonteFalsa(
            estado = so(Tipo.PESO),
            paginas = mutableMapOf("t1" to Pagina(apagados = listOf("peso-9"), proximo = "t2")),
        )
        val m = MarcadoresMemoria().apply { tokens[Tipo.PESO] = "t1" }
        val envio = EnvioFalso()
        sinc(fonte, m, envio).sincronizar()
        val c = envio.comandos.single { it.getString("tipo") == "apagado" }
        assertEquals("peso-9", c.getString("id"))
    }

    @Test
    fun `fc de repouso - todos os dias da janela, nulo onde nao ha`() = runTest {
        val fonte = FonteFalsa(
            estado = so(Tipo.FC_REPOUSO),
            registros = mutableMapOf(
                Tipo.FC_REPOUSO to mutableListOf(
                    Registro("r1", Tipo.FC_REPOUSO, "com.xiaomi.wearable", hora = Instant.parse("2026-10-28T12:00:00Z"), valor = 60.0),
                    Registro("r2", Tipo.FC_REPOUSO, "com.xiaomi.wearable", hora = Instant.parse("2026-10-28T13:00:00Z"), valor = 62.0),
                ),
            ),
        )
        val envio = EnvioFalso()
        sinc(fonte, MarcadoresMemoria(), envio).sincronizar()
        val dias = envio.comandos.single { it.getString("tipo") == "dias" }.getJSONObject("repouso")
        assertEquals(61.0, dias.getDouble("2026-10-28"), 0.0)
        assertTrue(dias.isNull("2026-10-27"))
        assertEquals(31, dias.length())
    }

    @Test
    fun `o que a propria ponte gravaria fica de fora`() = runTest {
        val fonte = FonteFalsa(
            estado = so(Tipo.PESO),
            registros = mutableMapOf(
                Tipo.PESO to mutableListOf(
                    Registro("w1", Tipo.PESO, "br.com.orrico.ponte", hora = Instant.parse("2026-10-28T12:00:00Z"), valor = 80.0),
                    Registro("w2", Tipo.PESO, "com.renpho.health", hora = Instant.parse("2026-10-28T12:00:00Z"), valor = 81.0),
                ),
            ),
        )
        val envio = EnvioFalso()
        sinc(fonte, MarcadoresMemoria(), envio).sincronizar()
        val ids = envio.comandos.filter { it.getString("tipo") == "registro" }.map { it.getJSONObject("registro").getString("id") }
        assertEquals(listOf("w2"), ids)
    }

    @Test
    fun `um tipo com problema nao para os outros`() = runTest {
        val fonte = FonteFalsa(
            estado = so(Tipo.PESO, Tipo.PASSOS),
            registros = mutableMapOf(Tipo.PESO to mutableListOf()),
        ).apply { falhaEm = Tipo.PESO }
        val m = MarcadoresMemoria()
        val envio = EnvioFalso()
        val r = sinc(fonte, m, envio).sincronizar()
        assertEquals("sem_permissao", r.erro)
        assertNull(m.tokens[Tipo.PESO])
        assertEquals("t-passos-novo", m.tokens[Tipo.PASSOS])
        assertEquals("sem_permissao", envio.lotes.last().second.getString("erro"))
    }

    @Test
    fun `dado recusado pelo servidor fica visivel e o tipo nao avanca`() = runTest {
        val fonte = FonteFalsa(
            estado = so(Tipo.PESO),
            registros = mutableMapOf(
                Tipo.PESO to mutableListOf(
                    Registro("w2", Tipo.PESO, "com.renpho.health", hora = Instant.parse("2026-10-28T12:00:00Z"), valor = 81.0),
                ),
            ),
        )
        val m = MarcadoresMemoria()
        val envio = EnvioFalso().apply {
            falha = "dados_invalidos"
            falhaQuando = { it.length() > 0 }
        }
        val r = sinc(fonte, m, envio).sincronizar()
        assertEquals("dados_invalidos", r.erro)
        assertNull(m.tokens[Tipo.PESO])
    }

    @Test
    fun `Health Connect indisponivel - manda so o estado`() = runTest {
        val fonte = FonteFalsa(estado = EstadoSaude("instalar"))
        val envio = EnvioFalso()
        val r = sinc(fonte, MarcadoresMemoria(), envio).sincronizar()
        assertEquals("instalar", r.erro)
        assertEquals(0, envio.lotes.single().first.length())
        assertEquals("instalar", envio.lotes.single().second.getString("sdk"))
    }

    @Test
    fun `muitos comandos vao em varios envios`() = runTest {
        val base = Instant.parse("2026-10-01T12:00:00Z")
        val fonte = FonteFalsa(
            estado = so(Tipo.PESO),
            registros = mutableMapOf(
                Tipo.PESO to (0 until 400).map {
                    Registro("w$it", Tipo.PESO, "com.renpho.health", hora = base.plusSeconds(it * 60L), valor = 80.0)
                }.toMutableList(),
            ),
        )
        val envio = EnvioFalso()
        sinc(fonte, MarcadoresMemoria(), envio).sincronizar()
        assertEquals(400, envio.comandos.size)
        assertTrue(envio.lotes.all { it.first.length() <= Sincronizador.POR_ENVIO })
    }
}
