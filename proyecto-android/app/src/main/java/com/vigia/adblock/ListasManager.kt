package com.vigia.adblock

import android.content.Context
import android.net.ConnectivityManager
import com.vigia.core.red.UtilRed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** Estado compartido entre pantalla, servicio y tareas en segundo plano (todo ocurre en el mismo proceso). */
object AdBlockEstado {
    val filtro = FiltroAdBlock()
    val ambito: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

/** Descarga, valida, compila y guarda las listas. El filtro compilado se cachea en un archivo para arrancar rápido. */
object ListasManager {
    class Resumen(val total: Int, val actualizadas: Int, val sinCambios: Int, val errores: Int, val dominios: Int, val mensajes: List<String>)

    /** Texto de progreso para la pantalla (vacío = nada en curso). */
    val estado = MutableStateFlow("")
    val actualizando = MutableStateFlow(false)

    private const val LIMITE_BYTES = 80L * 1024L * 1024L
    private const val MIN_ENTRADAS = 50
    private val enCurso = AtomicBoolean(false)
    private val cerrojoCompilar = Any()

    private fun carpeta(c: Context): File {
        val d = File(c.applicationContext.filesDir, "listas")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun archivoLista(c: Context, id: String) = File(carpeta(c), id + ".txt")

    private fun archivoCache(c: Context) = File(carpeta(c), "filtro.bin")

    fun hayListasDescargadas(c: Context): Boolean {
        return Ajustes.listasActivasDef(c).any { archivoLista(c, it.id).exists() }
    }

    fun redMedida(c: Context): Boolean {
        val cm = c.applicationContext.getSystemService(ConnectivityManager::class.java)
        return cm.isActiveNetworkMetered
    }

    /** Carga el filtro desde la caché (o recompilando) si aún no está en memoria. No bloquea al llamante. */
    fun asegurarCargado(c: Context) {
        if (AdBlockEstado.filtro.cargado) return
        val app = c.applicationContext
        AdBlockEstado.ambito.launch {
            synchronized(cerrojoCompilar) {
                if (AdBlockEstado.filtro.cargado) return@launch
                val t = AdBlockEstado.filtro.leerCache(archivoCache(app))
                if (t != null) {
                    AdBlockEstado.filtro.reemplazar(t)
                } else if (hayListasDescargadas(app) || Ajustes.bloqueExtra(app).isNotBlank()) {
                    compilar(app)
                }
            }
        }
    }

    fun actualizarAsync(c: Context) {
        val app = c.applicationContext
        AdBlockEstado.ambito.launch { actualizar(app) }
    }

    fun reconstruirAsync(c: Context) {
        val app = c.applicationContext
        AdBlockEstado.ambito.launch {
            synchronized(cerrojoCompilar) { compilar(app) }
        }
    }

    suspend fun actualizar(c: Context): Resumen = withContext(Dispatchers.IO) {
        val app = c.applicationContext
        if (!enCurso.compareAndSet(false, true)) {
            return@withContext Resumen(0, 0, 0, 0, 0, listOf("Ya hay una actualización en curso"))
        }
        try {
            actualizando.value = true
            val activas = Ajustes.listasActivasDef(app)
            var ok = 0
            var igual = 0
            var err = 0
            val mensajes = ArrayList<String>()
            for ((i, lista) in activas.withIndex()) {
                estado.value = "Descargando " + lista.nombre + " (" + (i + 1) + "/" + activas.size + ")…"
                try {
                    if (descargar(app, lista)) ok++ else igual++
                } catch (e: Exception) {
                    err++
                    mensajes.add(lista.nombre + ": " + (e.message ?: e.javaClass.simpleName))
                }
            }
            var dominios = AdBlockEstado.filtro.totalDominios
            if (ok > 0 || !AdBlockEstado.filtro.cargado) {
                estado.value = "Compilando el filtro…"
                synchronized(cerrojoCompilar) { dominios = compilar(app) }
            }
            if (activas.isEmpty() || err < activas.size) Ajustes.setUltimaActualizacion(app, System.currentTimeMillis())
            estado.value = if (err == 0) "Listas al día · " + dominios + " dominios"
            else "Actualizado con " + err + " error(es): " + mensajes.joinToString("; ")
            Resumen(activas.size, ok, igual, err, dominios, mensajes)
        } finally {
            actualizando.value = false
            enCurso.set(false)
        }
    }

    /** Devuelve true si la lista cambió, false si el servidor dijo «sin cambios». Lanza excepción si falla. */
    private fun descargar(c: Context, lista: ListaDef): Boolean {
        if (!lista.url.startsWith("https://")) throw IllegalArgumentException("solo se admiten URL https")
        val destino = archivoLista(c, lista.id)
        val conn = URL(lista.url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "AdBlockSoberano/1.0")
            if (destino.exists()) {
                val etag = Ajustes.etag(c, lista.id)
                val mod = Ajustes.modificado(c, lista.id)
                if (etag != null) conn.setRequestProperty("If-None-Match", etag)
                if (mod != null) conn.setRequestProperty("If-Modified-Since", mod)
            }
            val codigo = conn.responseCode
            if (codigo == HttpURLConnection.HTTP_NOT_MODIFIED) return false
            if (codigo != HttpURLConnection.HTTP_OK) throw IllegalStateException("el servidor respondió " + codigo)

            val tmp = File(destino.parentFile, lista.id + ".descarga")
            var bytes = 0L
            conn.inputStream.use { entrada ->
                tmp.outputStream().buffered().use { salida ->
                    val buf = ByteArray(32768)
                    while (true) {
                        val n = entrada.read(buf)
                        if (n < 0) break
                        bytes += n
                        if (bytes > LIMITE_BYTES) throw IllegalStateException("lista demasiado grande")
                        salida.write(buf, 0, n)
                    }
                }
            }
            val validas = contarEntradas(tmp)
            if (validas < MIN_ENTRADAS) {
                tmp.delete()
                throw IllegalStateException("no parece una lista válida (" + validas + " entradas)")
            }
            if (!tmp.renameTo(destino)) {
                destino.delete()
                if (!tmp.renameTo(destino)) throw IllegalStateException("no se pudo guardar la lista")
            }
            Ajustes.setCabecerasCache(c, lista.id, conn.getHeaderField("ETag"), conn.getHeaderField("Last-Modified"))
            return true
        } finally {
            conn.disconnect()
        }
    }

