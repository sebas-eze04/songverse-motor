# ============================================================
#  Release de SongVerse para Android (el motor local de descargas).
#
#  Uso:  pwsh scripts/publicar.ps1
#  Toma el APK firmado de la última compilación (./gradlew assembleRelease) y lo
#  sube al repositorio PÚBLICO sebas-eze04/songverse-motor como release
#  «android-v<versión>», con make_latest=false: el «latest» del repositorio es
#  el del motor de Windows, que su actualizador lee de ahí.
#  Al final imprime versión, enlace, tamaño y SHA-256 del APK publicado.
# ============================================================
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$OWNER = 'sebas-eze04'
$REPO  = 'songverse-motor'

$gradle = Get-Content (Join-Path $root 'app\build.gradle.kts') -Raw
if ($gradle -notmatch 'versionName = "(\d+\.\d+\.\d+)"') { throw 'No encuentro versionName en app/build.gradle.kts' }
$version = $Matches[1]
$apk = Join-Path $root 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $apk)) { throw "Falta ${apk}: compila antes (gradlew assembleRelease)." }
$hash = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLower()
$size = (Get-Item $apk).Length
Write-Host "==> SongVerse $version para Android · $size bytes · $hash" -ForegroundColor Cyan

# Token de la cuenta del proyecto (fine-grained, solo este repo): keys/github-token.txt, fuera de git.
$tokenPath = Join-Path $root '..\keys\github-token.txt'
if (-not (Test-Path $tokenPath)) { throw "Falta $tokenPath con el token de GitHub de la cuenta del proyecto." }
$token = (Get-Content $tokenPath -Raw).Trim()
$headers = @{ Authorization = "Bearer $token"; Accept = 'application/vnd.github+json' }
$api = "https://api.github.com/repos/$OWNER/$REPO"

$tag = "android-v$version"
$notes = "SongVerse ${version} para Android: el motor local de descargas (Android 8 o posterior, 64 bits).`n`n**SHA-256:** ``$hash``"
try {
    $body = @{ tag_name = $tag; name = "SongVerse $version para Android"; body = $notes; make_latest = 'false' } | ConvertTo-Json
    $release = Invoke-RestMethod -Method Post -Headers $headers -Uri "$api/releases" -Body $body -ContentType 'application/json'
} catch {
    $release = Invoke-RestMethod -Headers $headers -Uri "$api/releases/tags/$tag"
    Invoke-RestMethod -Method Patch -Headers $headers -Uri "$api/releases/$($release.id)" -Body (@{ body = $notes; make_latest = 'false' } | ConvertTo-Json) -ContentType 'application/json' | Out-Null
}

function Upload([string]$path, [string]$name, [string]$mime) {
    $existing = Invoke-RestMethod -Headers $headers -Uri "$api/releases/$($release.id)/assets"
    $existing | Where-Object { $_.name -eq $name } | ForEach-Object { Invoke-RestMethod -Method Delete -Headers $headers -Uri "$api/releases/assets/$($_.id)" | Out-Null }
    Write-Host "==> Subiendo $name…" -ForegroundColor Cyan
    Invoke-RestMethod -Method Post -Uri "https://uploads.github.com/repos/$OWNER/$REPO/releases/$($release.id)/assets?name=$name" -Headers @{ Authorization = "Bearer $token"; 'Content-Type' = $mime } -InFile $path | Out-Null
}

$sums = Join-Path $root 'app\build\outputs\apk\release\SHA256SUMS.txt'
"$hash  SongVerse-Android.apk`n" | Set-Content -Path $sums -Encoding utf8NoBOM -NoNewline
Upload $apk 'SongVerse-Android.apk' 'application/vnd.android.package-archive'
Upload $sums 'SHA256SUMS.txt' 'text/plain'

Write-Host "✔ SongVerse $version para Android publicado." -ForegroundColor Green
Write-Host "  version: $version"
Write-Host "  url:     https://github.com/$OWNER/$REPO/releases/download/$tag/SongVerse-Android.apk"
Write-Host "  size:    $size"
Write-Host "  sha256:  $hash"
