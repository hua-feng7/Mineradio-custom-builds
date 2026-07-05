param(
  [string] $ApkPath = ""
)

$ErrorActionPreference = "Stop"

$AndroidDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent $AndroidDir
if (-not $ApkPath) {
  $LatestPointer = Join-Path $AndroidDir "apk-builds\latest-apk.txt"
  if (Test-Path -LiteralPath $LatestPointer) {
    $ApkPath = (Get-Content -LiteralPath $LatestPointer -Raw -Encoding UTF8).Trim()
  }
}
if (-not $ApkPath) {
  $ArtifactDir = Join-Path $AndroidDir "apk-builds"
  if (Test-Path -LiteralPath $ArtifactDir) {
    $latest = Get-ChildItem -LiteralPath $ArtifactDir -Filter "Mineradio-android-*-debug.apk" -File |
      Sort-Object LastWriteTime -Descending |
      Select-Object -First 1
    if ($latest) { $ApkPath = $latest.FullName }
  }
}
if (-not $ApkPath) {
  $ApkPath = Join-Path $RepoRoot "Mineradio-android-debug.apk"
}
$PublicIndex = Join-Path $RepoRoot "public\index.html"
$ServerJs = Join-Path $RepoRoot "server.js"
$Manifest = Join-Path $AndroidDir "AndroidManifest.xml"
$MainActivity = Join-Path $AndroidDir "src\com\mineradio\android\MainActivity.java"
$Application = Join-Path $AndroidDir "src\com\mineradio\android\MineradioApplication.java"
$LocalHttpServer = Join-Path $AndroidDir "src\com\mineradio\android\LocalHttpServer.java"

$script:Results = New-Object System.Collections.Generic.List[object]

function Add-Check {
  param(
    [string] $Name,
    [bool] $Ok,
    [string] $Detail = ""
  )
  $script:Results.Add([pscustomobject]@{
    Name = $Name
    Ok = $Ok
    Detail = $Detail
  }) | Out-Null
}

function Require-File {
  param([string] $Path, [string] $Name)
  $exists = Test-Path -LiteralPath $Path
  Add-Check $Name $exists $Path
  return $exists
}

function Require-Text {
  param(
    [string] $Path,
    [string] $Pattern,
    [string] $Name
  )
  if (-not (Test-Path -LiteralPath $Path)) {
    Add-Check $Name $false "Missing file: $Path"
    return
  }
  $text = Get-Content -LiteralPath $Path -Raw -Encoding UTF8
  Add-Check $Name ($text -match $Pattern) $Pattern
}

function Invoke-NativeCheck {
  param(
    [string] $Name,
    [string] $FilePath,
    [string[]] $Arguments
  )
  try {
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
      $output = & $FilePath @Arguments 2>&1
      $exitCode = $LASTEXITCODE
    } finally {
      $ErrorActionPreference = $previousErrorActionPreference
    }
    $ok = $exitCode -eq 0
    $detail = (($output | Select-Object -First 6) -join " ")
    if (-not $detail) {
      $detail = $FilePath + " " + ($Arguments -join " ")
    }
    Add-Check $Name $ok ("exit=$exitCode; " + $detail)
  } catch {
    Add-Check $Name $false $_.Exception.Message
  }
}

Require-File $ApkPath "APK exists" | Out-Null
Require-File $PublicIndex "public/index.html exists" | Out-Null
Require-File $ServerJs "server.js exists" | Out-Null
Require-File $Manifest "AndroidManifest.xml exists" | Out-Null
Require-File $MainActivity "MainActivity.java exists" | Out-Null
Require-File $Application "MineradioApplication.java exists" | Out-Null
Require-File $LocalHttpServer "LocalHttpServer.java exists" | Out-Null

