package com.vigia.adblock

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** Actualización diaria de las listas en segundo plano. */
class ActualizadorWorker(contexto: Context, parametros: WorkerParameters) : CoroutineWorker(contexto, parametros) {
    override suspend fun doWork(): Result {
        val r = ListasManager.actualizar(applicationContext)
        return if (r.total > 0 && r.errores == r.total) Result.retry() else Result.success()
    }
}

object Programador {
    private const val NOMBRE = "adblock_listas_diario"

    /**
     * Una vez al día, hacia las 4:00 (Android puede retrasarlo para ahorrar batería), con Wi-Fi si el usuario así
     * lo pidió. [reemplazar] = true cuando cambia un ajuste; false al arrancar, para no mover la hora.
     */
    fun programar(c: Context, reemplazar: Boolean) {
        val red = if (Ajustes.soloWifi(c)) NetworkType.UNMETERED else NetworkType.CONNECTED
        val restricciones = Constraints.Builder().setRequiredNetworkType(red).build()
        val ahora = Calendar.getInstance()
        val objetivo = Calendar.getInstance()
        objetivo.set(Calendar.HOUR_OF_DAY, 4)
        objetivo.set(Calendar.MINUTE, 0)
        objetivo.set(Calendar.SECOND, 0)
        objetivo.set(Calendar.MILLISECOND, 0)
        if (objetivo.before(ahora)) objetivo.add(Calendar.DAY_OF_YEAR, 1)
        val retraso = objetivo.timeInMillis - ahora.timeInMillis
        val peticion = PeriodicWorkRequestBuilder<ActualizadorWorker>(24, TimeUnit.HOURS)
            .setConstraints(restricciones)
            .setInitialDelay(retraso, TimeUnit.MILLISECONDS)
            .build()
        val politica = if (reemplazar) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP
        WorkManager.getInstance(c.applicationContext).enqueueUniquePeriodicWork(NOMBRE, politica, peticion)
    }
}
