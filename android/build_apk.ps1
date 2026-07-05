param(
  [string] $Notes = "",
  [string] $NotesFile = ""
)

$ErrorActionPreference = "Stop"

function Invoke-Native {
  param(
    [Parameter(Mandatory = $true)]
    [string] $FilePath,
    [string[]] $Arguments
  )
  & $FilePath @Arguments
  if ($LASTEXITCODE -ne 0) {
    throw "$FilePath failed with exit code $LASTEXITCODE"
  }
}

$AndroidDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent $AndroidDir
$Sdk = $env:ANDROID_HOME
if (-not $Sdk -or -not (Test-Path $Sdk)) {
  $Sdk = "D:\Android\Sdk"
}
if (-not (Test-Path $Sdk)) {
  throw "Android SDK not found. Set ANDROID_HOME or install it at D:\Android\Sdk."
}

$BuildToolsDir = Get-ChildItem -Path (Join-Path $Sdk "build-tools") -Directory |
  Sort-Object { [version]$_.Name } -Descending |
  Select-Object -First 1
if (-not $BuildToolsDir) {
  throw "No Android build-tools found under $Sdk\build-tools."
}

$PlatformJar = Join-Path $Sdk "platforms\android-35\android.jar"
if (-not (Test-Path $PlatformJar)) {
  $PlatformJar = Get-ChildItem -Path (Join-Path $Sdk "platforms") -Recurse -Filter android.jar |
    Sort-Object FullName -Descending |
    Select-Object -First 1 -ExpandProperty FullName
}
if (-not $PlatformJar -or -not (Test-Path $PlatformJar)) {
  throw "No android.jar found under $Sdk\platforms."
}

$Aapt2 = Join-Path $BuildToolsDir.FullName "aapt2.exe"
$D8 = Join-Path $BuildToolsDir.FullName "d8.bat"
$ZipAlign = Join-Path $BuildToolsDir.FullName "zipalign.exe"
$ApkSigner = Join-Path $BuildToolsDir.FullName "apksigner.bat"
$KeyToolCommand = Get-Command keytool -ErrorAction SilentlyContinue
if ($KeyToolCommand) {
  $KeyTool = $KeyToolCommand.Source
} else {
  $KeyTool = @(
    "C:\Program Files\Java\jdk-26.0.1\bin\keytool.exe",
    "C:\Program Files (x86)\Java\jdk1.8.0_144\bin\keytool.exe"
  ) | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $KeyTool) {
  throw "keytool.exe not found. Install a JDK or add keytool to PATH."
}
$JdkHome = Split-Path -Parent (Split-Path -Parent $KeyTool)
$env:JAVA_HOME = $JdkHome
$env:PATH = (Join-Path $JdkHome "bin") + ";" + $env:PATH
$JavaC = Join-Path $JdkHome "bin\javac.exe"
if (-not (Test-Path $JavaC)) {
  $JavaC = (Get-Command javac).Source
}

$BuildDir = Join-Path $AndroidDir "build\manual"
$GenDir = Join-Path $BuildDir "gen"
$ClassesDir = Join-Path $BuildDir "classes"
$DexDir = Join-Path $BuildDir "dex"
$CompiledRes = Join-Path $BuildDir "compiled_res.zip"
$UnsignedApk = Join-Path $BuildDir "Mineradio-android-unsigned.apk"
$AlignedApk = Join-Path $BuildDir "Mineradio-android-aligned.apk"
$ArtifactDir = Join-Path $AndroidDir "apk-builds"
$BuildStamp = Get-Date -Format "yyyyMMdd-HHmmss"
$FinalApk = Join-Path $ArtifactDir ("Mineradio-android-" + $BuildStamp + "-debug.apk")
$Suffix = 1
while (Test-Path -LiteralPath $FinalApk) {
  $FinalApk = Join-Path $ArtifactDir ("Mineradio-android-" + $BuildStamp + "-" + $Suffix.ToString("00") + "-debug.apk")
  $Suffix += 1
}
$BuildLog = Join-Path $ArtifactDir "BUILDS.md"
$LatestPointer = Join-Path $ArtifactDir "latest-apk.txt"
$PublicDir = Join-Path $RepoRoot "public"
$WwwDir = Join-Path $AndroidDir "assets\www"

if (Test-Path $BuildDir) {
  Remove-Item -LiteralPath $BuildDir -Recurse -Force
}
New-Item -ItemType Directory -Force $GenDir, $ClassesDir, $DexDir | Out-Null

if (-not (Test-Path $PublicDir)) {
  throw "Web assets not found: $PublicDir"
}
New-Item -ItemType Directory -Force $ArtifactDir | Out-Null
New-Item -ItemType Directory -Force $WwwDir | Out-Null
Copy-Item -Path (Join-Path $PublicDir "*") -Destination $WwwDir -Recurse -Force
Write-Host "Synced web assets: $PublicDir -> $WwwDir"