Require-Text $Manifest 'android:screenOrientation="landscape"' "Manifest locks MainActivity landscape"
Require-Text $Application 'registerActivityLifecycleCallbacks' "Application registers lifecycle callback"
Require-Text $Application 'SCREEN_ORIENTATION_LANDSCAPE' "Application enforces landscape globally"
Require-Text $MainActivity 'setRequestedOrientation\(ActivityInfo\.SCREEN_ORIENTATION_LANDSCAPE\)' "MainActivity requests landscape"
Require-Text $MainActivity 'setJavaScriptEnabled\(true\)' "WebView enables JavaScript"
Require-Text $MainActivity 'setMediaPlaybackRequiresUserGesture\(false\)' "WebView allows media autoplay"
Require-Text $MainActivity 'setMixedContentMode\(WebSettings\.MIXED_CONTENT_ALWAYS_ALLOW\)' "WebView allows mixed content"
Require-Text $MainActivity 'clearCache\(true\)' "WebView clears resource cache on startup"
Require-Text $MainActivity 'APP_ASSET_VERSION' "WebView loads versioned app URL"
Require-Text $MainActivity 'android-touch-adapter\.js\?v=5' "Android touch adapter is injected"
Require-Text $MainActivity 'android-media-session\.js\?v=1' "Android media session bridge is injected"
Require-Text $LocalHttpServer '"/api/qq/official/playlists"' "Android local server exposes QQ official playlists"
Require-Text $LocalHttpServer 'client_search_cp' "Android QQ search uses client_search_cp"
Require-Text $LocalHttpServer 'remoteplace=txt\.yqq\.song' "Android QQ search uses full song remoteplace"
Require-Text $LocalHttpServer 'catZhida=1' "Android QQ search requests song zhida results"
Require-Text $LocalHttpServer '"total"' "Android QQ search returns total metadata"
Require-Text $LocalHttpServer 'get_playlist_by_category' "Android QQ official playlists use category API"
Require-Text $LocalHttpServer 'QQ_DESKTOP_USER_AGENT' "Android QQ requests use desktop UA"
Require-Text $LocalHttpServer 'lyric_download\.fcg' "Android QQ third-party lyric uses QRC download API"
Require-Text $LocalHttpServer 'qrcEncrypted' "Android QQ third-party lyric returns encrypted QRC payloads"
Require-Text $LocalHttpServer '"/api/qq/playlist/create"' "Android local server exposes QQ playlist create"
Require-Text $LocalHttpServer '"/api/qq/playlist/add-song"' "Android local server exposes QQ playlist add"
Require-Text $LocalHttpServer 'fcg_add_diss\.fcg' "Android QQ playlist create uses cloud write CGI"
Require-Text $LocalHttpServer 'fcg_music_add2songdir\.fcg' "Android QQ playlist add uses cloud write CGI"
Require-Text $LocalHttpServer 'get_singer_detail_info' "Android QQ artist detail uses singer detail API"
Require-Text $LocalHttpServer '"/v1/artist/songs"' "Android NetEase artist songs use paged weapi"
Require-Text $LocalHttpServer '"more"' "Android APIs expose pagination more flags"
Require-Text $ServerJs 'QQ_QRC_DOWNLOAD_URL' "Desktop server exposes QQ third-party QRC lyric path"
Require-Text $ServerJs 'qrcEncrypted' "Desktop server returns encrypted QQ QRC payloads"
Require-Text $PublicIndex 'stageLyricGlyphAtlas' "Lyrics support shared glyph atlas renderer"
Require-Text $PublicIndex 'buildGlyphLyricMesh' "Lyrics can build glyph atlas meshes"
Require-Text $PublicIndex 'shouldUseStageGlyphLyricRenderer' "Lyrics route async mode to glyph renderer"
Require-Text $PublicIndex 'return buildGlyphLyricMesh\(text\);' "Async glyph renderer does not auto-fallback to raster line textures"
Require-Text $PublicIndex 'stageLyricGlyphAtlasShared' "Glyph atlas textures use managed shared lifetime"
Require-Text $PublicIndex 'scheduleStageLyricGlyphAtlasQueue' "Glyph atlas draws queued characters over time"
Require-Text $PublicIndex 'ensureStageLyricGlyphsDrawn' "Glyph atlas draws only required visible glyphs synchronously"
Require-Text $PublicIndex 'lyric-render-worker\.js\?v=5' "Frontend loads lyric worker v5"
Require-Text $PublicIndex 'fontSize \* 2\.70' "Frontend lyric glow uses expanded horizontal padding"
Require-Text $PublicIndex 'fontSize \* 1\.92' "Frontend lyric glow uses expanded vertical padding"
Require-Text $PublicIndex 'id="lyric-provider-seg"' "UI exposes lyric provider switch"
Require-Text $PublicIndex 'parseQrcText' "Frontend parses QQ QRC word timings"
Require-Text $PublicIndex 'MineradioQrcCodec' "Frontend loads QQ QRC codec"
Require-Text $PublicIndex 'pako_inflate\.umd\.min\.js' "Frontend loads pako inflate fallback"
Require-Text $PublicIndex 'DecompressionStream' "Frontend inflates decrypted QQ QRC payloads"
Require-Text $PublicIndex '/api/qq/playlist/add-song' "Frontend can write QQ playlist songs"
Require-Text $PublicIndex 'showLoginModal\(\{ provider: ''qq'' \}\)' "Frontend collect login opens QQ provider"
Require-Text $PublicIndex 'loadMoreArtistSongs' "Frontend artist detail can load more songs"
Require-Text $PublicIndex 'loadMoreSearchResults' "Frontend search results can load more songs"
Require-Text $PublicIndex 'data-artist-load-more' "Frontend renders artist load more button"
Require-Text $PublicIndex 'data-search-load-more' "Frontend renders search load more button"
Require-Text (Join-Path $AndroidDir "build_apk.ps1") 'apk-builds' "APK build script archives timestamped builds"
Require-Text (Join-Path $AndroidDir "build_apk.ps1") 'BUILDS\.md' "APK build script writes build log"

