package id.stomake.ui;

import id.stomake.ai.OllamaClient;
import id.stomake.data.Repository;
import id.stomake.data.Repository.CharacterProfile;
import id.stomake.data.Repository.ChatMessage;
import id.stomake.data.Repository.Project;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** C.AI-style local character workshop with persistent persona and chat history. */
public final class CharacterStudioPane extends BorderPane {
    private final Repository repo;
    private final OllamaClient ollama;
    private final Consumer<Project> openBible;
    private final Runnable openSettings;

    private final ListView<CharacterProfile> characterList = new ListView<>();
    private final ComboBox<String> modelBox = new ComboBox<>();
    private final VBox chatMessages = new VBox(12);
    private final ScrollPane chatScroll = new ScrollPane(chatMessages);
    private final TextArea messageInput = new TextArea();
    private final Label chatTitle = new Label("Pilih karakter untuk mulai chat");
    private final Label chatStatus = new Label("Chat disimpan di perangkat ini");
    private final Button sendButton = new Button("Kirim  ↑");
    private CharacterProfile selected;
    private boolean loadingProfile;
    private final PauseTransition profileSaveDelay = new PauseTransition(Duration.millis(1300));

    // Persona fields
    private final TextField name = new TextField();
    private final ComboBox<String> role = new ComboBox<>();
    private final TextArea description = area("Gambaran singkat karakter dan identitasnya...");
    private final TextArea personality = area("Contoh: pendiam, mudah cemburu, perhatian diam-diam, suka menyindir...");
    private final TextArea traits = area("Satu trait per baris. Contoh: keras kepala\nprotektif\ncerdas");
    private final TextArea motivations = area("Apa yang paling ingin dicapai karakter ini?");
    private final TextArea flaws = area("Apa kekurangan, ketakutan, atau titik lemahnya?");
    private final TextArea background = area("Masa lalu, keluarga, pengalaman penting, dan hal yang membentuknya...");
    private final TextArea appearance = area("Ciri fisik, pakaian, gestur, dan detail visual...");
    private final TextArea speakingStyle = area("Cara bicara, pilihan kata, panggilan, kebiasaan dialog...");
    private final TextArea relationship = area("Hubungan karakter dengan pengguna / tokoh utama...");
    private final TextArea scenario = area("Situasi awal, lokasi, waktu, dan kondisi adegan roleplay...");
    private final TextArea greeting = area("Pesan pembuka yang akan muncul saat chat dimulai...");
    private final TextArea extraInstructions = area("Aturan khusus atau detail lain yang harus diingat model...");
    private final TextArea secrets = area("Rahasia yang diketahui karakter, atau belum diketahui pengguna...");
    private final TextArea memoryNotes = area("Catatan kontinuitas jangka panjang: kejadian penting, janji, relasi, dan fakta yang jangan dilupakan...");
    private final Label saveStatus = new Label("Profil belum disimpan");
    private Dialog<ButtonType> profileDialog;

