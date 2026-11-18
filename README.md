# Stomake 2.2.1 — Story Maker & Roleplay

Stomake 2.2 is a Java 21 + JavaFX desktop app with two deliberately separate workspaces:

- **Story Maker** — projects, chapter editor, Story Bible, story characters with recommended Role/Type choices, relationship graph, timeline, statistics, version history, import/export, and the project-aware AI Writer.
- **Roleplay** — standalone AI characters, a compact profile (Biodata, Character Description, Backstory, and Role), optional creator relationship/scenario setup, character-specific chat history and memory, recommended roles with custom values, and an explicit conversion action that copies only the story-safe profile fields into an independent Story Maker character record.

Roleplay characters are stored in `ai_characters` / `ai_character_messages`. Story characters are stored in the project-scoped `story_records` table. Converting a character creates or updates the separate Story Maker record; it does not merge the two identities and does not copy creator-specific relationship, chat history, or roleplay memory.

## Window controls and workspace layout

The main application and Reader mode each use a custom title bar with **minimize**, **maximize/restore**, **fullscreen**, and **close** controls. Reader mode keeps its own controls visible in fullscreen. Press **F11** to toggle fullscreen and **Esc** to leave fullscreen.

The Story Maker navigation can be hidden from the button in the workspace header. The chapter list can be hidden independently from the **Daftar Bab** button in the editor title row. Both controls stay visible so the panels can be restored at any time. The Reader mode has its own collapsible table of contents and a theme selector (Paper, Light, Warm Light, Dark, Slate); the selected reader theme is saved separately from the app theme.

## Build the Windows app using GitHub Actions

You do not need to install Java or Maven on your own PC to build Stomake.

1. Upload the complete contents of this folder to the root of a GitHub repository. Include the hidden `.github` folder.
2. Push to the repository's default branch, or open **Actions → Build Stomake 2.2.1 Windows** and choose **Run workflow**.
3. Download the `Stomake-2.2.1-Windows-x64` artifact from the successful workflow run.
4. Extract `Stomake-2.2.1-Windows-x64.zip` and run `Stomake.exe` from inside the `Stomake` folder. Do not move the EXE out of its folder.

GitHub Actions builds on a Windows runner using JDK 21 and Maven 3.9.9. `jpackage` creates a portable app-image that includes a private Java runtime. The deliverable is a ZIP containing the portable app folder, not a one-file installer.

## Run locally from source (optional)

Requires JDK 21 and Maven 3.9+:

```powershell
mvn -B -DskipTests javafx:run
```

To create the portable Windows app locally on Windows:

```powershell
.\Build-Windows.ps1
```

## AI / Ollama

Ollama is a separate local service. Start Ollama, install a model, and select it in Stomake settings. The Roleplay model and AI Writer model are configured separately. Model files are not bundled in the Windows app artifact.

## Data and migration

The app stores its database at `%APPDATA%\Stomake\data\Stomake.db` on Windows (or `~/.stomake/data/Stomake.db` elsewhere). The 2.2.1 release keeps the existing SQLite schema and adds no destructive migration. Existing Story Maker projects, story records, Roleplay character profiles, conversations, and settings are preserved. Before replacing an existing installation or restoring a database, close Stomake and create a backup from Settings.

## Main source layout

```text
src/main/java/id/stomake/App.java
src/main/java/id/stomake/ui/MainView.java
src/main/java/id/stomake/ui/CharacterStudioPane.java
src/main/java/id/stomake/ui/WindowChrome.java
src/main/java/id/stomake/data/Database.java
src/main/java/id/stomake/data/Repository.java
src/main/java/id/stomake/ai/OllamaClient.java
src/main/java/id/stomake/export/StoryTransferService.java
src/main/resources/id/stomake/theme.css
.github/workflows/build-windows.yml
```

## Verification status

All Java sources passed a Java 21 syntax parse, and the SQLite Database/Repository classes compiled together. A full JavaFX Maven build and Windows GUI launch have not yet been run in this environment; the included Windows GitHub Actions workflow is the full-build verification step. See `BUILD-AND-VERIFICATION.md`.
