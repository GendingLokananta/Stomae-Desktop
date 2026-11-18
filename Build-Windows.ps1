$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $Root
$Version = (Get-Content 'VERSION' -Raw).Trim()
if ([string]::IsNullOrWhiteSpace($Version)) { throw 'VERSION is empty.' }

Write-Host "Building Stomake $Version for Windows x64..." -ForegroundColor Cyan

$RequiredSources = @(
    'src/main/java/id/stomake/App.java',
    'src/main/java/id/stomake/ui/WindowChrome.java',
    'src/main/java/id/stomake/data/Database.java',
    'src/main/java/id/stomake/data/Repository.java',
    'src/main/java/id/stomake/ai/OllamaClient.java',
    'src/main/java/id/stomake/ui/MainView.java',
    'src/main/java/id/stomake/ui/CharacterStudioPane.java',
    'src/main/java/id/stomake/ui/ReaderWindow.java',
    'src/main/java/id/stomake/ui/RelationshipPane.java',
    'src/main/java/id/stomake/ui/WriterChatPane.java',
    'src/main/java/id/stomake/ui/ImageCropDialog.java',
    'src/main/java/id/stomake/export/StoryTransferService.java',
    'src/main/resources/id/stomake/theme.css'
)
foreach ($Source in $RequiredSources) {
    $SourcePath = Join-Path $Root $Source
    if (-not (Test-Path $SourcePath)) {
        throw "Required source file is missing: $Source. The GitHub repository does not contain this file. Replace the repository contents with the complete 2.2.2 source and commit the Java data package."
    }
    # Catch broad .gitignore patterns such as 'data/' that silently omit
    # src/main/java/id/stomake/data/*.java from git add / GitHub uploads.
    if (Get-Command git -ErrorAction SilentlyContinue) {
        git check-ignore --no-index -q -- $Source 2>$null
        if ($LASTEXITCODE -eq 0) {
            throw "Required source file is ignored by .gitignore: $Source. Use '/data/' (root-anchored) instead of 'data/' and git add the file."
        }
    }
}
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    throw 'Maven 3.9+ is required on this build machine. GitHub Actions installs it automatically.'
}
if (-not (Get-Command jpackage -ErrorAction SilentlyContinue)) {
    throw 'JDK 21 is required because jpackage is part of the JDK. GitHub Actions installs it automatically.'
}

mvn -B -ntp -DskipTests clean package
if ($LASTEXITCODE -ne 0) { throw 'Maven package failed.' }

$JarName = "stomake-desktop-$Version.jar"
$JarPath = Join-Path $Root "target\$JarName"
if (-not (Test-Path $JarPath)) { throw "Main JAR not found: $JarPath" }

$AppInput = Join-Path $Root 'target\app-image-input'
$DistDir = Join-Path $Root 'dist'
if (Test-Path $AppInput) { Remove-Item $AppInput -Recurse -Force }
if (Test-Path $DistDir) { Remove-Item $DistDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $AppInput, $DistDir | Out-Null
Copy-Item $JarPath $AppInput -Force

mvn -B -ntp dependency:copy-dependencies "-DoutputDirectory=$AppInput" -DincludeScope=runtime
if ($LASTEXITCODE -ne 0) { throw 'Copying runtime dependencies failed.' }

# Bundle a private Java runtime and JavaFX modules into the app-image.
# Keep the complete Stomake folder together; Stomake.exe relies on files beside it.
# Put the platform-specific modular JavaFX JARs on jlink's module path.
# Keep JavaFX off the normal app class path to avoid loading duplicate JavaFX classes.
# JPackage automatically adds every JAR left in AppInput to the application class path.
$FxModules = Join-Path $Root 'target\javafx-modules'
New-Item -ItemType Directory -Force -Path $FxModules | Out-Null
$PlatformFxJars = Get-ChildItem $AppInput -Filter 'javafx-*-win.jar'
if (-not $PlatformFxJars) { throw 'Windows JavaFX module JARs were not copied by Maven.' }
$PlatformFxJars | Copy-Item -Destination $FxModules -Force
Get-ChildItem $AppInput -Filter 'javafx-*.jar' | Remove-Item -Force
foreach ($FxModule in @('base', 'graphics', 'controls', 'media', 'web')) {
    $ModuleJar = Get-ChildItem $FxModules -Filter "javafx-$FxModule-*-win.jar" | Select-Object -First 1
    if (-not $ModuleJar) { throw "Missing JavaFX module JAR: javafx-$FxModule" }
}

$Jmods = Join-Path $env:JAVA_HOME 'jmods'
if (-not (Test-Path $Jmods)) { throw "JDK module directory not found: $Jmods" }
$ModulePath = "$Jmods;$FxModules"
$Modules = 'java.sql,java.net.http,java.desktop,java.logging,java.xml,java.naming,java.management,jdk.crypto.ec,jdk.unsupported,jdk.jsobject,jdk.xml.dom,javafx.controls,javafx.web,javafx.media'
jpackage --type app-image --name Stomake --app-version $Version `
    --input $AppInput `
    --main-jar $JarName `
    --main-class 'id.stomake.App' `
    --module-path $ModulePath `
    --add-modules $Modules `
    --dest $DistDir `
    --vendor 'Stomake Studio' `
    --description 'Local writing studio and AI character roleplay'
if ($LASTEXITCODE -ne 0) { throw 'jpackage failed.' }

$Exe = Join-Path $DistDir 'Stomake\Stomake.exe'
if (-not (Test-Path $Exe)) { throw "Build completed without expected launcher: $Exe" }
@"
Stomake $Version - Windows x64

1. Extract the entire Stomake folder from the ZIP.
2. Run Stomake.exe. Do not move the EXE out of this folder.
3. Ollama is a separate local service. Install/start Ollama and pull a model before using AI features.
4. Your database is stored in %APPDATA%\Stomake\data\Stomake.db.

This is a portable app-image with its own Java runtime, not a single-file executable installer.
"@ | Set-Content -Encoding UTF8 (Join-Path $DistDir 'Stomake\README-first-run.txt')

Write-Host "Build complete: $Exe" -ForegroundColor Green