    public CharacterStudioPane(Repository repo, OllamaClient ollama, Consumer<Project> openBible, Runnable openSettings) {
        this.repo = repo;
        this.ollama = ollama;
        this.openBible = openBible;
        this.openSettings = openSettings;
        role.setEditable(true);
        role.setPromptText("Pilih atau tulis peran karakter...");
        role.getItems().setAll("Protagonist", "Antagonist", "Deuteragonist", "Supporting Character",
                "Mentor", "Rival", "Love Interest", "Friend", "Family", "NPC", "Original Character", "Custom");
        role.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item);
                setTooltip(empty || item == null ? null : new javafx.scene.control.Tooltip(roleHint(item)));
            }
        });
        getStyleClass().add("character-studio");
        setTop(buildHeader());
        setCenter(buildStudio());
        profileSaveDelay.setOnFinished(e -> saveProfile(false));
        attachProfileAutosave();
        reloadCharacters();
        refreshModels();
    }

    private Node buildHeader() {
        HBox header = new HBox(14);
        header.getStyleClass().add("studio-header");
        header.setAlignment(Pos.CENTER_LEFT);
        VBox copy = new VBox(3);
        Label eyebrow = new Label("ROLEPLAY STUDIO"); eyebrow.getStyleClass().add("eyebrow");
        Label title = new Label("Roleplay"); title.getStyleClass().add("section-heading");
        Label subtitle = new Label("Buat karakter AI dan bangun percakapan dengan persona yang kamu tentukan."); subtitle.getStyleClass().add("muted-label");
        copy.getChildren().addAll(eyebrow, title, subtitle);
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        Button edit = new Button("Edit Profil"); edit.getStyleClass().add("button-quiet"); edit.setOnAction(e -> openProfileEditor());
        Button toBible = new Button("Convert ke Story Maker ↗"); toBible.getStyleClass().add("button-quiet"); toBible.setOnAction(e -> importToBible());
        Button add = new Button("＋ Karakter baru"); add.getStyleClass().add("button-primary"); add.setOnAction(e -> newCharacter());
        header.getChildren().addAll(copy, spacer, edit, toBible, add);
        header.setPadding(new Insets(20, 24, 16, 24));
        return header;
    }

    private Node buildStudio() {
        VBox library = new VBox(10); library.getStyleClass().add("character-library"); library.setPrefWidth(220); library.setMinWidth(190);
        HBox libraryTitle = new HBox(8); libraryTitle.setAlignment(Pos.CENTER_LEFT);
        Label label = new Label("KARAKTERKU"); label.getStyleClass().add("nav-section-title");
        Region sp = new Region(); HBox.setHgrow(sp, Priority.ALWAYS);
        Button refresh = new Button("↻"); refresh.getStyleClass().add("small-icon-button"); refresh.setOnAction(e -> reloadCharacters());
        libraryTitle.getChildren().addAll(label, sp, refresh);
        characterList.getStyleClass().add("character-list"); VBox.setVgrow(characterList, Priority.ALWAYS);
        characterList.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(CharacterProfile item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setGraphic(null); }
                else {
                    Label initial = new Label(item.name().isBlank() ? "?" : item.name().substring(0, 1).toUpperCase()); initial.getStyleClass().add("character-avatar-small");
                    VBox details = new VBox(3, new Label(item.name()), new Label(item.role()));
                    ((Label) details.getChildren().get(0)).getStyleClass().add("character-list-name");
                    ((Label) details.getChildren().get(1)).getStyleClass().add("character-list-role");
                    HBox row = new HBox(9, initial, details); row.setAlignment(Pos.CENTER_LEFT); setGraphic(row); setText(null);
                }
            }
        });
        characterList.getSelectionModel().selectedItemProperty().addListener((obs, old, value) -> { if (value != null) selectCharacter(value); });
        Button delete = new Button("Hapus karakter"); delete.getStyleClass().add("button-danger"); delete.setMaxWidth(Double.MAX_VALUE); delete.setOnAction(e -> deleteCharacter());
        library.getChildren().addAll(libraryTitle, characterList, delete);

        VBox chat = buildChatPanel(); HBox.setHgrow(chat, Priority.ALWAYS); chat.setMinWidth(420);
        SplitPane split = new SplitPane(library, chat); split.setDividerPositions(0.25); split.getStyleClass().add("studio-split");
        BorderPane wrapper = new BorderPane(split); wrapper.setPadding(new Insets(0, 20, 20, 20));
        return wrapper;
    }

    private VBox buildProfileEditor() {
        VBox outer = new VBox(10);
        outer.getStyleClass().add("profile-editor");
        outer.setPrefWidth(430);
        outer.setMinWidth(390);
        HBox titleRow = new HBox(8);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        Label heading = new Label("Profil karakter");
        heading.getStyleClass().add("panel-heading");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button save = new Button("Simpan");
        save.getStyleClass().add("button-primary");
        save.setOnAction(e -> saveProfile(true));
        titleRow.getChildren().addAll(heading, spacer, save);

        VBox identity = new VBox(10);
        identity.setPadding(new Insets(14));
        identity.getChildren().addAll(
                field("Nama karakter", name),
                field("Role / tipe karakter", role),
                roleHelp(),
                field("Biodata", description),
                field("Deskripsi karakter", personality),
                field("Backstory karakter", background));

        VBox setup = new VBox(10);
        setup.setPadding(new Insets(14));
        Label setupHint = new Label("Bagian ini hanya mengatur hubungan karakter dengan kreator dan kondisi roleplay. Tidak ikut menjadi data karakter Story Maker saat dikonversi.");
        setupHint.getStyleClass().add("muted-label");
        setupHint.setWrapText(true);
        setup.getChildren().addAll(setupHint,
                field("Hubungan karakter dengan kamu", relationship),
                field("Skenario awal roleplay", scenario),
                field("Greeting / pesan pembuka", greeting),
                field("Instruksi tambahan (opsional)", extraInstructions),
                field("Memori & kontinuitas percakapan", memoryNotes));
        TitledPane setupPane = new TitledPane("Pengaturan Roleplay (opsional)", wrap(setup));
        setupPane.setExpanded(false);
        setupPane.setCollapsible(true);
        setupPane.getStyleClass().add("roleplay-setup-pane");

        saveStatus.getStyleClass().add("muted-label");
        HBox footer = new HBox(8, saveStatus);
        footer.setAlignment(Pos.CENTER_LEFT);
        ScrollPane scroll = wrap(new VBox(12, identity, setupPane));
        VBox.setVgrow(scroll, Priority.ALWAYS);
        outer.getChildren().addAll(titleRow, scroll, footer);
        return outer;
    }

    private Node roleHelp() {
        Label hint = new Label("Saran: Protagonist = tokoh utama · Antagonist = penentang · Supporting = pendukung · Mentor = pembimbing · Rival = saingan · NPC = karakter tambahan. Kamu juga bisa mengetik role sendiri.");
        hint.getStyleClass().add("muted-label");
        hint.setWrapText(true);
        hint.setMaxWidth(390);
        return hint;
    }

    private static String roleHint(String role) {
        return switch (role) {
            case "Protagonist" -> "Tokoh utama yang menjadi pusat cerita atau interaksi.";
            case "Antagonist" -> "Tokoh yang menjadi lawan atau sumber konflik utama.";
            case "Deuteragonist" -> "Tokoh utama kedua yang memiliki peran besar.";
            case "Supporting Character" -> "Tokoh pendukung yang membantu perkembangan cerita.";
            case "Mentor" -> "Pembimbing yang memberi arahan, ilmu, atau pengalaman.";
            case "Rival" -> "Saingan yang mendorong konflik atau perkembangan karakter.";
            case "Love Interest" -> "Tokoh yang menjadi pasangan atau potensi hubungan romantis.";
            case "Friend" -> "Teman atau sekutu dekat.";
            case "Family" -> "Anggota keluarga atau kerabat.";
            case "NPC" -> "Tokoh tambahan yang mengisi dunia atau adegan.";
            case "Original Character" -> "Karakter original buatan kreator.";
            default -> "Peran bebas yang kamu tentukan sendiri.";
        };
    }

    private VBox buildChatPanel() {
        VBox panel = new VBox(10); panel.getStyleClass().add("character-chat-panel");
        HBox top = new HBox(10); top.setAlignment(Pos.CENTER_LEFT);
        StackPane avatar = new StackPane(new Label("✧")); avatar.getStyleClass().add("character-chat-avatar");
        VBox nameBox = new VBox(3, chatTitle, chatStatus); chatTitle.getStyleClass().add("chat-character-name"); chatStatus.getStyleClass().add("muted-label");
        HBox.setHgrow(nameBox, Priority.ALWAYS);
        Button memory = new Button("Ringkas ke memori"); memory.getStyleClass().add("button-quiet"); memory.setOnAction(e -> summarizeMemory(memory));
        Button clear = new Button("Bersihkan chat"); clear.getStyleClass().add("button-quiet"); clear.setOnAction(e -> clearChat());
        top.getChildren().addAll(avatar, nameBox, memory, clear);
        HBox modelRow = new HBox(8); modelRow.setAlignment(Pos.CENTER_LEFT);
        Label modelLabel = new Label("MODEL ROLEPLAY"); modelLabel.getStyleClass().add("field-caption");
        modelBox.setEditable(true); modelBox.setPrefWidth(280); modelBox.setValue(OllamaClient.RP_UNCENSORED);
        modelBox.setOnAction(e -> { if (selected != null) saveProfile(false); });
        Region sp = new Region(); HBox.setHgrow(sp, Priority.ALWAYS);
        Button settings = new Button("⚙"); settings.getStyleClass().add("small-icon-button"); settings.setOnAction(e -> openSettings.run());
        modelRow.getChildren().addAll(modelLabel, modelBox, sp, settings);
        chatMessages.getStyleClass().add("chat-message-list");
        chatScroll.setFitToWidth(true); chatScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); chatScroll.getStyleClass().add("chat-scroll"); VBox.setVgrow(chatScroll, Priority.ALWAYS);
        Label note = new Label("AI lokal dapat sesekali melupakan detail pada chat panjang. Isi catatan memori di profil untuk menjaga kontinuitas."); note.getStyleClass().add("muted-label"); note.setWrapText(true);
        HBox composer = new HBox(9); composer.getStyleClass().add("chat-composer"); composer.setAlignment(Pos.BOTTOM_CENTER);
        messageInput.setPromptText("Ketik pesan untuk karakter... (Ctrl + Enter untuk kirim)"); messageInput.setWrapText(true); messageInput.setPrefRowCount(2); HBox.setHgrow(messageInput, Priority.ALWAYS);
        messageInput.setOnKeyPressed(e -> {
            if (e.isControlDown() && e.getCode() == javafx.scene.input.KeyCode.ENTER) { sendMessage(); e.consume(); }
        });
        sendButton.getStyleClass().add("button-primary"); sendButton.setOnAction(e -> sendMessage());
        composer.getChildren().addAll(messageInput, sendButton);
        panel.getChildren().addAll(top, modelRow, new Separator(), chatScroll, note, composer);
        return panel;
    }

    private Node field(String caption, Control control) {
        VBox box = new VBox(5);
        Label label = new Label(caption); label.getStyleClass().add("field-caption");
        if (control instanceof TextArea area) { area.setWrapText(true); area.setPrefRowCount(3); area.setMinHeight(72); }
        box.getChildren().addAll(label, control);
        return box;
    }
    private static TextArea area(String prompt) { TextArea a = new TextArea(); a.setPromptText(prompt); a.setWrapText(true); a.setPrefRowCount(3); return a; }
    private static ScrollPane wrap(Node node) { ScrollPane s = new ScrollPane(node); s.setFitToWidth(true); s.getStyleClass().add("profile-scroll"); return s; }
    private static Tab tab(String title, Node content) { Tab t = new Tab(title, content); t.setClosable(false); return t; }

    private void attachProfileAutosave() {
        List<TextInputControl> controls = List.of(name, description, personality, background,
                relationship, scenario, greeting, extraInstructions, memoryNotes);
        for (TextInputControl control : controls) {
            control.textProperty().addListener((obs, oldValue, newValue) -> {
                if (!loadingProfile) {
                    saveStatus.setText("Perubahan belum disimpan…");
                    profileSaveDelay.playFromStart();
                }
            });
        }
        role.getEditor().textProperty().addListener((obs, oldValue, newValue) -> {
            if (!loadingProfile) {
                saveStatus.setText("Perubahan belum disimpan…");
                profileSaveDelay.playFromStart();
            }
        });
        role.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (!loadingProfile && newValue != null) {
                saveStatus.setText("Perubahan belum disimpan…");
                profileSaveDelay.playFromStart();
            }
        });
        modelBox.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (!loadingProfile && selected != null && newValue != null && !newValue.isBlank()) {
                saveStatus.setText("Model berubah · menyimpan…");
                profileSaveDelay.playFromStart();
            }
        });
    }

    private void reloadCharacters() {
        try {
            List<CharacterProfile> rows = repo.characters();
            characterList.getItems().setAll(rows);
            if (!rows.isEmpty()) characterList.getSelectionModel().select(0);
            else clearProfile();
        } catch (Exception ex) { status("Gagal memuat karakter: " + ex.getMessage()); }
    }

    private void refreshModels() {
        Task<List<String>> task = new Task<>() { @Override protected List<String> call() throws Exception { return ollama.listModels(); } };
        task.setOnSucceeded(e -> {
            List<String> names = new ArrayList<>(List.of(OllamaClient.RP_UNCENSORED, OllamaClient.LIGHT_UNCENSORED, OllamaClient.RP_QUALITY, OllamaClient.SMALL_ROLEPLAY, "dolphin-phi:latest"));
            for (String installed : task.getValue()) if (!names.contains(installed)) names.add(installed);
            String preferred = modelBox.getValue(); modelBox.getItems().setAll(names);
            if (preferred != null && !preferred.isBlank()) modelBox.setValue(preferred);
            else modelBox.setValue(OllamaClient.RP_UNCENSORED);
        });
        task.setOnFailed(e -> chatStatus.setText("Ollama belum terhubung · buka pengaturan model"));
        Thread thread = new Thread(task, "Stomake-Character-Models"); thread.setDaemon(true); thread.start();
    }

    private void newCharacter() {
        saveProfile(false);
        clearProfile();
        name.setText("Karakter baru");
        role.setValue("Original Character");
        role.getEditor().setText("Original Character");
        saveProfile(false);
        openProfileEditor();
    }

    private void openProfileEditor() {
        if (selected == null) {
            status("Buat atau pilih karakter terlebih dahulu.");
            return;
        }
        // Reuse one dialog because the editor controls are persistent fields.
        // Re-parenting those controls into a new Dialog on every open is invalid in JavaFX.
        if (profileDialog == null) {
            profileDialog = new Dialog<>();
            profileDialog.setTitle("Edit Profil Karakter");
            profileDialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            profileDialog.getDialogPane().setContent(buildProfileEditor());
            profileDialog.getDialogPane().setPrefSize(470, 700);
            if (getScene() != null && getScene().getWindow() != null) {
                profileDialog.initOwner(getScene().getWindow());
            }
            profileDialog.setOnHidden(e -> saveProfile(false));
        }
        profileDialog.setTitle("Edit Profil Karakter — " + selected.name());
        if (!profileDialog.isShowing()) profileDialog.showAndWait();
    }

    private void clearProfile() {
        profileSaveDelay.stop();
        loadingProfile = true;
        selected = null;
        name.clear(); role.setValue(null); role.getEditor().clear(); description.clear(); personality.clear(); traits.clear(); motivations.clear(); flaws.clear();
        background.clear(); appearance.clear(); speakingStyle.clear(); relationship.clear(); scenario.clear(); greeting.clear(); extraInstructions.clear(); secrets.clear(); memoryNotes.clear();
        chatTitle.setText("Pilih karakter untuk mulai chat");
        chatMessages.getChildren().clear();
        saveStatus.setText("Profil baru");
        loadingProfile = false;
    }

    private void selectCharacter(CharacterProfile x) {
        profileSaveDelay.stop();
        loadingProfile = true;
        selected = x;
        name.setText(x.name()); role.setValue(x.role()); role.getEditor().setText(x.role()); description.setText(x.description()); personality.setText(x.personality()); traits.setText(x.traits());
        motivations.setText(x.motivations()); flaws.setText(x.flaws()); background.setText(x.background()); appearance.setText(x.appearance());
        speakingStyle.setText(x.speakingStyle()); relationship.setText(x.relationshipToUser()); scenario.setText(x.scenario()); greeting.setText(x.greeting());
        extraInstructions.setText(x.extraInstructions()); secrets.setText(x.secrets()); memoryNotes.setText(x.memoryNotes());
        chatTitle.setText(x.name()); chatStatus.setText(x.role() + "  ·  Chat tersimpan lokal");
        String savedModel = x.modelName().isBlank() ? safeSetting("roleplay_model", OllamaClient.RP_UNCENSORED) : x.modelName();
        if (!modelBox.getItems().contains(savedModel)) modelBox.getItems().add(savedModel);
        modelBox.setValue(savedModel);
        renderMessages(x.id());
        loadingProfile = false;
        saveStatus.setText("Tersimpan");
    }

    private void saveProfile(boolean showNotice) {
        if (name.getText().isBlank()) { if (showNotice) status("Nama karakter belum diisi."); return; }
        profileSaveDelay.stop();
        try {
            long id = selected == null ? 0 : selected.id();
            double temperature = 0.95;
            if (selected != null) temperature = selected.temperature();
            String selectedRole = role.getEditor().getText() == null ? "" : role.getEditor().getText().trim();
            if (selectedRole.isBlank() && role.getValue() != null) selectedRole = role.getValue();
            // Keep legacy columns intact for existing profiles, although the simplified 2.2 UI no longer exposes them.
            CharacterProfile value = new CharacterProfile(id, name.getText().trim(), selectedRole,
                    value(description), value(personality), selected == null ? "" : selected.traits(),
                    value(background), selected == null ? "" : selected.appearance(), selected == null ? "" : selected.speakingStyle(),
                    value(scenario), value(greeting), value(extraInstructions), value(relationship),
                    selected == null ? "" : selected.motivations(), selected == null ? "" : selected.flaws(),
                    selected == null ? "" : selected.secrets(), value(memoryNotes),
                    (modelBox.getValue() == null ? "" : modelBox.getValue()), temperature);
            long savedId = repo.saveCharacter(value);
            selected = repo.character(savedId);
            int existingIndex = -1;
            for (int i = 0; i < characterList.getItems().size(); i++) {
                if (characterList.getItems().get(i).id() == savedId) { existingIndex = i; break; }
            }
            if (existingIndex >= 0) {
                characterList.getItems().set(existingIndex, selected);
                characterList.getSelectionModel().select(existingIndex);
            } else {
                characterList.getItems().add(0, selected);
                characterList.getSelectionModel().select(0);
            }
            saveStatus.setText("Tersimpan");
            chatTitle.setText(selected.name()); chatStatus.setText(selected.role() + "  ·  Chat tersimpan lokal");
            if (showNotice) status("Profil karakter tersimpan.");
        } catch (Exception ex) { status("Gagal menyimpan profil: " + ex.getMessage()); }
    }

    /** Save the current profile synchronously before navigation or exit. */
    public void savePendingProfile() {
        profileSaveDelay.stop();
        if (selected != null || !name.getText().isBlank()) saveProfile(false);
    }

    private void deleteCharacter() {
        if (selected == null) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Hapus karakter dan seluruh riwayat chat-nya?", ButtonType.YES, ButtonType.NO);
        confirm.setHeaderText("Hapus " + selected.name() + "?");
        if (confirm.showAndWait().orElse(ButtonType.NO) == ButtonType.YES) {
            try { repo.deleteCharacter(selected.id()); reloadCharacters(); }
            catch (Exception ex) { status("Gagal menghapus karakter: " + ex.getMessage()); }
        }
    }

    private void importToBible() {
        saveProfile(false);
        if (selected == null) { status("Simpan karakter terlebih dahulu."); return; }
        try {
            List<Project> projects = repo.projects();
            if (projects.isEmpty()) { status("Buat proyek cerita terlebih dahulu."); return; }
            ChoiceDialog<Project> dialog = new ChoiceDialog<>(projects.get(0), projects);
            dialog.setTitle("Convert ke Story Maker"); dialog.setHeaderText("Salin profil karakter ke proyek cerita sebagai karakter baru yang independen."); dialog.setContentText("Pilih proyek:");
            Optional<Project> chosen = dialog.showAndWait();
            chosen.ifPresent(p -> {
                try {
                    repo.importCharacterToStory(selected.id(), p.id());
                    openBible.accept(p);
                } catch (Exception ex) { status("Gagal mengonversi karakter: " + ex.getMessage()); }
            });
        } catch (Exception ex) { status("Tidak bisa memuat daftar cerita: " + ex.getMessage()); }
    }

    private void renderMessages(long characterId) {
        chatMessages.getChildren().clear();
        try {
            List<ChatMessage> history = repo.messages(characterId, 1000);
            if (history.isEmpty()) {
                if (selected != null && !selected.greeting().isBlank()) {
                    Label intro = new Label("Pesan pembuka akan muncul saat kamu mengirim pesan pertama.");
                    intro.getStyleClass().add("chat-empty-state"); intro.setWrapText(true); chatMessages.getChildren().add(intro);
                } else {
                    Label intro = new Label("Percakapanmu dengan " + name.getText() + " akan muncul di sini.\n\nBuat karakter ini hidup lewat chat roleplay.");
                    intro.getStyleClass().add("chat-empty-state"); intro.setWrapText(true); chatMessages.getChildren().add(intro);
                }
            } else for (ChatMessage message : history) addBubble(message.role(), message.content());
            scrollChatToBottom();
        } catch (Exception ex) { status("Gagal memuat riwayat chat: " + ex.getMessage()); }
    }

    private void addBubble(String role, String content) {
        Label text = new Label(content); text.setWrapText(true); text.getStyleClass().add("chat-bubble-text");
        VBox bubble = new VBox(text); bubble.getStyleClass().add(role.equals("user") ? "chat-bubble-user" : "chat-bubble-ai");
        bubble.setMaxWidth(640);
        HBox row = new HBox(bubble); row.setAlignment(role.equals("user") ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        chatMessages.getChildren().add(row);
    }

    private void sendMessage() {
        String prompt = messageInput.getText().trim();
        if (prompt.isBlank()) return;
        saveProfile(false);
        if (selected == null) { status("Simpan karakter terlebih dahulu."); return; }
        if (modelBox.getValue() == null || modelBox.getValue().isBlank()) { status("Pilih model roleplay terlebih dahulu."); return; }
        long characterId = selected.id();
        String model = modelBox.getValue().trim();
        messageInput.clear(); messageInput.setDisable(true); sendButton.setDisable(true);
        try {
            if (repo.messages(characterId, 1).isEmpty() && selected != null && !selected.greeting().isBlank()) {
                repo.addMessage(characterId, "assistant", selected.greeting());
                chatMessages.getChildren().clear();
                addBubble("assistant", selected.greeting());
            }
            repo.addMessage(characterId, "user", prompt);
            addBubble("user", prompt);
        } catch (Exception ex) { status("Pesan tidak bisa disimpan: " + ex.getMessage()); messageInput.setDisable(false); sendButton.setDisable(false); return; }
        chatStatus.setText("Sedang mengetik...");
        Task<String> task = new Task<>() {
            @Override protected String call() throws Exception {
                CharacterProfile profile = repo.character(characterId);
                List<OllamaClient.Message> messages = new ArrayList<>();
                messages.add(new OllamaClient.Message("system", buildPersonaPrompt(profile)));
                for (ChatMessage m : repo.messages(characterId, 22)) messages.add(new OllamaClient.Message(m.role(), m.content()));
                return ollama.chat(model, messages, profile.temperature(), model.toLowerCase().contains("8b") ? 8192 : 4096, 900);
            }
        };
        task.setOnSucceeded(e -> {
            String answer = task.getValue();
            try { repo.addMessage(characterId, "assistant", answer); addBubble("assistant", answer); }
            catch (Exception ex) { status("Jawaban diterima, tetapi gagal menyimpan chat: " + ex.getMessage()); addBubble("assistant", answer); }
            messageInput.setDisable(false); sendButton.setDisable(false); messageInput.requestFocus();
            chatStatus.setText(selected != null ? selected.role() + "  ·  Online lokal" : "Chat selesai"); scrollChatToBottom();
        });
        task.setOnFailed(e -> {
            addBubble("assistant", "[Gagal menghubungi model] " + task.getException().getMessage());
            messageInput.setDisable(false); sendButton.setDisable(false); chatStatus.setText("Tidak bisa merespons · periksa Ollama dan model");
        });
        Thread thread = new Thread(task, "Stomake-Roleplay-" + characterId); thread.setDaemon(true); thread.start();
    }

    /** Create a compact continuity summary so older roleplay details can be retained beyond the recent chat window. */
    private void summarizeMemory(Button trigger) {
        if (selected == null) { status("Pilih karakter terlebih dahulu."); return; }
        saveProfile(false);
        long characterId = selected.id();
        CharacterProfile profile = selected;
        String model = profile.modelName().isBlank() ? modelBox.getValue() : profile.modelName();
        if (model == null || model.isBlank()) { status("Pilih model roleplay terlebih dahulu."); return; }
        trigger.setDisable(true);
        chatStatus.setText("Sedang merangkum kontinuitas...");
        Task<String> task = new Task<>() {
            @Override protected String call() throws Exception {
                List<ChatMessage> history = repo.messages(characterId, 50);
                if (history.isEmpty()) throw new IllegalStateException("Belum ada percakapan yang bisa diringkas.");
                List<OllamaClient.Message> messages = new ArrayList<>();
                String existing = profile.memoryNotes().isBlank() ? "Belum ada memori tersimpan." : profile.memoryNotes();
                messages.add(new OllamaClient.Message("system",
                        "Kamu adalah pencatat kontinuitas cerita. Bukan karakter roleplay dan bukan asisten percakapan. "
                        + "Buat catatan memori ringkas untuk melanjutkan roleplay di masa depan. Gabungkan memori lama dengan percakapan terbaru. "
                        + "Pertahankan fakta yang sudah terjadi, nama, hubungan, janji, konflik, lokasi, benda penting, rahasia yang terungkap, "
                        + "perubahan emosi/relasi, dan hal yang belum terselesaikan. Jangan mengarang kejadian baru. Hindari mengulang detail profil yang statis. "
                        + "Tulis dalam Bahasa Indonesia sebagai poin-poin faktual yang padat, mudah dibaca model lain, maksimal sekitar 700 kata.\n\n"
                        + "MEMORI SEBELUMNYA:\n" + existing));
                for (ChatMessage m : history) messages.add(new OllamaClient.Message(m.role(), m.content()));
                messages.add(new OllamaClient.Message("user", "Perbarui catatan memori berdasarkan percakapan di atas. Keluarkan hanya catatan memori final."));
                return ollama.chat(model, messages, 0.3, 4096, 750);
            }
        };
        task.setOnSucceeded(e -> {
            try {
                CharacterProfile latest = repo.character(characterId);
                if (latest == null) throw new IllegalStateException("Karakter sudah tidak ditemukan.");
                CharacterProfile updated = new CharacterProfile(latest.id(), latest.name(), latest.role(), latest.description(), latest.personality(),
                        latest.traits(), latest.background(), latest.appearance(), latest.speakingStyle(), latest.scenario(), latest.greeting(),
                        latest.extraInstructions(), latest.relationshipToUser(), latest.motivations(), latest.flaws(), latest.secrets(),
                        task.getValue(), latest.modelName(), latest.temperature());
                repo.saveCharacter(updated);
                if (selected != null && selected.id() == characterId) {
                    memoryNotes.setText(task.getValue());
                    selected = repo.character(characterId);
                    saveStatus.setText("Memori kontinuitas diperbarui");
                    chatStatus.setText("Memori tersimpan · " + profile.name());
                }
            } catch (Exception ex) { status("Ringkasan dibuat, tetapi gagal menyimpan memori: " + ex.getMessage()); }
            trigger.setDisable(false);
        });
        task.setOnFailed(e -> {
            status("Gagal merangkum memori: " + task.getException().getMessage());
            trigger.setDisable(false);
        });
        Thread thread = new Thread(task, "Stomake-Memory-Summary-" + characterId);
        thread.setDaemon(true);
        thread.start();
    }

    private String buildPersonaPrompt(CharacterProfile p) {
        StringBuilder b = new StringBuilder();
        b.append("Kamu sedang melakukan roleplay sebagai karakter fiksi bernama ").append(p.name()).append(".\n");
        b.append("JANGAN bertindak sebagai asisten AI. Kamu adalah karakter tersebut di dalam adegan.\n\n");
        section(b, "ROLE / TIPE KARAKTER", p.role());
        section(b, "BIODATA", p.description());
        section(b, "DESKRIPSI KARAKTER", p.personality());
        section(b, "BACKSTORY KARAKTER", p.background());
        section(b, "HUBUNGAN KARAKTER DENGAN KREATOR (KHUSUS ROLEPLAY)", p.relationshipToUser());
        section(b, "SKENARIO SAAT INI", p.scenario());
        section(b, "MEMORI DAN KONTINUITAS SEBELUMNYA", p.memoryNotes());
        section(b, "INSTRUKSI TAMBAHAN", p.extraInstructions());
        b.append("\nATURAN ROLEPLAY WAJIB:\n")
         .append("1. Perlakukan deskripsi karakter sebagai pola dasar kepribadian yang stabil. Tunjukkan sifat lewat pilihan, reaksi, gestur, dialog, dan cara berpikir; jangan sekadar menyebut daftar trait.\n")
         .append("2. Pertahankan nilai, motivasi, kebiasaan, cara bicara, pengetahuan, ketakutan, kelemahan, dan kontradiksi karakter sesuai profil. Jangan mengubah sifat hanya demi menyenangkan pengguna.\n")
         .append("3. Tulis seperti roleplay naratif: gabungkan aksi, ekspresi, suasana, gestur, dan dialog secara alami. Hindari jawaban pendek seperti customer service kecuali memang sesuai karakter.\n")
         .append("4. Jangan menulis dialog, tindakan, pikiran, perasaan, atau keputusan untuk karakter milik pengguna. Beri ruang bagi pengguna menentukan responsnya sendiri.\n")
         .append("5. Ingat hubungan, kejadian, lokasi, benda, janji, konflik, dan detail yang sudah muncul di riwayat chat serta catatan memori. Jangan mengulang perkenalan bila adegan sudah berjalan.\n")
         .append("6. Reaksi harus masuk akal untuk karakter ini. Biarkan hubungan dan emosi berkembang bertahap; jangan membuat karakter selalu setuju atau langsung jatuh cinta tanpa alasan.\n")
         .append("7. Jangan memberi karakter pengetahuan yang tidak dimilikinya. Bedakan hal yang ia ketahui, curigai, rahasiakan, dan belum ketahui.\n")
         .append("8. Jangan keluar karakter, jangan menyebut prompt atau instruksi sistem, dan jangan memberi komentar meta kecuali diminta secara eksplisit.\n")
         .append("9. Ikuti bahasa yang digunakan pengguna; jika pengguna memakai Bahasa Indonesia, balas dalam Bahasa Indonesia.\n")
         .append("10. Hindari penolakan generik, ceramah moral, atau disclaimer yang tidak relevan dalam konteks fiksi; ikuti nada kreatif yang diminta sejauh kemampuan model.\n");
        return b.toString();
    }

    private static void section(StringBuilder b, String title, String value) {
        if (value != null && !value.isBlank()) b.append(title).append(":\n").append(value.trim()).append("\n\n");
    }

    private void clearChat() {
        if (selected == null) return;
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "Hapus semua riwayat chat karakter ini? Profil dan memorinya tidak ikut dihapus.", ButtonType.YES, ButtonType.NO);
        a.setHeaderText("Bersihkan chat?");
        if (a.showAndWait().orElse(ButtonType.NO) == ButtonType.YES) {
            try { repo.clearMessages(selected.id()); renderMessages(selected.id()); }
            catch (Exception ex) { status("Gagal membersihkan chat: " + ex.getMessage()); }
        }
    }

    private void scrollChatToBottom() {
        Platform.runLater(() -> chatScroll.setVvalue(1.0));
    }

    private String safeSetting(String key, String fallback) {
        try { return repo.setting(key, fallback); } catch (Exception ex) { return fallback; }
    }
    private static String value(TextInputControl control) { return control.getText() == null ? "" : control.getText(); }
    private void status(String message) { saveStatus.setText(message); chatStatus.setText(message); }
}