Invoke-Native $Aapt2 @("compile", "--dir", (Join-Path $AndroidDir "res"), "-o", $CompiledRes)
Invoke-Native $Aapt2 @(
  "link",
  "-o", $UnsignedApk,
  "-I", $PlatformJar,
  "--manifest", (Join-Path $AndroidDir "AndroidManifest.xml"),
  "-R", $CompiledRes,
  "--java", $GenDir,
  "--min-sdk-version", "23",
  "--target-sdk-version", "35",
  "--version-code", "1",
  "--version-name", "1.1.1-android",
  "--auto-add-overlay"
)

$Sources = @()
$Sources += Get-ChildItem -Path (Join-Path $AndroidDir "src") -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
$Sources += Get-ChildItem -Path $GenDir -Recurse -Filter *.java | Select-Object -ExpandProperty FullName

Invoke-Native $JavaC (@(
  "-encoding", "UTF-8",
  "-source", "1.8",
  "-target", "1.8",
  "-bootclasspath", $PlatformJar,
  "-classpath", $GenDir,
  "-d", $ClassesDir
) + $Sources)

$ClassFiles = Get-ChildItem -Path $ClassesDir -Recurse -Filter *.class | Select-Object -ExpandProperty FullName
Invoke-Native $D8 (@("--lib", $PlatformJar, "--min-api", "23", "--output", $DexDir) + $ClassFiles)
if (-not (Test-Path (Join-Path $DexDir "classes.dex"))) {
  throw "d8 did not produce classes.dex."
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$Zip = [System.IO.Compression.ZipFile]::Open($UnsignedApk, [System.IO.Compression.ZipArchiveMode]::Update)
try {
  $ExistingDex = $Zip.GetEntry("classes.dex")
  if ($ExistingDex) { $ExistingDex.Delete() }
  [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
    $Zip,
    (Join-Path $DexDir "classes.dex"),
    "classes.dex",
    [System.IO.Compression.CompressionLevel]::Optimal
  ) | Out-Null

  $AssetsRoot = Join-Path $AndroidDir "assets"
  $AssetFiles = Get-ChildItem -Path $AssetsRoot -Recurse -File
  foreach ($File in $AssetFiles) {
    $Relative = $File.FullName.Substring($AssetsRoot.Length).TrimStart('\', '/')
    $EntryName = "assets/" + ($Relative -replace '\\', '/')
    $ExistingAsset = $Zip.GetEntry($EntryName)
    if ($ExistingAsset) { $ExistingAsset.Delete() }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
      $Zip,
      $File.FullName,
      $EntryName,
      [System.IO.Compression.CompressionLevel]::Optimal
    ) | Out-Null
  }
} finally {
  $Zip.Dispose()
}

$Keystore = Join-Path $AndroidDir "debug.keystore"
if (-not (Test-Path $Keystore)) {
  Invoke-Native $KeyTool @(
    "-genkeypair", "-v",
    "-keystore", $Keystore,
    "-storepass", "android",
    "-alias", "androiddebugkey",
    "-keypass", "android",
    "-keyalg", "RSA",
    "-keysize", "2048",
    "-validity", "10000",
    "-dname", "CN=Android Debug,O=Mineradio,C=CN"
  )
}

Invoke-Native $ZipAlign @("-p", "-f", "4", $UnsignedApk, $AlignedApk)
Invoke-Native $ApkSigner @(
  "sign",
  "--ks", $Keystore,
  "--ks-key-alias", "androiddebugkey",
  "--ks-pass", "pass:android",
  "--key-pass", "pass:android",
  "--v4-signing-enabled", "false",
  "--out", $FinalApk,
  $AlignedApk
)

if (Test-Path "$FinalApk.idsig") {
  Remove-Item -LiteralPath "$FinalApk.idsig" -Force
}
Invoke-Native $ApkSigner @("verify", "--verbose", $FinalApk)
$Hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $FinalApk).Hash
$Size = (Get-Item -LiteralPath $FinalApk).Length
$GitCommit = ""
try { $GitCommit = (git -C $RepoRoot rev-parse --short HEAD 2>$null) } catch { $GitCommit = "" }
$GitBranch = ""
try { $GitBranch = (git -C $RepoRoot branch --show-current 2>$null) } catch { $GitBranch = "" }
if ($NotesFile) {
  if (-not (Test-Path -LiteralPath $NotesFile)) {
    throw "Notes file not found: $NotesFile"
  }
  $Notes = (Get-Content -LiteralPath $NotesFile -Raw -Encoding UTF8).Trim()
}
if (-not $Notes) {
  $Notes = "未填写构建说明。"
}
if (-not (Test-Path -LiteralPath $BuildLog)) {
  Set-Content -LiteralPath $BuildLog -Encoding UTF8 -Value "# Mineradio Android APK 构建记录`r`n"
}
$ApkName = Split-Path -Leaf $FinalApk
Add-Content -LiteralPath $BuildLog -Encoding UTF8 -Value @(
  "",
  "## $BuildStamp",
  "",
  "- APK: ``$ApkName``",
  "- SHA256: ``$Hash``",
  "- Size: ``$Size`` bytes",
  "- Git: ``$GitBranch`` ``$GitCommit``",
  "- Notes: $Notes"
)
Set-Content -LiteralPath $LatestPointer -Encoding UTF8 -Value $FinalApk
Write-Host "APK: $FinalApk"
Write-Host "SHA256: $Hash"
Write-Host "Build log: $BuildLog"
