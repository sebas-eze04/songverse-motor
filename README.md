# SongVerse

Motor local de descargas para Windows y Android. No tiene ventana ni pantalla propia: hace la descarga **en tu propio equipo** para las apps compatibles que lo integran: canciones en MP3 (hasta 320 kbps) con su portada dentro del archivo, su letra (sincronizada también en `.lrc`, si la hay) y sus datos completos. En Windows, además, vídeos MP4 (H.264) en la calidad que se pida, con su miniatura como portada. Admite colas largas, informa del avance, la velocidad, el peso y el tiempo restante, y permite pausar, reanudar y cancelar cada descarga.

Se mantiene solo: revisa yt-dlp cada 6 horas y lo pone al día, rehace una copia que se rompió y reintenta tras un corte de red. En Windows también se actualiza a sí mismo cuando hay una versión nueva.

## Instalar

**Windows** (10 y 11, 64 bits): descarga `SongVerse-Setup.exe` de la [última versión](https://github.com/sebas-eze04/songverse-motor/releases/latest). Arranca con Windows en silencio; su menú junto al reloj abre la carpeta de música o lo cierra.

**Android** (8 o posterior, 64 bits): descarga `SongVerse-Android.apk` de la [versión para Android](https://github.com/sebas-eze04/songverse-motor/releases/tag/android-v1.2.3). Al abrirlo solo dice que está activo: trabaja para la app que lo integra, en el mismo dispositivo, y se apaga solo cuando lleva un rato sin nada que hacer. La app compatible lo despierta cuando lo necesita.

## Seguridad

- Escucha **solo en 127.0.0.1** (tu equipo), puerto 47321.
- Solo atiende a las apps compatibles de tu propio equipo: cualquier página web recibe un rechazo.
- Solo entrega los archivos que él mismo descargó.
- En Android (desde la 1.2.2), la app que lo integra le entrega una llave al despertarlo. Con ella la app firma cada petición y el motor firma cada respuesta, así que otra app del dispositivo no puede hacerse pasar por él; y cada app solo ve y maneja sus propias descargas. Desde la 1.2.3 la app puede, además, cifrar lo que pide (AES-256-GCM), y cada arranque del motor es una sesión distinta: una petición capturada no sirve después.
- Para letras y portadas solo consulta una lista cerrada de servicios.
- Cada versión va firmada. En Windows, el motor solo instala actualizaciones con la firma del proyecto, y se pone al día solo cuando no está descargando nada. En Android, las actualizaciones las instala el sistema, que exige la misma firma.

## Uso responsable

Descarga solo contenido que tengas derecho a guardar. Respeta los derechos de autor y las condiciones de cada plataforma.

## Código de la versión para Android

La versión para Android se distribuye bajo la **GNU General Public License v3** (incluye la biblioteca youtubedl-android, que lleva esa licencia). Su código fuente está en la carpeta [`android/`](android/) de este repositorio, con su licencia.

## Componentes de terceros

- **yt-dlp** ([Unlicense](https://github.com/yt-dlp/yt-dlp/blob/master/LICENSE)): https://github.com/yt-dlp/yt-dlp
- **FFmpeg** (GPL v3). En Windows, compilación de BtbN `N-125551-ga09be9b91e-20260712`; código fuente: https://git.ffmpeg.org/ffmpeg.git (commit `a09be9b91e`) y scripts de compilación: https://github.com/BtbN/FFmpeg-Builds
- **youtubedl-android** 0.18.1 (GPL v3), en la versión para Android: yt-dlp, Python, QuickJS y FFmpeg compilados para Android. https://github.com/yausername/youtubedl-android
- **Tauri** (MIT/Apache-2.0), **tiny_http**, **ureq**, **id3**, **rfd** (MIT/Apache-2.0), en la versión para Windows.

© 2026 SongVerse.
