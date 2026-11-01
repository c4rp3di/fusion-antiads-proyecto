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

## Hallazgos de la primera prueba (es.pornhub.com en Chrome)
- Sus anuncios NO usan VAST/IMA/GPT: el reproductor pide los vídeos (.mp4) y las imágenes a adtng.com / trafficjunky.net y envía eventos a etahub.com. Por eso vast/stubs no actuaban.
- Se añadió la técnica «juguete»: esos .mp4 se sustituyen por un MP4 válido de 0,3 s (assets/anuncio_vacio.mp4).

## Segunda prueba (es.pornhub.com)
- Los scripts de anuncios (embeddedads, popunder, gtm) quedan vaciados desde el origen: no llegan a pedirse los .mp4 (por eso «juguete» = 0).
- `media.trafficjunky.net/delivery/js/abp/js1.js` parece un detector de bloqueadores (abp). Hipótesis: al vaciarlo, el sitio detecta bloqueo y no reproduce. Se añade la clave `permitir` en reglas.json: esa URL ya no se bloquea.
- Los eventos de etahub.com se repetían (mismo ndate): la respuesta vacía no llevaba cabeceras CORS y el emisor la tomaba por fallo. Ahora el bloqueo clásico responde con CORS y atiende el preflight.

## Tercera prueba: el pre-roll seguía saliendo
- El diagnóstico solo anotaba lo que las técnicas modificaban; no se veía qué peticiones pasaban sin tocar. Nuevo interruptor del Laboratorio: «Registrar vídeo y audio (diagnóstico)», que anota (req) las peticiones de vídeo/audio/HLS/archivos grandes con código, tipo y tamaño.
- El túnel solo enruta IPv4. Con el Modo Profundo activo, las consultas DNS de tipo AAAA se contestan «sin datos» (FiltroTrafico.filtrarAaaa) para que las apps usen IPv4 y pasen por el proxy.

## Cuarta prueba: de dónde sale el pre-roll de Pornhub
- Con «Registrar vídeo y audio» se vio: `evtubescms.phncdn.com/pre_videos/…/mp4_720.mp4` (206, 3,3 MB). El pre-roll lo sirve la propia CDN de Pornhub, así que ninguna lista de dominios lo cubre (y bloquear phncdn.com rompería el vídeo principal, que va por HLS en em-h.phncdn.com).
- Nueva clave `juguete` en reglas.json: URLs que se sustituyen por el MP4 válido de 0,3 s. Por defecto: `phncdn.com/pre_videos/`.
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