if (Test-Path -LiteralPath $ApkPath) {
  $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $ApkPath).Hash
  Add-Check "APK SHA256 calculated" ($hash.Length -eq 64) $hash
  Add-Check "APK size non-trivial" ((Get-Item -LiteralPath $ApkPath).Length -gt 1000000) ((Get-Item -LiteralPath $ApkPath).Length.ToString())

  Add-Type -AssemblyName System.IO.Compression
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $ApkPath).Path)
  try {
    $requiredEntries = @(
      "AndroidManifest.xml",
      "classes.dex",
      "assets/www/index.html",
      "assets/www/android-touch-adapter.js",
      "assets/www/android-media-session.js",
      "assets/www/vendor/three.r128.min.js",
      "assets/www/vendor/qrcode-browser.js",
      "assets/www/vendor/qrc-codec-browser.js",
      "assets/www/vendor/pako_inflate.umd.min.js",
      "assets/www/assets/skull-decimation-points.bin"
    )
    foreach ($entryName in $requiredEntries) {
      $entry = $zip.GetEntry($entryName)
      Add-Check "APK contains $entryName" ($null -ne $entry -and $entry.Length -gt 0) ($(if ($entry) { $entry.Length } else { "missing" }))
    }

    $indexEntry = $zip.GetEntry("assets/www/index.html")
    if ($indexEntry) {
      $reader = New-Object IO.StreamReader($indexEntry.Open())
      try {
        $apkIndex = $reader.ReadToEnd()
      } finally {
        $reader.Dispose()
      }
      Add-Check "APK index has lyric pause hold" ($apkIndex.Contains("holdingStageLyrics")) "holdingStageLyrics"
      Add-Check "APK index has priority lyric prewarm" ($apkIndex.Contains("scheduleStageLyricPrewarm(activeIdx, priorityWarm)")) "priorityWarm"
      Add-Check "APK index has paced lyric stack fill" ($apkIndex.Contains("stageLyricStackWorkBudget") -and $apkIndex.Contains("immediateRadius:stageLyricImmediateFillRadius")) "stageLyricStackWorkBudget"
      Add-Check "APK index has background lyric prewarm" ($apkIndex.Contains("primeStageLyricBackgroundRender") -and $apkIndex.Contains("stageLyricRenderPipelineLimit")) "primeStageLyricBackgroundRender"
      Add-Check "APK index loads lyric worker v5" ($apkIndex.Contains("lyric-render-worker.js?v=5")) "lyric-render-worker.js?v=5"
      Add-Check "APK index has feathered lyric glow padding" ($apkIndex.Contains("fontSize * 2.70") -and $apkIndex.Contains("fontSize * 1.92")) "expanded lyric glow padding"
      Add-Check "APK index has odd lyric row presets 1-15" ($apkIndex.Contains('id="fx-lyricstacklines"') -and $apkIndex.Contains('min="1"') -and $apkIndex.Contains('max="15"') -and $apkIndex.Contains('step="2"')) "fx-lyricstacklines"
      Add-Check "APK index has lyric provider switch" ($apkIndex.Contains('id="lyric-provider-seg"') -and $apkIndex.Contains('data-lyric-provider="third"')) "lyric-provider-seg"
      Add-Check "APK index parses QQ QRC lyrics" ($apkIndex.Contains("function parseQrcText") -and $apkIndex.Contains("qrc-word") -and $apkIndex.Contains("qrcEncrypted")) "parseQrcText"
      Add-Check "APK index loads QQ official playlists" ($apkIndex.Contains("/api/qq/official/playlists?limit=24")) "/api/qq/official/playlists"
      Add-Check "APK index writes QQ playlists" ($apkIndex.Contains("/api/qq/playlist/add-song") -and $apkIndex.Contains("showLoginModal({ provider: 'qq' })")) "/api/qq/playlist/add-song"
      Add-Check "APK index has artist load more" ($apkIndex.Contains("function loadMoreArtistSongs") -and $apkIndex.Contains("data-artist-load-more")) "loadMoreArtistSongs"
      Add-Check "APK index has search load more" ($apkIndex.Contains("function loadMoreSearchResults") -and $apkIndex.Contains("data-search-load-more")) "loadMoreSearchResults"
      Add-Check "APK index has pseudo-random shuffle queue" ($apkIndex.Contains("function arrayShuffleInPlace") -and $apkIndex.Contains("function rebuildPseudoShuffleQueueFromIndex") -and $apkIndex.Contains("shuffleSequenceStep")) "pseudo shuffle queue"
    }
  } finally {
    $zip.Dispose()
  }
}

