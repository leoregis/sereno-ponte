package br.com.orrico.ponte

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import java.time.Duration
import java.time.Instant
import kotlin.reflect.KClass

/**
 * O Health Connect do celular (a mesma leitura do SaudeConectada.kt do
 * Sereno Android). SÓ LEITURA: a ponte nunca grava no Health Connect.
 */
class HealthConnect(private val ctx: Context) : FonteSaude {
    companion object {
        const val PACOTE = "com.google.android.apps.healthdata"

        val classes: Map<Tipo, KClass<out Record>> = mapOf(
            Tipo.PASSOS to StepsRecord::class,
            Tipo.SONO to SleepSessionRecord::class,
            Tipo.FC to HeartRateRecord::class,
            Tipo.FC_REPOUSO to RestingHeartRateRecord::class,
            Tipo.VFC to HeartRateVariabilityRmssdRecord::class,
            Tipo.SPO2 to OxygenSaturationRecord::class,
            Tipo.EXERCICIO to ExerciseSessionRecord::class,
            Tipo.CALORIAS_ATIVAS to ActiveCaloriesBurnedRecord::class,
            Tipo.PESO to WeightRecord::class,
            Tipo.GORDURA to BodyFatRecord::class,
            Tipo.MASSA_MAGRA to LeanBodyMassRecord::class,
        )

        /** Todas as permissões que a ponte pede (leitura + segundo plano). */
        val permissoes: Set<String> =
            classes.values.map { HealthPermission.getReadPermission(it) }.toSet() +
                HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

        fun sdk(ctx: Context): String = when (HealthConnectClient.getSdkStatus(ctx, PACOTE)) {
            HealthConnectClient.SDK_AVAILABLE -> "disponivel"
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "atualizar"
            else -> if (Build.VERSION.SDK_INT >= 34) "indisponivel" else "instalar"
        }

        /** Código simples (sem a mensagem do sistema). */
        fun codigoDe(e: Exception): String = when (e) {
            is SecurityException -> "sem_permissao"
            is IllegalStateException ->
                if ((e.message ?: "").contains("quota", true) || (e.message ?: "").contains("rate", true)) "limite" else "erro"
            is android.os.RemoteException -> "limite"
            else -> "erro"
        }
    }

    private val cliente by lazy { HealthConnectClient.getOrCreate(ctx) }

    private suspend fun <T> chamar(bloco: suspend () -> T): T = try {
        bloco()
    } catch (e: FalhaSaude) {
        throw e
    } catch (e: Exception) {
        throw FalhaSaude(codigoDe(e))
    }

    override suspend fun estado(): EstadoSaude {
        val s = sdk(ctx)
        if (s != "disponivel") return EstadoSaude(s)
        return chamar {
            val concedidas = cliente.permissionController.getGrantedPermissions()
            EstadoSaude(
                sdk = s,
                concedidos = classes.filter { HealthPermission.getReadPermission(it.value) in concedidas }.keys,
                segundoPlano = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in concedidas,
            )
        }
    }

    override suspend fun token(tipo: Tipo): String = chamar {
        cliente.getChangesToken(ChangesTokenRequest(setOf(classes.getValue(tipo))))
    }

    override suspend fun alteracoes(token: String): Pagina = chamar {
        val registros = mutableListOf<Registro>()
        val apagados = mutableListOf<String>()
        var proximo = token
        var mais: Boolean
        var lote = 0
        do {
            val r = cliente.getChanges(proximo)
            if (r.changesTokenExpired) return@chamar Pagina(expirado = true)
            for (m in r.changes) {
                when (m) {
                    is UpsertionChange -> registro(m.record)?.let { registros += it }
                    is DeletionChange -> apagados += m.recordId
                }
            }
            proximo = r.nextChangesToken
            mais = r.hasMore
            lote++
            // Até ~5 páginas por vez: memória controlada; o resto vem do token devolvido.
        } while (mais && lote < 5)
        Pagina(registros, apagados, proximo, mais)
    }

    override suspend fun ler(tipo: Tipo, de: Instant, ate: Instant): List<Registro> = chamar {
        val saida = mutableListOf<Registro>()
        var pagina: String? = null
        do {
            val r = cliente.readRecords(
                ReadRecordsRequest(
                    recordType = classes.getValue(tipo),
                    timeRangeFilter = TimeRangeFilter.between(de, ate),
                    pageSize = 1000,
                    pageToken = pagina,
                ),
            )
            r.records.mapNotNullTo(saida) { registro(it) }
            pagina = r.pageToken
        } while (pagina != null)
        saida
    }

