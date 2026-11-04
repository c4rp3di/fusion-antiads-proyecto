# AntiAds · laboratorio de técnicas contra anuncios de vídeo

App aparte (com.vigia.antiads) para probar técnicas sin tocar AdBlock Soberano+. Parte de una copia del Soberano+ y añade:
- Pantalla **Laboratorio**: interruptor por técnica, contadores, diagnóstico (copiar/compartir) y reglas por sitio (JSON importable).
- Proxy ampliado (módulo :vigia-mitm de ESTE proyecto): respuestas inventadas con CORS, script al inicio de la página con nonce en la CSP, reescritura de listas HLS.
- Su propia CA («AntiAds CA (local)»): hay que exportarla e instalarla aparte de la del Soberano+.

Solo puede haber una VPN activa: desactiva el Soberano+ antes de usar AntiAds.

## Qué probar
1. Ajustes › Modo Profundo (MitM): activar, exportar el CA, instalarlo, reactivar.
2. En el móvil, abre Chrome (no Brave) con la web.
3. En el Laboratorio, mira que sube «conexiones descifradas» y qué técnica actúa.
4. Si falla: Laboratorio › Compartir diagnóstico, y pásalo.

## Límites conocidos
- Las técnicas de red (VAST, scripts sustitutos, bloqueo clásico) actúan por URL en cualquier página; las de página (YouTube, saltador, CSS, HLS) por sitio, según reglas.json.
- Los anuncios metidos en el propio flujo de vídeo (SSAI) no se tocan, salvo HLS con marcas CUE-OUT/CUE-IN.
- La app de YouTube no acepta nuestra CA: solo YouTube web en Chrome.
- Sin probar en dispositivo: los scripts sustitutos y el de YouTube están escritos de memoria sobre cómo se comportan GPT, IMA y el reproductor.

## Hallazgos de las primeras pruebas (web de vídeos con publicidad, en Chrome)
- Sus anuncios NO usan VAST/IMA/GPT: el reproductor pide los vídeos (.mp4) y las imágenes a redes publicitarias externas y envía eventos a un dominio de analítica. Por eso vast/stubs no actuaban.
- Se añadió la técnica «juguete»: esos .mp4 se sustituyen por un MP4 válido de 0,3 s (assets/anuncio_vacio.mp4).
- Una ruta con «abp» (`/delivery/js/abp`) parece un detector de bloqueadores. Hipótesis: al vaciarlo, el sitio detecta bloqueo y no reproduce. Por eso existe la clave `permitir` en reglas.json: esas URLs no se bloquean.
- Los eventos de analítica se repetían (mismo sello de tiempo): la respuesta vacía no llevaba cabeceras CORS y el emisor la tomaba por fallo. Ahora el bloqueo clásico responde con CORS y atiende el preflight.
- Con «Registrar vídeo y audio» se vio que el pre-roll lo sirve la propia CDN de la web, bajo la ruta `/pre_videos/`, así que ninguna lista de dominios lo cubre (y bloquear ese dominio rompería el vídeo principal, que va por HLS).
- Nueva clave `juguete` en reglas.json: URLs que se sustituyen por el MP4 válido de 0,3 s. Por defecto: `/pre_videos/`.
- Los vídeos inventados atienden Range (206/416), porque el navegador pide «bytes=0-».

## Quinta prueba: el pre-roll seguía y no había petición
- No aparecía `pre_videos` en la red y `adroll_video_loadtime` marcaba 9-20 ms: casi seguro lo servía la caché de Chrome (se descargó en la prueba anterior). Probar en una pestaña de incognito o borrando la caché de Chrome.
- Nuevo interruptor «Registrar TODAS las peticiones» por si no es la caché.