$Sdk = $env:ANDROID_HOME
if (-not $Sdk -or -not (Test-Path -LiteralPath $Sdk)) {
  $Sdk = "D:\Android\Sdk"
}
if (Test-Path -LiteralPath $Sdk) {
  $buildTools = Get-ChildItem -Path (Join-Path $Sdk "build-tools") -Directory |
    Sort-Object { [version]$_.Name } -Descending |
    Select-Object -First 1
  $apksigner = if ($buildTools) { Join-Path $buildTools.FullName "apksigner.bat" } else { "" }
  $keytool = Get-Command keytool -ErrorAction SilentlyContinue
  if (-not $keytool) {
    $knownKeytool = "C:\Program Files\Java\jdk-26.0.1\bin\keytool.exe"
    if (Test-Path -LiteralPath $knownKeytool) {
      $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $knownKeytool)
      $env:PATH = (Join-Path $env:JAVA_HOME "bin") + ";" + $env:PATH
    }
  } else {
    $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $keytool.Source)
    $env:PATH = (Join-Path $env:JAVA_HOME "bin") + ";" + $env:PATH
  }
  if ($apksigner -and (Test-Path -LiteralPath $apksigner) -and (Test-Path -LiteralPath $ApkPath)) {
    Invoke-NativeCheck "APK signature verifies" $apksigner @("verify", "--verbose", $ApkPath)
  } else {
    Add-Check "APK signature verifies" $false "apksigner or APK missing"
  }
} else {
  Add-Check "Android SDK found" $false $Sdk
}

if (Get-Command node -ErrorAction SilentlyContinue) {
  if (Test-Path -LiteralPath $ServerJs) {
    Invoke-NativeCheck "server.js parses" "node" @("--check", $ServerJs)
  }
  if (Test-Path -LiteralPath $PublicIndex) {
    try {
      $html = Get-Content -LiteralPath $PublicIndex -Raw -Encoding UTF8
      $matches = [regex]::Matches($html, '<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)</script>', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
      $tmp = Join-Path $env:TEMP ("mineradio-inline-" + [guid]::NewGuid().ToString("N") + ".js")
      $sb = New-Object System.Text.StringBuilder
      foreach ($m in $matches) {
        [void]$sb.AppendLine($m.Groups[1].Value)
      }
      Set-Content -LiteralPath $tmp -Value $sb.ToString() -Encoding UTF8
      Invoke-NativeCheck "public inline scripts parse" "node" @("--check", $tmp)
      Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
    } catch {
      Add-Check "public inline scripts parse" $false $_.Exception.Message
    }
  }
  $touchAdapter = Join-Path $AndroidDir "assets\www\android-touch-adapter.js"
  if (Test-Path -LiteralPath $touchAdapter) {
    Invoke-NativeCheck "android touch adapter parses" "node" @("--check", $touchAdapter)
  }
} else {
  Add-Check "Node.js available" $false "node not found"
}

$script:Results | Format-Table -AutoSize
$failed = @($script:Results | Where-Object { -not $_.Ok })
if ($failed.Count) {
  Write-Error ("Android port verification failed: " + $failed.Count + " check(s)")
  exit 1
}

Write-Host "Android port verification passed: $($script:Results.Count) checks"