    override suspend fun agregarHoras(tipo: Tipo, de: Instant, ate: Instant): List<AgregadoHora> = chamar {
        val metricas: Map<String, AggregateMetric<*>> = when (tipo) {
            Tipo.PASSOS -> mapOf("passos" to StepsRecord.COUNT_TOTAL)
            Tipo.FC -> mapOf(
                "min" to HeartRateRecord.BPM_MIN,
                "media" to HeartRateRecord.BPM_AVG,
                "max" to HeartRateRecord.BPM_MAX,
                "n" to HeartRateRecord.MEASUREMENTS_COUNT,
            )
            Tipo.CALORIAS_ATIVAS -> mapOf("kcal" to ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
            else -> emptyMap()
        }
        if (metricas.isEmpty()) return@chamar emptyList()
        cliente.aggregateGroupByDuration(
            AggregateGroupByDurationRequest(
                metrics = metricas.values.toSet(),
                timeRangeFilter = TimeRangeFilter.between(de, ate),
                timeRangeSlicer = Duration.ofHours(1),
            ),
        ).mapNotNull { g ->
            val valores = mutableMapOf<String, Double>()
            for ((nome, m) in metricas) {
                numero(g.result[m])?.let { valores[nome] = it }
            }
            if (valores.isEmpty()) null else AgregadoHora(g.startTime, g.endTime, valores, g.result.dataOrigins.map { it.packageName })
        }
    }

    override suspend fun resumir(tipos: Set<Tipo>, de: Instant, ate: Instant): Map<String, Double> = chamar {
        val metricas = mutableMapOf<String, AggregateMetric<*>>()
        if (Tipo.PASSOS in tipos) metricas["passos"] = StepsRecord.COUNT_TOTAL
        if (Tipo.CALORIAS_ATIVAS in tipos) metricas["kcal"] = ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL
        if (Tipo.FC in tipos) {
            metricas["fcMedia"] = HeartRateRecord.BPM_AVG
            metricas["fcMax"] = HeartRateRecord.BPM_MAX
        }
        if (metricas.isEmpty()) return@chamar emptyMap()
        val r = cliente.aggregate(AggregateRequest(metrics = metricas.values.toSet(), timeRangeFilter = TimeRangeFilter.between(de, ate)))
        buildMap { for ((nome, m) in metricas) numero(r[m])?.let { put(nome, it) } }
    }

    private fun numero(v: Any?): Double? = when (v) {
        is Energy -> v.inKilocalories
        is Number -> v.toDouble()
        else -> null
    }

    /** Registro do Health Connect → o da ponte (só o que o Sereno usa). */
    private fun registro(r: Record): Registro? {
        val m = r.metadata
        val aparelho = m.device?.let { listOfNotNull(it.manufacturer, it.model).joinToString(" ").ifBlank { null } }
        fun base(tipo: Tipo) = Registro(
            id = m.id,
            tipo = tipo,
            origem = m.dataOrigin.packageName,
            alterado = m.lastModifiedTime,
            aparelho = aparelho,
        )
        return when (r) {
            is SleepSessionRecord -> base(Tipo.SONO).copy(
                inicio = r.startTime,
                fim = r.endTime,
                estagios = r.stages.map { Estagio(it.startTime, it.endTime, it.stage) },
            )
            is ExerciseSessionRecord -> base(Tipo.EXERCICIO).copy(
                inicio = r.startTime,
                fim = r.endTime,
                exercicio = r.exerciseType,
                titulo = r.title,
            )
            is StepsRecord -> base(Tipo.PASSOS).copy(inicio = r.startTime, fim = r.endTime)
            is HeartRateRecord -> base(Tipo.FC).copy(inicio = r.startTime, fim = r.endTime)
            is ActiveCaloriesBurnedRecord -> base(Tipo.CALORIAS_ATIVAS).copy(inicio = r.startTime, fim = r.endTime)
            is WeightRecord -> base(Tipo.PESO).copy(hora = r.time, valor = r.weight.inKilograms)
            is BodyFatRecord -> base(Tipo.GORDURA).copy(hora = r.time, valor = r.percentage.value)
            is LeanBodyMassRecord -> base(Tipo.MASSA_MAGRA).copy(hora = r.time, valor = r.mass.inKilograms)
            is OxygenSaturationRecord -> base(Tipo.SPO2).copy(hora = r.time, valor = r.percentage.value)
            is RestingHeartRateRecord -> base(Tipo.FC_REPOUSO).copy(hora = r.time, valor = r.beatsPerMinute.toDouble())
            is HeartRateVariabilityRmssdRecord -> base(Tipo.VFC).copy(hora = r.time, valor = r.heartRateVariabilityMillis)
            else -> null
        }
    }
}
