# AdBlock Soberano+ · pendientes

Ideas guardadas para más adelante. Nada de esto está hecho todavía.

## Corregir lo que está flojo
- [ ] **1. HTTP plano (puerto 80) en modo MitM.** Con el MitM activo el DNS no bloquea y el puerto 80 no pasa por el proxy, así que esos anuncios se cuelan (el 1.0 sí los bloqueaba). Desviar también el 80 al proxy, sin TLS, reutilizando HttpCodec.
- [ ] **2. Lista blanca más fina.** Hoy una app entra entera al primer fallo de handshake. Guardar pareja app + host, con caducidad para reintentar pasado un tiempo.
- [ ] **3. Arreglo de «desactivar protección» en el AdBlock 1.0.** El fallo (stopService con servicio enlazado) está en el núcleo compartido; el 1.0 sigue con él.
- [ ] **4. Topes en memoria.** La caché de certificados por dominio (CaManager) crece sin límite: usar una caché LRU.

## Consumo
- [ ] **5. MitM solo en navegadores por defecto.** Casi ninguna otra app acepta una CA de usuario; cada una pierde una conexión al primer intento. Opción «solo navegadores» y el resto va directo.

## Calidad del filtrado
- [ ] **6. Reglas cosméticas reales.** Ahora hay una lista fija de selectores genéricos. Leer las reglas cosméticas de EasyList y aplicarlas por dominio.
- [ ] **7. IPv6.** El túnel solo captura IPv4: el IPv6 se salta el MitM y el bloqueo por SNI (el DNS sí se filtra).

## Seguridad y mantenimiento
- [ ] **8. Regenerar la CA** desde Ajustes, y aviso para quitarla al desinstalar la app.
- [ ] **9. Pruebas automáticas de HttpCodec** (chunked, Expect: 100-continue, respuestas sin longitud).
- [ ] **10. R8 con reglas para BouncyCastle** para reducir el APK.

## A valorar
- [ ] **11. Anuncios de vídeo previos (pre-roll) y reglas por sitio.** Construir la base en el Modo Profundo:
  - **VAST vacío válido** como respuesta a las peticiones de anuncios de vídeo, en vez de 0 bytes.
  - **Inyección de scripts** (scriptlets) al principio de la página, ajustando la CSP como ya se hace con el CSS.
  - **Reglas por sitio en un archivo actualizable** (qué vaciar, qué inyectar), sin recompilar.
  - Primer caso: YouTube en el navegador (el script que borra `adPlacements`/`playerAds` de la respuesta del reproductor; frágil, YouTube cambia a menudo). Segundo: webs de adultos.
  - **Dato de prueba (usuario):** en webs de adultos, si se bloquean los dominios de anuncios el vídeo no carga. El reproductor espera la respuesta del anuncio y, si falla, se queda parado. Por eso hace falta responder con un «no hay anuncio» válido y no cortar.
  - Limitaciones conocidas: la app de YouTube no acepta nuestra CA; los anuncios metidos en el propio flujo de vídeo (SSAI) no se pueden quitar así.
