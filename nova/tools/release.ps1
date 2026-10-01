<#
  Publishes a Forge Nova release that ForgeNova.exe (nova\launcher) picks up on its next start.

    powershell -ExecutionPolicy Bypass -File nova\tools\release.ps1              # publish nova\VERSION
    powershell -ExecutionPolicy Bypass -File nova\tools\release.ps1 -LocalTest   # build everything into nova\dist, publish nothing

  Steps: refuse uncommitted or unpushed work -> build (nova\tools\build.cmd, nova\launcher\build.cmd) -> zip the
  player files -> make sure the pinned Forge build is mirrored as its own "forge-<id>" release -> look up the
  current Java 21 JRE -> write nova-manifest.json -> create release v<VERSION> (marked latest) on GitHub.
  Needs the GitHub CLI, signed in once:  winget install GitHub.cli   then   gh auth login
#>
param(
    [switch]$LocalTest,   # build zips + a file:// manifest in nova\dist for testing the launcher; no git checks, no upload
    [string]$Notes = ""   # release notes (markdown); defaults to the commit subjects since the previous release
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $root

function Step($m) { Write-Host "`n== $m" -ForegroundColor Cyan }
function Die($m) { Write-Host "`n$m" -ForegroundColor Red; exit 1 }
function Sha256($f) { (Get-FileHash -Algorithm SHA256 $f).Hash.ToLowerInvariant() }
function Exec($exe) { & $exe @args; if ($LASTEXITCODE -ne 0) { Die "$exe $args failed (exit $LASTEXITCODE)" } }
# True when the GitHub release exists. (cmd does the redirect: PowerShell 5.1 turns redirected native stderr into errors.)
function ReleaseExists($t) { cmd /c "gh release view $t -R $repo >nul 2>&1"; return ($LASTEXITCODE -eq 0) }

# Writes a zip from (sourcePath, entryName) pairs without staging copies (Forge's res folder is ~300 MB).
function New-Zip($zipPath, $items) {
    if (Test-Path $zipPath) { Remove-Item $zipPath }
    $zip = [System.IO.Compression.ZipFile]::Open($zipPath, 'Create')
    try {
        foreach ($it in $items) {
            $src = (Resolve-Path $it[0]).Path
            if (Test-Path $src -PathType Container) {
                $base = $src.TrimEnd('\') + '\'
                foreach ($f in Get-ChildItem $src -Recurse -File) {
                    $entry = $it[1] + '/' + $f.FullName.Substring($base.Length).Replace('\', '/')
                    [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $f.FullName, $entry, 'Optimal')
                }
            } else {
                [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $src, $it[1], 'Optimal')
            }
        }
    } finally { $zip.Dispose() }
}

$version = (Get-Content nova\VERSION -TotalCount 1).Trim()
if ($version -notmatch '^\d+\.\d+\.\d+$') { Die "nova\VERSION must look like 1.2.3 (found '$version')" }
$tag = "v$version"
$remote = (git remote get-url origin).Trim()
if ($remote -notmatch 'github\.com[:/](.+?)(\.git)?$') { Die "origin is not a GitHub repository: $remote" }
$repo = $Matches[1]
$launcherSrc = Get-Content nova\launcher\ForgeNova.cs -Raw
if ($launcherSrc -notmatch 'LauncherVersion = (\d+);') { Die 'LauncherVersion not found in ForgeNova.cs' }
$launcherVersion = [int]$Matches[1]
if ($launcherSrc -notmatch "Repo = `"$([regex]::Escape($repo))`"") { Die "ForgeNova.cs Repo does not match origin ($repo)" }

Step "Forge Nova $version -> $repo"
if (-not $LocalTest) {
    # A window opened before installing gh still has the old PATH.
    if (-not (Get-Command gh -ErrorAction SilentlyContinue) -and (Test-Path "$env:ProgramFiles\GitHub CLI\gh.exe")) { $env:Path += ";$env:ProgramFiles\GitHub CLI" }
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { Die 'GitHub CLI not found: winget install GitHub.cli, then gh auth login' }
    Exec gh auth status
    # A release must be exactly a commit: no local edits or new files may sneak into the zips.
    $dirty = git status --porcelain
    if ($dirty) { Die "Commit (or stash) these first - releases are built from committed files only:`n$($dirty -join "`n")" }
    git fetch --quiet origin
    $head = (git rev-parse HEAD).Trim()
    $ahead = git rev-list '@{u}..HEAD'
    if ($ahead) { Die 'Push your commits first (git push) - the release tag must point at a commit GitHub has.' }
    if (ReleaseExists $tag) { Die "$tag is already released - bump nova\VERSION, commit, push, run again." }
}

Step 'Building'
# -LocalTest keeps the current nova\lib: rebuilding would package uncommitted (possibly unverified) engine patch work.
if (-not $LocalTest -or -not (Test-Path nova\lib\nova-host.jar)) { Exec cmd /c nova\tools\build.cmd }
Exec cmd /c nova\launcher\build.cmd
$dist = Join-Path $root 'nova\dist'
New-Item -ItemType Directory -Force $dist | Out-Null
Copy-Item nova\build\launcher\ForgeNova.exe $dist -Force

Step 'Packing Forge Nova'
$novaZip = Join-Path $dist "ForgeNova-$version.zip"
New-Zip $novaZip @(
    @('forge-nova.cmd', 'forge-nova.cmd'),
    @('nova\client', 'nova/client'),
    @('nova\lib', 'nova/lib'),
    @('nova\README.md', 'nova/README.md'),
    @('nova\VERSION', 'nova/VERSION'))

Step 'Forge build'
$jar = Get-ChildItem forge-gui-desktop-*-jar-with-dependencies.jar | Select-Object -First 1
if (-not $jar) { Die 'Forge desktop jar not found in the repository root.' }
$builtFor = (Get-Content nova\lib\patches-built-for.txt -TotalCount 1).Trim()
if ($builtFor -ne $jar.Name) { Die "Engine patches were built for $builtFor, not $($jar.Name)." }
$forgeVer = $jar.Name -replace '^forge-gui-desktop-(.+)-jar-with-dependencies\.jar$', '$1'
$forgeDate = (Get-Content build.txt -TotalCount 1).Substring(0, 10)
$forgeId = "$forgeVer-$forgeDate"
$forgeTag = "forge-$forgeId"
$forgeZipName = "forge-runtime-$forgeId.zip"
$forgeZip = Join-Path $dist $forgeZipName
$forgeInfo = Join-Path $dist "forge-runtime-$forgeId.json"
$published = $false
if (-not $LocalTest) {
    $published = ReleaseExists $forgeTag
    if ($published -and -not (Test-Path $forgeInfo)) { Exec gh release download $forgeTag -R $repo -p "forge-runtime-$forgeId.json" -D $dist --clobber }
}
if (-not $published -and -not (Test-Path $forgeInfo)) {
    Write-Host "Packing Forge $forgeId (jar + res, a few minutes)..."
    New-Zip $forgeZip @(
        @($jar.FullName, $jar.Name),
        @('res', 'res'),
        @('build.txt', 'build.txt'),
        @('LICENSE.txt', 'LICENSE.txt'))
    @{ id = $forgeId; sha256 = (Sha256 $forgeZip); size = (Get-Item $forgeZip).Length } | ConvertTo-Json | Set-Content $forgeInfo -Encoding ASCII
}
$fi = Get-Content $forgeInfo -Raw | ConvertFrom-Json
if (-not $LocalTest -and -not $published) {
    Write-Host "Uploading Forge $forgeId as release $forgeTag..."
    $forgeNotes = "Unmodified Forge desktop build ($($jar.Name), built $forgeDate) that Forge Nova's engine patches are compiled for. " +
        "The Forge Nova launcher downloads it automatically. Forge is GPL-3.0; source: https://github.com/Card-Forge/forge"
    Exec gh release create $forgeTag $forgeZip $forgeInfo -R $repo --target $head --title "Forge runtime $forgeId" --notes $forgeNotes --prerelease --latest=false
}

Step 'Java'
$adopt = Invoke-RestMethod 'https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jre&os=windows&vendor=eclipse'
$pkg = ($adopt | Where-Object { $_.binary.package.name -like '*.zip' } | Select-Object -First 1)
if (-not $pkg) { Die 'Adoptium returned no Windows x64 JRE 21 zip.' }
Write-Host $pkg.release_name

if ($LocalTest) {
    $base = 'file:///' + $dist.Replace('\', '/')
    $novaUrl = "$base/ForgeNova-$version.zip"; $forgeUrl = "$base/$forgeZipName"; $launcherUrl = "$base/ForgeNova.exe"
} else {
    $base = "https://github.com/$repo/releases/download"
    $novaUrl = "$base/$tag/ForgeNova-$version.zip"; $forgeUrl = "$base/$forgeTag/$forgeZipName"; $launcherUrl = "$base/$tag/ForgeNova.exe"
}
$manifest = [ordered]@{
    version  = $version
    commit   = (git rev-parse HEAD).Trim()
    launcher = [ordered]@{ version = $launcherVersion; url = $launcherUrl; sha256 = (Sha256 "$dist\ForgeNova.exe"); size = (Get-Item "$dist\ForgeNova.exe").Length }
    java     = [ordered]@{ id = $pkg.release_name; url = $pkg.binary.package.link; sha256 = $pkg.binary.package.checksum; size = $pkg.binary.package.size }
    forge    = [ordered]@{ id = $forgeId; url = $forgeUrl; sha256 = $fi.sha256; size = $fi.size }
    nova     = [ordered]@{ url = $novaUrl; sha256 = (Sha256 $novaZip); size = (Get-Item $novaZip).Length }
}
$manifestPath = Join-Path $dist 'nova-manifest.json'
$manifest | ConvertTo-Json -Depth 4 | Set-Content $manifestPath -Encoding ASCII
Get-Content $manifestPath

if ($LocalTest) {
    Step 'Local test build ready'
    Write-Host "Test the launcher against it (installs into the folder you run it from once it holds nova-install.json):"
    Write-Host "  set NOVA_MANIFEST_URL=$base/nova-manifest.json"
    exit 0
}

Step "Publishing $tag"
if (-not $Notes) {
    $prev = cmd /c "git describe --tags --abbrev=0 --match v* 2>nul"
    $range = if ($prev) { "$prev..HEAD" } else { 'HEAD' }
    $Notes = (git log $range --pretty='- %s' --no-merges -n 40) -join "`n"
    $Notes = "Players: just start Forge Nova - the launcher updates itself.`nNew here? Download **ForgeNova.exe** below and run it.`n`n$Notes"
}
Exec gh release create $tag $novaZip "$dist\ForgeNova.exe" $manifestPath -R $repo --target $head --title "Forge Nova $version" --notes $Notes --latest
Write-Host "`nReleased. Share: https://github.com/$repo/releases/latest/download/ForgeNova.exe" -ForegroundColor Green
