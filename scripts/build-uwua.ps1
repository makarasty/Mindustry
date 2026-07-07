<#
.SYNOPSIS
    Reproducible build of MindustryX server with custom "UwUA" changes.

.DESCRIPTION
    Full pipeline:
      1. (optional -Pull) fast-forward parent repo to latest origin/main
      2. reset+patch Arc submodule   (patches/arc/*)      -> group com.github.TinyLake.MindustryX
      3. reset+patch work submodule  (patches/picked/*, patches/client/*)
      4. apply custom edits to NetworkIO.java  (version tag "UwUA", description limit 300)
         -> if the upstream anchors are gone, ABORTS ("изменения невозможны")
      5. clean rebuild  :core:jar server:dist  (--rerun-tasks --no-build-cache)
      6. verify the built jar actually contains the "UwUA" change
    Submodule base commits (pins) are read dynamically from the parent repo tree,
    so the script keeps working after upstream pin bumps.

.PARAMETER Pull
    Fast-forward the parent repo to origin/main before building (get the latest version).

.PARAMETER SkipPatch
    Skip re-patching submodules. Only re-applies the NetworkIO edit and rebuilds
    the current work tree. Use for a quick rebuild without touching patch state.

.PARAMETER NoClean
    Skip 'gradlew clean'. Faster, but relies on --rerun-tasks to force recompile.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\build-uwua.ps1 -Pull
#>
[CmdletBinding()]
param(
    [switch]$Pull,
    [switch]$SkipPatch,
    [switch]$NoClean
)

# git & gradle write progress to stderr; under 'Stop' PowerShell 5.1 turns that into
# a fatal NativeCommandError even on exit 0. Use 'Continue' + explicit $LASTEXITCODE checks.
$ErrorActionPreference = 'Continue'

# ---------------------------------------------------------------- config
$Root      = Split-Path -Parent $PSScriptRoot          # repo root (scripts/..)
$Work      = Join-Path $Root 'work'
$Arc       = Join-Path $Root 'Arc'
$JavaHome  = 'D:\java\graalvm-jdk-17.0.12'             # GraalVM 17
$NetIO     = 'core/src/mindustry/net/NetworkIO.java'   # relative to $Work
$JarRel    = 'server/build/libs/server-release.jar'    # relative to $Work
$ArcGroup  = "com.github.TinyLake.MindustryX"          # expected Arc group after patch

# ---------------------------------------------------------------- helpers
function Info($m){ Write-Host "[*] $m" -ForegroundColor Cyan }
function Ok  ($m){ Write-Host "[OK] $m" -ForegroundColor Green }
function Warn($m){ Write-Host "[!] $m" -ForegroundColor Yellow }
function Die ($m){ Write-Host "[FAIL] $m" -ForegroundColor Red; exit 1 }

# read the submodule base commit recorded in the parent repo tree
function Get-Pin($path){
    $line = & git -C $Root ls-tree HEAD $path
    if(-not $line){ Die "cannot read submodule pin for '$path'" }
    return ($line -split '\s+')[2]
}

# reset a submodule to its pin, then git-am a set of patch globs in order.
# aborts cleanly on the first conflicting patch.
function Patch-Repo($repo,$pin,[string[]]$globs){
    & git -C $repo am --abort 2>$null
    Info "reset $(Split-Path $repo -Leaf) -> $($pin.Substring(0,10))"
    & git -C $repo reset --hard $pin | Out-Null
    if($LASTEXITCODE -ne 0){ Die "reset failed for $repo" }
    $env:GIT_COMMITTER_DATE = '2024-01-01 00:00:00 +0000'
    foreach($g in $globs){
        $files = Get-ChildItem -Path $g -File -ErrorAction SilentlyContinue | Sort-Object Name
        if(-not $files){ Warn "no patches match: $g"; continue }
        Info "applying $($files.Count) patches from $(Split-Path $g -Leaf)"
        & git -C $repo am --no-gpg-sign -3 @($files.FullName)
        if($LASTEXITCODE -ne 0){
            $cur = (& git -C $repo am --show-current-patch=raw 2>$null | Select-String -Pattern '^Subject:' | Select-Object -First 1)
            & git -C $repo am --abort 2>$null
            Die "patch conflict in $(Split-Path $repo -Leaf): $cur`n    -> patches do not apply to this upstream. Update patches or pin."
        }
    }
    Ok "$(Split-Path $repo -Leaf) patched -> $(& git -C $repo rev-parse --short HEAD)"
}

# idempotent literal replacement with an upstream-anchor gate (no regex — exact strings)
function Replace-Or-Gate([ref]$text,$oldLit,$newLit,$what){
    if($text.Value.Contains($newLit)){ Ok "$what already applied"; return }
    if($text.Value.Contains($oldLit)){
        $text.Value = $text.Value.Replace($oldLit,$newLit)
        Ok "$what applied"
    } else {
        Die "anchor for '$what' not found in NetworkIO.java -> upstream changed writeServerData(); наши изменения невозможны. Update scripts\build-uwua.ps1."
    }
}

# ---------------------------------------------------------------- 0. sanity
if(-not (Test-Path (Join-Path $JavaHome 'bin\java.exe'))){ Die "JAVA_HOME not found: $JavaHome (GraalVM 17). Edit `$JavaHome in this script." }
if(-not (Test-Path $Work)){ Die "work submodule missing: $Work" }
if(-not (Test-Path $Arc)) { Die "Arc submodule missing: $Arc" }

# ---------------------------------------------------------------- 1. pull
if($Pull){
    Info "pull: rebase parent repo onto origin/main"
    & git -C $Root checkout -- assets/mod.hjson 2>$null   # drop spurious CRLF-only change
    # --rebase replays local commits (e.g. tooling) on top of upstream; --autostash
    # tucks a dirty tree. Survives having local commits main is ahead by (unlike --ff-only).
    & git -C $Root pull --rebase --autostash origin main
    if($LASTEXITCODE -ne 0){
        & git -C $Root rebase --abort 2>$null
        Die "git pull --rebase failed (conflict with local commits). Resolve manually, then re-run."
    }
    Ok "parent at $(& git -C $Root rev-parse --short HEAD)"
} else {
    Warn "no -Pull: building current checkout (not fetching latest)"
}

# ---------------------------------------------------------------- 2+3. patch submodules
if(-not $SkipPatch){
    $arcPin  = Get-Pin 'Arc'
    $workPin = Get-Pin 'work'
    Patch-Repo $Arc  $arcPin  @( (Join-Path $Root 'patches\arc\*.patch') )
    Patch-Repo $Work $workPin @( (Join-Path $Root 'patches\picked\*.patch'), (Join-Path $Root 'patches\client\*.patch') )
} else {
    Warn "-SkipPatch: leaving submodule patch state as-is"
}

# verify Arc group (composite-build substitution depends on it; else jitpack 404)
$arcBuild = Get-Content -Raw (Join-Path $Arc 'build.gradle')
if($arcBuild -notmatch [regex]::Escape("group = '$ArcGroup'")){
    Die "Arc group is not '$ArcGroup' -> local Arc will not substitute, build will hit jitpack 404. Run without -SkipPatch."
}
Ok "Arc group = $ArcGroup"

# ---------------------------------------------------------------- 4. custom edits
$netPath = Join-Path $Work $NetIO
if(-not (Test-Path $netPath)){ Die "NetworkIO.java not found: $netPath" }
$src = Get-Content -Raw $netPath
Replace-Or-Gate ([ref]$src) 'writeString(buffer, "MindustryX");'      'writeString(buffer, "UwUA");'          'version tag "UwUA"'
Replace-Or-Gate ([ref]$src) 'writeString(buffer, description, 100);'  'writeString(buffer, description, 300);' 'description limit 300'
[System.IO.File]::WriteAllText($netPath, $src, (New-Object System.Text.UTF8Encoding($false)))  # UTF-8 no BOM
Ok "NetworkIO.java patched"

# ---------------------------------------------------------------- 5. build
$env:JAVA_HOME = $JavaHome
$env:PATH      = "$JavaHome\bin;$env:PATH"
Push-Location $Work
try {
    Info "gradle: stop daemons"
    & .\gradlew.bat --stop | Out-Null
    if(-not $NoClean){ Info "gradle: clean"; & .\gradlew.bat clean; if($LASTEXITCODE -ne 0){ Die "gradle clean failed" } }
    Info "gradle: :core:jar server:dist (--rerun-tasks --no-build-cache)"
    & .\gradlew.bat :core:jar server:dist --no-build-cache --rerun-tasks
    if($LASTEXITCODE -ne 0){ Die "gradle build failed" }
} finally { Pop-Location }

# ---------------------------------------------------------------- 6. verify
$jar = Join-Path $Work $JarRel
if(-not (Test-Path $jar)){ Die "jar not produced: $jar" }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
try {
    $entry = $zip.GetEntry('mindustry/net/NetworkIO.class')
    if(-not $entry){ Die "NetworkIO.class missing from jar" }
    $sr = New-Object System.IO.StreamReader($entry.Open(), [System.Text.Encoding]::GetEncoding('ISO-8859-1'))
    $bytes = $sr.ReadToEnd(); $sr.Close()
    if($bytes -notmatch 'UwUA'){ Die "jar built but NetworkIO.class does NOT contain 'UwUA' -> edit lost, rebuild" }
} finally { $zip.Dispose() }

$info = Get-Item $jar
Ok "BUILD OK"
Write-Host ""
Write-Host "  jar : $jar" -ForegroundColor White
Write-Host "  size: $([math]::Round($info.Length/1MB,2)) MB" -ForegroundColor White
Write-Host "  time: $($info.LastWriteTime)" -ForegroundColor White
Write-Host "  run : java -jar `"$jar`"" -ForegroundColor White
