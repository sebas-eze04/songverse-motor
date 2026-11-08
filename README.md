# SongVerse

Motor local de descargas para Windows. Vive junto al reloj, sin ventana, y hace la descarga **en tu propio equipo** para las apps de escritorio compatibles que lo integran: canciones en MP3 (hasta 320 kbps) con su portada dentro del archivo, su letra (sincronizada también en `.lrc`, si la hay) y sus datos completos, y vídeos MP4 (H.264) en la calidad que se pida, con su miniatura como portada. Admite colas largas, informa del avance, la velocidad, el peso y el tiempo restante, y permite pausar, reanudar y cancelar cada descarga.

Se mantiene solo: revisa yt-dlp cada 6 horas y lo pone al día, rehace una copia que se rompió, reintenta tras un corte de red y se actualiza a sí mismo cuando hay una versión nueva.

## Instalar

Descarga `SongVerse-Setup.exe` de la [última versión](https://github.com/sebas-eze04/songverse-motor/releases/latest) (Windows 10 y 11, 64 bits). Arranca con Windows en silencio; su menú junto al reloj abre la carpeta de música o lo cierra.

## Seguridad

- Escucha **solo en 127.0.0.1** (tu equipo), puerto 47321.
- Solo atiende a las apps de escritorio de tu propio equipo: cualquier página web recibe un rechazo.
- Solo entrega los archivos que él mismo descargó.
- Para letras y portadas solo consulta una lista cerrada de servicios.
- Cada versión va firmada: el motor solo instala actualizaciones con la firma del proyecto, y se pone al día solo cuando no está descargando nada.

## Uso responsable

Descarga solo contenido que tengas derecho a guardar. Respeta los derechos de autor y las condiciones de cada plataforma.

## Componentes de terceros

- **yt-dlp** ([Unlicense](https://github.com/yt-dlp/yt-dlp/blob/master/LICENSE)): https://github.com/yt-dlp/yt-dlp
- **FFmpeg** (GPL v3), compilación de BtbN `N-125551-ga09be9b91e-20260712`. Código fuente: https://git.ffmpeg.org/ffmpeg.git (commit `a09be9b91e`) y scripts de compilación: https://github.com/BtbN/FFmpeg-Builds
- **Tauri** (MIT/Apache-2.0), **tiny_http**, **ureq**, **id3**, **rfd** (MIT/Apache-2.0).

© 2026 SongVerse.
