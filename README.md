# Launcher B180

Pantalla de inicio estilo Android Auto para el estéreo Android (8227L, 1024×600) del Mercedes B180.

## Instalar una versión

1. En el celular, abrí **Releases** de este repo y bajá `launcher-b180.apk` de la última versión.
2. Pasalo al estéreo con un pendrive y abrilo con el gestor de archivos.
3. La primera vez, apretá el botón de inicio y elegí **Launcher B180 → Siempre**.

Cada versión nueva se instala encima de la anterior: no hace falta desinstalar.

## Uso

- **Dock:** Maps, música, teléfono, radio y ajustes. Si un botón no tiene app asignada, se abre la lista para elegirla.
  **Mantené apretado** un botón para cambiar su app.
- **Botón de la izquierda:** todas las apps.
- **Controles de música:** anterior, reproducir/pausar y siguiente (teclas multimedia estándar).

## Etapas

- [x] 1. Base: estructura, dock, reloj, lista de apps.
- [ ] 2. Música: título, artista, portada y arreglo del Play por Bluetooth.
- [ ] 3. Mapa propio con posición, velocidad e indicaciones de Google Maps.
- [ ] 4. Extras con señal: portadas y clima.

## Notas técnicas

- Sin dependencias externas; compila para Android 5+ (`targetSdk` 29).
- La firma usa la clave fija de `keystore/` para que las actualizaciones se instalen encima.
- GitHub Actions compila cada push a `main` y publica el APK en Releases.
