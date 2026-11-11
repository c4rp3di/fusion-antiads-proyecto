# AdBlock Soberano+ / AntiAds · pendientes

Estado: **AntiAds** es la app de pruebas (laboratorio, diagnóstico, técnicas contra anuncios de vídeo). **AdBlock Soberano+** es la estable. Nada de lo de AntiAds se ha portado todavía al Soberano+.

## Hecho en AntiAds (candidato a portar al Soberano+ cuando se decida)
- [x] Respuestas vacías con CORS (las webs ya no reintentan en bucle).
- [x] Navegadores nunca excluidos del MitM.
- [x] IPv6 oculto con el Modo Profundo (consultas AAAA «sin datos»).
- [x] Anuncio de juguete (MP4 válido de 0,3 s) con soporte de Range, y lista «permitir».
- [x] Bloqueo de DNS cifrado (DoT/DoH) por dominio, IP y puerto.
- [x] Reconfigurar el túnel sin pararlo, y botón «Activar/Apagar profundo» en la notificación.
- [x] Botón de protección que realmente desactiva (`stop()` pedía el cierre mal).
- [x] Diagnóstico unificado: categorías, gravedad, filtros, líneas tocables con acciones, ficha por app, rastreadores, sesiones de prueba, efectividad de reglas y comprobaciones.
- [x] Caché de certificados con tope (400, LRU).
- [x] Regenerar la CA desde Ajustes, y aviso de quitarla al desinstalar.
- [x] Pruebas de `HttpCodec` (`./gradlew :vigia-mitm:testDebugUnitTest`; aún no se ejecutan en el flujo de compilación).
- [x] Editor de sitios dentro de la app (Ajustes › Laboratorio › Sitios).

- [x] Pantalla de estadísticas (Hoy / 7 / 30 días): gráfico por horas o días, desglose por capa, dominios y apps con buscador, copiar CSV. Botón en «Más bloqueados hoy».
- [x] **Apps excluidas con bloqueo básico en Modo Profundo** (Ajustes › Modo Profundo, desactivado por defecto, Android 10+): las excluidas se quedan en el túnel sin descifrar; las filtran DNS + SNI y su HTTP sin cifrar pasa por el proxy. SIN PROBAR en dispositivo.
- [x] Quick Settings Tile para activar/desactivar la protección.

## Pendiente
### Medianos
- [x] **HTTP plano (puerto 80) en modo MitM.** El TCP 80 se desvía también al proxy, que lo atiende sin TLS (bloqueo, CSS y scripts como en HTTPS). Si el tráfico no empieza como HTTP se deja pasar tal cual. Interruptor en Ajustes › Modo Profundo (por defecto activado). SIN PROBAR en dispositivo.
- [ ] **MitM solo en navegadores** (opción). Casi ninguna otra app acepta una CA de usuario y cada una pierde una conexión al primer intento.
- [ ] **Lista blanca por host con caducidad.** Hoy una app entra entera al primer fallo de handshake (los navegadores ya no).
- [ ] **App en los bloqueos DNS/SNI.** El núcleo solo la conoce si el túnel la ha resuelto; hoy queda vacía sin la captura de detalle.
- [ ] **Capturar IPv6 de verdad** en lugar de ocultarlo (ruta ::/0 y tratamiento de paquetes IPv6).
- [ ] **R8 con reglas para BouncyCastle** para reducir el APK.
- [ ] **Ejecutar las pruebas unitarias en el flujo de compilación.**

### Grande
- [ ] **Reglas cosméticas reales.** Hoy hay una lista fija de selectores. Leer las reglas cosméticas de EasyList y aplicarlas por dominio.

### A valorar
- [ ] Limpiar anuncios incrustados en el flujo de vídeo (HLS con marcas CUE-OUT/CUE-IN): ya existe, solo sirve si el sitio los marca.
- [ ] La app de YouTube no acepta la CA: solo se puede actuar sobre YouTube en el navegador.
- [ ] Aprender qué bloqueos rompen una web (recarga repetida → relajar la regla para ese sitio).

## Decisión pendiente
- [ ] Qué se porta al Soberano+ y qué se queda solo en AntiAds. Sugerencia: esperar una semana de uso estable de AntiAds y portar por bloques (primero lo de red: CORS, IPv6, juguete, DNS cifrado; luego el diagnóstico).