    private fun contarEntradas(f: File): Int {
        var n = 0
        f.useLines(Charsets.UTF_8) { lineas ->
            for (l in lineas) {
                if (ParserListas.procesarLinea(l) != null) {
                    n++
                    if (n >= MIN_ENTRADAS) break
                }
            }
        }
        return n
    }

    /** Procesa las listas activas y los dominios propios, guarda la caché y cambia el filtro en caliente. Devuelve el nº de dominios. */
    private fun compilar(c: Context): Int {
        val bloqueo = ConjuntoHash64()
        val permitido = ConjuntoHash64()
        for (lista in Ajustes.listasActivasDef(c)) {
            val f = archivoLista(c, lista.id)
            if (!f.exists()) continue
            f.useLines(Charsets.UTF_8) { lineas ->
                for (l in lineas) {
                    val e = ParserListas.procesarLinea(l) ?: continue
                    if (e.permitido) permitido.agregar(HashDominio.completo(e.dominio))
                    else bloqueo.agregar(HashDominio.completo(e.dominio))
                }
            }
        }
        for (l in Ajustes.bloqueExtra(c).split('\n')) {
            val d = UtilRed.normalizarDominio(l) ?: continue
            bloqueo.agregar(HashDominio.completo(d))
        }
        if (Ajustes.bloquearDnsCifrado(c)) {
            for (d in DnsCifrado.DOMINIOS) bloqueo.agregar(HashDominio.completo(d))
        }
        for (l in Ajustes.permitidosExtra(c).split('\n')) {
            val d = UtilRed.normalizarDominio(l) ?: continue
            permitido.agregar(HashDominio.completo(d))
        }
        val tablas = FiltroAdBlock.Tablas(bloqueo, permitido)
        try {
            AdBlockEstado.filtro.guardarCache(archivoCache(c), tablas)
        } catch (e: Exception) {
            // Sin caché el filtro funciona igual; solo tardará más en arrancar.
        }
        AdBlockEstado.filtro.reemplazar(tablas)
        return bloqueo.tamano
    }
}
