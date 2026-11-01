# Launcher B180

Pantalla de inicio estilo Android Auto para el estéreo Android (8227L, 1024×600) del Mercedes B180.

## Instalar una versión

1. En el celular, abrí **Releases** de este repo y bajá `launcher-b180.apk` de la última versión.
2. Pasalo al estéreo con un pendrive y abrilo con el gestor de archivos.
3. La primera vez, apretá el botón de inicio y elegí **Launcher B180 → Siempre**.

Cada versión nueva se instala encima de la anterior: no hace falta desinstalar.

## Uso

- **Dock:** Apps, Radio, Teléfono, Música y Maps. Si un botón no tiene app asignada, se abre la lista para elegirla.
  **Mantené apretado** un botón para cambiar su app.
- **Música:** el desplegable de la tarjeta elige la fuente (Bluetooth, YouTube Music, USB, Radio u otra app).
- **Destinos rápidos:** "Agregar", mover el mapa hasta el lugar y "Guardar acá". Mantener apretado para editar.
- **Fotomultas:** la campana sobre el mapa activa o silencia el aviso sonoro.

## Etapas

- [x] 1. Base: estructura, dock con etiquetas, reloj, lista de apps.
- [x] 2. Música: título, artista, portada, progreso, volumen y arreglo del Play por Bluetooth; fuentes Bluetooth, YouTube Music, USB y Radio.
- [x] 3. Mapa propio con posición, velocidad, velocidad máxima, indicaciones de Google Maps, destinos rápidos y fotomultas.
- [x] 4. Extras con señal: clima.
- [ ] Descargar el mapa de una zona entera para usarlo sin señal (hoy se guarda lo que ya se vio).
- [ ] Pruebas en el estéreo.

## Notas técnicas

- Única dependencia: osmdroid (mapa). Compila para Android 5+ (`targetSdk` 29).
- Mapa, velocidad máxima y fotomultas: datos de OpenStreetMap; clima: Open-Meteo.
- USB, radio y cambio de fuente: servicio `ITsCommon` de `com.ts.MainUI` (la app del estéreo).
- La firma usa la clave fija de `keystore/` para que las actualizaciones se instalen encima.
- GitHub Actions compila cada push a `main` y publica el APK en Releases.
