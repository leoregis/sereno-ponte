package br.com.orrico.ponte

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** A leitura de cada hora, com internet (o HyperOS pode adiar um pouco). */
class Trabalho(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = when (Leitura.executar(applicationContext)) {
        Leitura.Fim.OK, Leitura.Fim.DESLIGADA, Leitura.Fim.SEM_TOKEN -> Result.success()
        Leitura.Fim.FALHOU -> Result.retry()
    }

    companion object {
        private const val NOME = "sereno_ponte_hora"

        fun agendar(ctx: Context) {
            val pedido = PeriodicWorkRequestBuilder<Trabalho>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx.applicationContext)
                .enqueueUniquePeriodicWork(NOME, ExistingPeriodicWorkPolicy.KEEP, pedido)
        }

        fun cancelar(ctx: Context) {
            WorkManager.getInstance(ctx.applicationContext).cancelUniqueWork(NOME)
        }
    }
}

/** Uma leitura completa (o trabalho de cada hora e o botão "Enviar agora"). */
object Leitura {
    enum class Fim { OK, FALHOU, DESLIGADA, SEM_TOKEN }

    suspend fun executar(ctx: Context): Fim {
        val armazem = Armazem(ctx)
        val token = armazem.tokenAparelho ?: return Fim.SEM_TOKEN
        val sinc = Sincronizador(
            fonte = HealthConnect(ctx),
            marcadores = armazem,
            envio = Servidor().envio(token, { ZoneId.systemDefault().id }, BuildConfig.VERSION_NAME),
            versao = BuildConfig.VERSION_NAME,
        )
        return try {
            val r = sinc.sincronizar()
            armazem.ultimoEnvio = System.currentTimeMillis()
            armazem.ultimoErro = r.erro
            armazem.ultimosLidos = JSONObject(r.lidos.mapKeys { it.key.codigo })
            Fim.OK
        } catch (f: FalhaEnvio) {
            if (f.codigo == "revogado") {
                // Desligada no web: volta à tela do código.
                armazem.esquecer()
                Trabalho.cancelar(ctx)
                Fim.DESLIGADA
            } else {
                armazem.ultimoErro = f.codigo
                Fim.FALHOU
            }
        }
    }
}