## Diagnóstico ampliado
- **Todas las conexiones de todas las apps** (Laboratorio › «Todas las conexiones»): el núcleo ya emitía un evento por conexión (app, destino, puerto, SNI, bytes). Se agrupan por app y destino (memoria acotada) y se nombran con el DNS observado. Solo metadatos; nunca contenido. Requiere Modo Profundo (en modo DNS el túnel no ve conexiones).
- **Telemetría de página**: script que cuenta errores JS, recursos fallidos, bloqueos de CSP, eventos del vídeo y recursos servidos desde la caché; envía un aviso cada ≥3 s a /__antiads/t del propio sitio, que el proxy intercepta (no sale a internet).
- **Informe**: entorno, avisos, conteo corregido (yt/skip/stubs), resumen por app, resumen por página y línea de tiempo con 200/450/1000/2500 líneas, o guardado en archivo. «Informe privado» (activado por defecto) recorta URLs.
- **Consumo**: las capturas son opt-in y se apagan solas a los 30 min; todo en memoria; sin escritura en disco salvo «Guardar informe»; los saltadores de JS ya no trabajan con la pestaña oculta y van a 500 ms / 1 s.

## Reorganización de pantallas
- **Ajustes › Laboratorio** (plegable): paneles «Técnicas» y «Reglas por sitio», también plegables. Es lo que CAMBIA lo que ve la web.
- **Diagnóstico** (pantalla principal, donde estaba «Laboratorio»): paneles «Estado», «Capturas» y «Registro e informe». Es lo que OBSERVA. Las casillas de captura (conexiones, peticiones, todas, página, informe privado) viven aquí.

## Diagnóstico unificado y botón de la notificación
- **Un único registro** (Registro.kt › Diagnostico): hora, categoría/subtipo, app, dominio, texto y gravedad. Categorías: bloqueos (dns, sni, proxy, ip), respuestas inventadas, páginas, peticiones, conexiones, DNS por app, certificado, sistema, errores. Los repetidos (bloqueos, conexiones, DNS) se agrupan por app+dominio con «×n». Solo en memoria.
- **Siempre registrado (barato):** bloqueos por DNS/SNI/proxy, errores, avisos de certificado, eventos del sistema (VPN, listas, cambios de modo). **Opt-in (30 min):** conexiones de todas las apps, peticiones, telemetría de página, DNS por app.
- **Filtros** en la pantalla Diagnóstico: categorías, gravedad, periodo, app y texto. El informe (copiar/compartir/guardar) respeta los filtros y añade «Bloqueados por dominio» y «Eventos por categoría».
- Núcleo: `ObservadorTrafico.alBloqueoDe(dominio, capa, app)` (por defecto llama a `alBloqueo`) y `NucleoVpnService.reconfigurar()` (reabre el túnel con la configuración nueva sin parar el servicio).
- **Notificación:** segundo botón «Activar/Apagar profundo» (`NotificacionSesion.Datos.textoExtra/accionExtra`); cambia el ajuste y reconfigura el túnel. La app de la conexión llega a los eventos del proxy mediante un ThreadLocal fijado en `alAbrirConexion`.

## Diagnóstico: más intuitivo y con más información
- **Líneas tocables** con iconos por categoría, colores por gravedad (rojo error, ámbar aviso) y nombres claros («bloqueado (DNS)», «anuncio de juguete»…). Al tocar: detalle y acciones (bloquear/permitir dominio, dejar la app sin descifrar, ver ficha, filtrar, copiar).
- **Filtros rápidos**: Todo · Problemas · Anuncios · Red · Página, además de los filtros finos.
- **Ficha por app**: descifrado, conexiones, bloqueos, DNS, rastreadores, avisos y errores.
- **Apps y rastreadores**: ranking por destinos de las listas contactados (mejor con «Todas las conexiones»).
- **Sesiones de prueba**: iniciar/terminar activa las capturas, resume solo ese rato y permite comparar las dos últimas.
- **Efectividad de las reglas**: veces que actuó cada patrón de reglas.json (○ = nunca).
- **Comprobaciones**: VPN, Modo Profundo, certificado, DNS privado estricto, batería, notificaciones, listas y otra VPN activa, con botón para ir a arreglarlo.

## Lote de mantenimiento
- Caché de certificados con tope (LRU, 400). `CaManager.regenerar()` y botón «Regenerar certificado CA» en Ajustes (con aviso de quitar la antigua de Android).
- `vigia-mitm/src/test/.../HttpCodecTest.kt`: pruebas de JVM del códec HTTP (no se ejecutan con `assembleDebug`).
- Ajustes › Laboratorio › Sitios: añadir, editar y quitar sitios sin importar JSON. Guarda una copia editable de las reglas; «Volver a las reglas integradas» la borra.
