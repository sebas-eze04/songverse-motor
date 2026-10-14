# SongVerse

Motor local de la página **[SongVerse](https://sebas-eze04.github.io/songverse/)**.

La página tiene toda la interfaz (tus playlists de Spotify en CSV, una búsqueda o un enlace de YouTube); este programa hace la descarga **en tu propio equipo**: canciones en MP3 de 320 kbps con su portada, su letra sincronizada y sus datos (listas para «Importar» en [Frame](https://sebas-eze04.github.io/frame-app/)) y vídeos en la calidad que elijas.

## Instalar

Descarga `SongVerse-Setup.exe` de la [última versión](https://github.com/sebas-eze04/songverse-motor/releases/latest) (Windows 10 y 11, 64 bits). Queda junto al reloj de Windows; la página lo detecta sola.

## Seguridad

- Escucha **solo en 127.0.0.1** (tu equipo), puerto 47321.
- Solo atiende a la página oficial: cualquier otra web recibe un rechazo.
- Para letras y portadas solo consulta una lista cerrada de servicios.
- Cada versión va firmada: el motor solo instala actualizaciones con la firma del proyecto.

## Uso responsable

Descarga solo contenido que tengas derecho a guardar. Respeta los derechos de autor y las condiciones de cada plataforma.

## Componentes de terceros

- **yt-dlp** ([Unlicense](https://github.com/yt-dlp/yt-dlp/blob/master/LICENSE)): https://github.com/yt-dlp/yt-dlp
- **FFmpeg** (GPL v3), compilación de BtbN `N-125551-ga09be9b91e-20260712`. Código fuente: https://git.ffmpeg.org/ffmpeg.git (commit `a09be9b91e`) y scripts de compilación: https://github.com/BtbN/FFmpeg-Builds
- **Tauri** (MIT/Apache-2.0), **tiny_http**, **ureq**, **id3**, **rfd** (MIT/Apache-2.0).

© 2026 SongVerse.
