package id.stomake.ui;

import id.stomake.ai.OllamaClient;
import id.stomake.data.Database;
import id.stomake.data.Repository;
import id.stomake.data.Repository.Chapter;
import id.stomake.data.Repository.Project;
import id.stomake.data.Repository.StoryRecord;
import id.stomake.export.StoryTransferService;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Orientation;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import netscape.javascript.JSObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

public final class MainView extends BorderPane {
    private final Stage stage;
    private final Repository repo;
    private final Database database;
    private final OllamaClient ollama;
    private final StoryTransferService transfer;
    private final StackPane pageHost = new StackPane();
    private final StackPane modeHost = new StackPane();
    private final VBox modeRail = new VBox(10);
    private final Label pageTitle = new Label("Beranda");
    private VBox storySidebar;
    private BorderPane storyWorkspace;
    private HBox storyLayout;
    private CharacterStudioPane roleplayPane;
    private Button storyModeButton;
    private Button roleplayModeButton;
    private Button collapseModeButton;
    private Button storySidebarToggle;
    private boolean storySidebarVisible = true;
    private boolean modeRailCollapsed;
    private boolean roleplayMode;
    private String activeStoryTitle = "Beranda";
    private long activeProjectId = -1;
    private EditorView activeEditor;
    private CharacterStudioPane activeCharacterStudio;
    private String activePage = "home";
    private Button activeNav;

    public MainView(Stage stage, Repository repo, Database database, OllamaClient ollama) {
        this.stage = stage;
        this.repo = repo;
        this.database = database;
        this.ollama = ollama;
        this.transfer = new StoryTransferService(database);
        getStyleClass().add("app-root");
        storySidebar = (VBox) buildSidebar();
        storyWorkspace = (BorderPane) buildWorkspace();
        storyLayout = new HBox(0, storySidebar, storyWorkspace);
        HBox.setHgrow(storyWorkspace, Priority.ALWAYS);
        setTop(new WindowChrome(stage));
        setCenter(buildMainShell());
        try { applyTheme(repo.setting("theme", "Dark")); } catch (Exception ignored) { applyTheme("Dark"); }
        showHome();
    }

    private Node buildMainShell() {
        HBox shell = new HBox(0);
        shell.getStyleClass().add("main-shell");
        modeRail.getStyleClass().add("mode-rail");
        modeRail.setPrefWidth(150);
        modeRail.setMinWidth(150);
        modeRail.setPadding(new Insets(14, 10, 14, 10));

        collapseModeButton = new Button("‹  Sembunyikan");
        collapseModeButton.getStyleClass().add("mode-collapse-button");
        collapseModeButton.setMaxWidth(Double.MAX_VALUE);
        collapseModeButton.setOnAction(e -> toggleModeRail());

        storyModeButton = modeButton("▤", "Story Maker", this::activateStoryMode);
        roleplayModeButton = modeButton("✧", "Roleplay", this::activateRoleplayMode);

        VBox choices = new VBox(7, storyModeButton, roleplayModeButton);
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        Button settings = modeButton("⚙", "Pengaturan", this::showSettings);
        Label local = new Label("TERSIMPAN LOKAL");
        local.getStyleClass().add("mode-rail-note");
        modeRail.getChildren().addAll(collapseModeButton, choices, spacer, settings, local);
        modeHost.getStyleClass().add("mode-host");
        modeHost.getChildren().setAll(storyLayout);
        HBox.setHgrow(modeHost, Priority.ALWAYS);
        shell.getChildren().addAll(modeRail, modeHost);
        updateModeRailLabels();
        updateModeSelection(false);
        return shell;
    }

    private Button modeButton(String icon, String label, Runnable action) {
        Button button = new Button(icon + "   " + label);
        button.getStyleClass().add("mode-button");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setOnAction(e -> action.run());
        button.setUserData(label);
        return button;
    }

    private void toggleModeRail() {
        modeRailCollapsed = !modeRailCollapsed;
        modeRail.setPrefWidth(modeRailCollapsed ? 60 : 150);
        modeRail.setMinWidth(modeRailCollapsed ? 60 : 150);
        modeRail.setMaxWidth(modeRailCollapsed ? 60 : 150);
        updateModeRailLabels();
    }

    private void updateModeRailLabels() {
        if (storyModeButton != null) storyModeButton.setText(modeRailCollapsed ? "▤" : "▤   Story Maker");
        if (roleplayModeButton != null) roleplayModeButton.setText(modeRailCollapsed ? "✧" : "✧   Roleplay");
        if (collapseModeButton != null) collapseModeButton.setText(modeRailCollapsed ? "›" : "‹  Sembunyikan");
        for (Node node : modeRail.getChildren()) {
            if (node instanceof Button button && "Pengaturan".equals(button.getUserData()))
                button.setText(modeRailCollapsed ? "⚙" : "⚙   Pengaturan");
        }
    }

    private void updateModeSelection(boolean roleplay) {
        if (storyModeButton != null) {
            storyModeButton.getStyleClass().remove("mode-selected");
            roleplayModeButton.getStyleClass().remove("mode-selected");
            (roleplay ? roleplayModeButton : storyModeButton).getStyleClass().add("mode-selected");
        }
    }

    private void activateStoryMode() {
        if (roleplayMode && roleplayPane != null) roleplayPane.savePendingProfile();
        if (activeEditor != null && activePage.equals("editor")) activeEditor.saveCurrentQuietly();
        roleplayMode = false;
        modeHost.getChildren().setAll(storyLayout);
        pageTitle.setText(activeStoryTitle);
        updateModeSelection(false);
    }

    private void activateRoleplayMode() {
        if (activeEditor != null) activeEditor.saveCurrentQuietly();
        if (activeCharacterStudio != null) activeCharacterStudio.savePendingProfile();
        if (roleplayPane == null) {
            roleplayPane = new CharacterStudioPane(repo, ollama, this::openStoryBibleForProject, this::showSettings);
        }
        activeCharacterStudio = roleplayPane;
        roleplayMode = true;
        modeHost.getChildren().setAll(roleplayPane);
        pageTitle.setText("Roleplay");
        updateModeSelection(true);
    }

    private void openStoryBibleForProject(Project project) {
        if (project != null) activeProjectId = project.id();
        activateStoryMode();
        showBible();
    }

    public void toggleFullScreen() {
        stage.setFullScreen(!stage.isFullScreen());
    }

    private Node buildWorkspace() {
        BorderPane workspace = new BorderPane();
        workspace.getStyleClass().add("workspace");
        HBox header = new HBox(16);
        header.getStyleClass().add("topbar");
        header.setAlignment(Pos.CENTER_LEFT);
        storySidebarToggle = new Button("☰");
        storySidebarToggle.getStyleClass().add("sidebar-toggle-button");
        storySidebarToggle.setTooltip(new Tooltip("Sembunyikan menu Story Maker"));
        storySidebarToggle.setOnAction(e -> toggleStorySidebar());
        Label brandMark = new Label("✦");
        brandMark.getStyleClass().add("top-mark");
        VBox titleBox = new VBox(2, pageTitle, new Label("Ruang kerja kreatifmu"));
        titleBox.getStyleClass().add("top-title-box");
        ((Label) titleBox.getChildren().get(1)).getStyleClass().add("muted-label");
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        Label localBadge = new Label("●  LOCAL WORKSPACE");
        localBadge.getStyleClass().add("local-badge");
        Button homeButton = new Button("Lihat cerita");
        homeButton.getStyleClass().add("button-quiet");
        homeButton.setOnAction(e -> showHome());
        header.getChildren().addAll(storySidebarToggle, brandMark, titleBox, spacer, localBadge, homeButton);
        workspace.setTop(header);
        pageHost.getStyleClass().add("page-host");
        workspace.setCenter(pageHost);
        return workspace;
    }

    private void toggleStorySidebar() {
        storySidebarVisible = !storySidebarVisible;
        if (storySidebar != null) {
            storySidebar.setVisible(storySidebarVisible);
            storySidebar.setManaged(storySidebarVisible);
        }
        if (storySidebarToggle != null) {
            storySidebarToggle.setText(storySidebarVisible ? "☰" : "›");
            storySidebarToggle.setTooltip(new Tooltip(storySidebarVisible
                    ? "Sembunyikan menu Story Maker" : "Tampilkan menu Story Maker"));
        }
    }

    private Node buildSidebar() {
        VBox side = new VBox(10);
        side.getStyleClass().add("sidebar");
        side.setPrefWidth(216);
        side.setMinWidth(192);
        HBox logoRow = new HBox(10);
        logoRow.setAlignment(Pos.CENTER_LEFT);
        Label logo = new Label("S"); logo.getStyleClass().add("logo-mark");
        VBox logoText = new VBox(0, new Label("STOMAKE"), new Label("STUDIO 2.2"));
        logoText.getStyleClass().add("logo-copy");
        ((Label) logoText.getChildren().get(1)).getStyleClass().add("logo-subtitle");
        logoRow.getChildren().addAll(logo, logoText);
        VBox nav = new VBox(6);
        Label mainLabel = new Label("WORKSPACE"); mainLabel.getStyleClass().add("nav-section-title");
        nav.getChildren().add(mainLabel);
        addNav(nav, "⌂", "Beranda", "home", this::showHome);
        addNav(nav, "▤", "Editor cerita", "editor", this::showEditorForActive);
        addNav(nav, "◈", "Story Bible", "bible", this::showBible);
        addNav(nav, "◎", "Relasi Karakter", "relationships", this::showRelationships);
        addNav(nav, "◌", "AI Penulis", "writer", this::showAiWriter);
        Label organizeLabel = new Label("ORGANISASI"); organizeLabel.getStyleClass().add("nav-section-title");
        nav.getChildren().add(organizeLabel);
        addNav(nav, "⌁", "Timeline", "timeline", this::showTimeline);
        addNav(nav, "▥", "Statistik", "stats", this::showStats);
        Region spacer = new Region(); VBox.setVgrow(spacer, Priority.ALWAYS);
        VBox footer = new VBox(10);
        Label localText = new Label("DATA TERSIMPAN LOKAL"); localText.getStyleClass().add("local-note");
        Label version = new Label("STOMAKE 2.2.3  ·  JAVA"); version.getStyleClass().add("version-label");
        footer.getChildren().addAll(localText, version);
        side.getChildren().addAll(logoRow, new Separator(), nav, spacer, footer);
        return side;
    }

    private void addNav(VBox parent, String icon, String title, String key, Runnable action) {
        Button button = new Button(icon + "    " + title);
        button.getStyleClass().add("nav-button");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setOnAction(e -> action.run());
        button.setUserData(key);
        parent.getChildren().add(button);
    }

    private void setActiveNav(String key, String title) {
        pageTitle.setText(title);
        activeStoryTitle = title;
        activePage = key;
        if (storySidebar != null) {
            for (Node n : storySidebar.lookupAll(".nav-button")) {
                if (n instanceof Button b) {
                    boolean selected = key.equals(b.getUserData());
                    b.getStyleClass().remove("nav-selected");
                    if (selected) b.getStyleClass().add("nav-selected");
                }
            }
        }
    }

    private void showPage(Node node, String key, String title) {
        if (roleplayMode) activateStoryMode();
        if (activeEditor != null && !"editor".equals(key)) activeEditor.saveCurrentQuietly();
        if (activeCharacterStudio != null && !"characters".equals(key)) activeCharacterStudio.savePendingProfile();
        pageHost.getChildren().setAll(node);
        setActiveNav(key, title);
    }

    /** Flush pending editor/profile changes before the application exits. */
    public void saveBeforeExit() {
        if (activeEditor != null) activeEditor.saveCurrentQuietly();
        if (activeCharacterStudio != null) activeCharacterStudio.savePendingProfile();
    }

    private void showHome() {
        if (roleplayMode) activateStoryMode();
        VBox root = new VBox(22);
        root.getStyleClass().add("page-content");
        HBox hero = new HBox(20);
        hero.getStyleClass().add("hero-card");
        hero.setAlignment(Pos.CENTER_LEFT);
        VBox heroCopy = new VBox(8);
        Label eyebrow = new Label("YOUR CREATIVE SPACE"); eyebrow.getStyleClass().add("eyebrow");
        Label heading = new Label("Cerita dimulai\ndari sini."); heading.getStyleClass().add("hero-heading");
        Label subtitle = new Label("Bangun dunia, susun karakter, dan tulis cerita dengan alur kerja yang lebih rapi.");
        subtitle.getStyleClass().add("hero-subtitle"); subtitle.setWrapText(true);
        Button create = new Button("＋   Buat cerita baru"); create.getStyleClass().add("button-primary");
        create.setOnAction(e -> createProject());
        heroCopy.getChildren().addAll(eyebrow, heading, subtitle, create);
        Region visual = new Region(); visual.getStyleClass().add("hero-visual"); HBox.setHgrow(visual, Priority.ALWAYS);
        hero.getChildren().addAll(heroCopy, visual);
        HBox sectionHead = new HBox(10);
        sectionHead.setAlignment(Pos.CENTER_LEFT);
        Label section = new Label("Cerita saya"); section.getStyleClass().add("section-heading");
        Region grow = new Region(); HBox.setHgrow(grow, Priority.ALWAYS);
        Label count = new Label(); count.getStyleClass().add("muted-label");
        Button importButton = new Button("⇧ Import .stomake"); importButton.getStyleClass().add("button-quiet"); importButton.setOnAction(e -> importStomake());
        sectionHead.getChildren().addAll(section, grow, count, importButton);
        FlowPane cards = new FlowPane(16, 16); cards.setPrefWrapLength(1100);
        try {
            List<Project> projects = repo.projects();
            count.setText(projects.size() + " proyek");
            if (projects.isEmpty()) {
                VBox empty = new VBox(8, new Label("Belum ada cerita"), new Label("Buat proyek pertamamu untuk mulai menulis."));
                empty.getStyleClass().add("empty-card"); cards.getChildren().add(empty);
            }
            for (Project p : projects) cards.getChildren().add(projectCard(p));
        } catch (Exception ex) { cards.getChildren().add(errorCard("Gagal memuat cerita", ex)); }
        root.getChildren().addAll(hero, sectionHead, cards);
        ScrollPane scroll = new ScrollPane(root); scroll.setFitToWidth(true); scroll.getStyleClass().add("page-scroll");
        showPage(scroll, "home", "Beranda");
    }

    private Node projectCard(Project project) {
        VBox card = new VBox(12); card.getStyleClass().add("project-card"); card.setPrefWidth(290);
        StackPane cover = new StackPane(); cover.getStyleClass().add("project-cover");
        Label coverText = new Label("✧"); coverText.getStyleClass().add("cover-glyph"); cover.getChildren().add(coverText);
        Image coverImage = ImageCropDialog.fromBase64(project.coverImage());
        if (coverImage != null) { ImageView imageView = new ImageView(coverImage); imageView.setFitWidth(290); imageView.setFitHeight(174); imageView.setPreserveRatio(false); cover.getChildren().setAll(imageView); }
        Label title = new Label(project.title()); title.getStyleClass().add("card-title"); title.setWrapText(true);
        Label synopsis = new Label(project.synopsis().isBlank() ? "Belum ada sinopsis. Tambahkan ringkasan untuk cerita ini." : project.synopsis());
        synopsis.getStyleClass().add("muted-label"); synopsis.setWrapText(true); synopsis.setMaxHeight(42);
        HBox actions = new HBox(8);
        Button read = new Button("Baca"); read.getStyleClass().add("button-quiet"); read.setOnAction(e -> openReader(project));
        Button edit = new Button("Edit cerita"); edit.getStyleClass().add("button-primary");
        edit.setOnAction(e -> openEditor(project.id()));
        Button menu = new Button("•••"); menu.getStyleClass().add("button-quiet");
        menu.setOnAction(e -> projectMenu(project, menu));
        actions.getChildren().addAll(read, edit, menu);
        card.getChildren().addAll(cover, title, synopsis, actions);
        return card;
    }

    private void projectMenu(Project project, Node anchor) {
        ContextMenu menu = new ContextMenu();
        MenuItem rename = new MenuItem("Ganti nama");
        rename.setOnAction(e -> {
            TextInputDialog dialog = new TextInputDialog(project.title()); dialog.setTitle("Ganti nama cerita"); dialog.setHeaderText(null); dialog.setContentText("Judul cerita:");
            dialog.showAndWait().ifPresent(value -> runUi(() -> repo.renameProject(project.id(), value), this::showHome));
        });
        MenuItem cover = new MenuItem("Atur sampul & sinopsis"); cover.setOnAction(e -> editProjectCover(project));
        MenuItem read = new MenuItem("Buka mode baca"); read.setOnAction(e -> openReader(project));
        MenuItem stomake = new MenuItem("Export cerita (.stomake)"); stomake.setOnAction(e -> exportStomake(project));
        MenuItem word = new MenuItem("Export Word (.docx)"); word.setOnAction(e -> exportWord(project));
        MenuItem delete = new MenuItem("Hapus cerita");
        delete.setOnAction(e -> confirm("Hapus cerita", "Proyek dan data yang terhubung akan dihapus. Lanjutkan?", () -> runUi(() -> repo.deleteProject(project.id()), this::showHome)));
        menu.getItems().addAll(rename, cover, read, new SeparatorMenuItem(), stomake, word, new SeparatorMenuItem(), delete);
        menu.show(anchor, javafx.geometry.Side.BOTTOM, 0, 0);
    }

    private void editProjectCover(Project project) {
        Dialog<ButtonType> dialog = new Dialog<>(); dialog.setTitle("Sampul & Sinopsis"); dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        ImageView preview = new ImageView(); preview.setFitWidth(220); preview.setFitHeight(230); preview.setPreserveRatio(true);
        String[] cover = {project.coverImage()}; Image current = ImageCropDialog.fromBase64(cover[0]); if (current != null) preview.setImage(current);
        TextArea synopsis = new TextArea(project.synopsis()); synopsis.setPromptText("Tulis sinopsis cerita..."); synopsis.setWrapText(true); synopsis.setPrefRowCount(5);
        Button choose = new Button("Pilih / Crop Sampul"); choose.setOnAction(e -> { try { String data=ImageCropDialog.chooseCroppedBase64(stage,"Pilih Sampul Cerita"); if(data!=null){cover[0]=data;preview.setImage(ImageCropDialog.fromBase64(data));} } catch(Exception ex){error("Sampul",ex);} });
        Button remove = new Button("Hapus Sampul"); remove.setOnAction(e->{cover[0]="";preview.setImage(null);});
        VBox content = new VBox(10,new Label("Preview sampul"),preview,new HBox(8,choose,remove),new Label("Sinopsis"),synopsis);content.setPadding(new Insets(14));dialog.getDialogPane().setContent(content);
        dialog.showAndWait().filter(x->x==ButtonType.OK).ifPresent(x->runUi(()->repo.updateProjectCover(project.id(),cover[0],synopsis.getText()),this::showHome));
    }

    private void exportStomake(Project project) {
        FileChooser chooser=new FileChooser();chooser.setTitle("Export Cerita Stomake");chooser.setInitialFileName(safeFileName(project.title())+".stomake");chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Stomake story","*.stomake"));
        var file=chooser.showSaveDialog(stage);if(file==null)return;
        try{transfer.exportStomake(project.id(),file.toPath());info("Cerita berhasil diekspor ke .stomake.");}catch(Exception ex){error("Export Stomake",ex);}
    }
    private void exportWord(Project project) {
        FileChooser chooser=new FileChooser();chooser.setTitle("Export Word");chooser.setInitialFileName(safeFileName(project.title())+".docx");chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Dokumen Word","*.docx"));
        var file=chooser.showSaveDialog(stage);if(file==null)return;
        try{transfer.exportDocx(project.id(),file.toPath());info("Dokumen Word berhasil dibuat.");}catch(Exception ex){error("Export Word",ex);}
    }
    private static String safeFileName(String value){String s=value==null?"Cerita":value.replaceAll("[<>:\"/\\|?*]","_").trim();return s.isBlank()?"Cerita":s;}
    private void importStomake() {
        FileChooser chooser=new FileChooser();chooser.setTitle("Import Cerita Stomake");chooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Stomake / JSON","*.stomake","*.json"),new FileChooser.ExtensionFilter("Semua file","*.*"));
        var file=chooser.showOpenDialog(stage);if(file==null)return;
        try{long id=transfer.importStomake(file.toPath());activeProjectId=id;info("Cerita berhasil diimpor.");showHome();}catch(Exception ex){error("Import Stomake",ex);}
    }
    private void openReader(Project project) { try { if(activeEditor!=null)activeEditor.saveCurrentQuietly(); ReaderWindow.open(stage,project,repo); } catch(Exception ex){error("Mode Baca",ex);} }

    private void createProject() {
        TextInputDialog dialog = new TextInputDialog("Cerita Baru");
        dialog.setTitle("Proyek baru"); dialog.setHeaderText("Mulai cerita baru"); dialog.setContentText("Judul cerita:");
        dialog.showAndWait().ifPresent(title -> {
            if (title.isBlank()) return;
            runUi(() -> activeProjectId = repo.createProject(title), () -> openEditor(activeProjectId));
        });
    }

    private void showEditorForActive() {
        try {
            if (activeProjectId <= 0) {
                List<Project> projects = repo.projects();
                if (projects.isEmpty()) { createProject(); return; }
                activeProjectId = projects.get(0).id();
            }
            openEditor(activeProjectId);
        } catch (Exception ex) { error("Editor", ex); }
    }

    private void openEditor(long projectId) {
        if (activeEditor != null) activeEditor.saveCurrentQuietly();
        activeProjectId = projectId;
        try {
            Project project = repo.projects().stream().filter(p -> p.id() == projectId).findFirst().orElse(null);
            if (project == null) { showHome(); return; }
            activeEditor = new EditorView(project);
            showPage(activeEditor, "editor", "Editor cerita");
        } catch (Exception ex) { error("Editor", ex); }
    }

    private final class EditorView extends BorderPane {
        private final Project project;
        private final ListView<Chapter> chapterList = new ListView<>();
        private VBox chapterRail;
        private Button chapterToggle;
        private boolean chapterRailVisible = true;
        private final TextField titleField = new TextField();
        private final WebView webView = new WebView();
        private final WebEngine engine = webView.getEngine();
        private final Label status = new Label("Tersimpan");
        private final PauseTransition saveDelay = new PauseTransition(Duration.millis(1100));
        private ToggleButton boldButton;
        private ToggleButton italicButton;
        private ToggleButton underlineButton;
        private ToggleButton strikeButton;
        private ComboBox<String> fontPicker;
        private Spinner<Integer> sizePicker;
        private ComboBox<String> headingPicker;
        private ComboBox<String> alignmentPicker;
        private boolean updatingControls;
        private Chapter current;
        private boolean loading;

        EditorView(Project project) throws Exception {
            this.project = project;
            getStyleClass().add("editor-layout");
            chapterRail = (VBox) buildChapterRail();
            setLeft(chapterRail);
            setCenter(buildWritingArea());
            saveDelay.setOnFinished(e -> saveCurrentQuietly());
            refreshChapters();
        }

        private void toggleChapterRail() {
            chapterRailVisible = !chapterRailVisible;
            if (chapterRail != null) {
                chapterRail.setVisible(chapterRailVisible);
                chapterRail.setManaged(chapterRailVisible);
            }
            if (chapterToggle != null) {
                chapterToggle.setText(chapterRailVisible ? "☰  Daftar Bab" : "›  Tampilkan Bab");
                chapterToggle.setTooltip(new Tooltip(chapterRailVisible
                        ? "Sembunyikan daftar bab untuk memperlebar editor"
                        : "Tampilkan daftar bab"));
            }
        }

        private Node buildChapterRail() {
            VBox rail = new VBox(12); rail.getStyleClass().add("chapter-rail"); rail.setPrefWidth(230);
            rail.setMinWidth(190);
            HBox head = new HBox(8); head.setAlignment(Pos.CENTER_LEFT);
            Label label = new Label("DAFTAR BAB"); label.getStyleClass().add("nav-section-title");
            Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
            Button add = new Button("＋"); add.getStyleClass().add("small-icon-button");
            add.setOnAction(e -> addChapter()); head.getChildren().addAll(label, spacer, add);
            chapterList.getStyleClass().add("chapter-list"); VBox.setVgrow(chapterList, Priority.ALWAYS);
            chapterList.setCellFactory(v -> new ListCell<>() {
                @Override protected void updateItem(Chapter item, boolean empty) {
                    super.updateItem(item, empty); setText(empty || item == null ? null : item.title());
                    if (!empty && !getStyleClass().contains("chapter-cell")) getStyleClass().add("chapter-cell");
                }
            });
            chapterList.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
                if (selected != null && (current == null || selected.id() != current.id())) {
                    saveCurrentQuietly(); loadChapter(selected);
                }
            });
            Button delete = new Button("Hapus bab terpilih"); delete.getStyleClass().add("button-quiet");
            delete.setMaxWidth(Double.MAX_VALUE); delete.setOnAction(e -> deleteChapter());
            rail.getChildren().addAll(head, chapterList, delete);
            return rail;
        }

        private Node buildWritingArea() {
            VBox area = new VBox(10); area.getStyleClass().add("writing-area");
            HBox titleRow = new HBox(12); titleRow.setAlignment(Pos.CENTER_LEFT);
            VBox titleBox = new VBox(4, new Label("JUDUL BAB"), titleField);
            ((Label) titleBox.getChildren().get(0)).getStyleClass().add("field-caption");
            titleField.getStyleClass().add("chapter-title-input"); HBox.setHgrow(titleBox, Priority.ALWAYS);
            chapterToggle = new Button("☰  Daftar Bab");
            chapterToggle.getStyleClass().add("chapter-toggle-button");
            chapterToggle.setTooltip(new Tooltip("Sembunyikan atau tampilkan daftar bab"));
            chapterToggle.setOnAction(e -> toggleChapterRail());
            Button save = new Button("Simpan"); save.getStyleClass().add("button-primary"); save.setOnAction(e -> saveCurrent());
            titleRow.getChildren().addAll(chapterToggle, titleBox, save);

            // The toolbar wraps into multiple rows on narrow editor widths instead of clipping controls.
            FlowPane formatTools = new FlowPane(7, 7);
            formatTools.getStyleClass().add("editor-toolbar");
            formatTools.setAlignment(Pos.CENTER_LEFT);
            formatTools.setRowValignment(VPos.CENTER);
            formatTools.setPrefWrapLength(1080);
            formatTools.setMaxWidth(Double.MAX_VALUE);

            boldButton = formatToggle("B", "Tebal", "bold"); boldButton.setStyle("-fx-font-weight:bold;");
            italicButton = formatToggle("I", "Miring", "italic"); italicButton.setStyle("-fx-font-style:italic;");
            underlineButton = formatToggle("U", "Garis bawah", "underline");
            strikeButton = formatToggle("S̶", "Coret", "strikeThrough");

            fontPicker = new ComboBox<>();
            fontPicker.getItems().addAll("Garamond", "Times New Roman", "Courier New", "Arial", "Georgia");
            fontPicker.setValue("Garamond"); fontPicker.setPrefWidth(150); fontPicker.setMinWidth(135);
            fontPicker.setTooltip(new Tooltip("Jenis huruf"));
            preserveSelectionOnPress(fontPicker);
            fontPicker.setOnAction(e -> { if (!updatingControls && fontPicker.getValue() != null) setFont(fontPicker.getValue()); });

            sizePicker = new Spinner<>(6, 200, 12);
            sizePicker.setEditable(true); sizePicker.setPrefWidth(82); sizePicker.setMinWidth(78);
            sizePicker.getStyleClass().add("font-size-control");
            sizePicker.setTooltip(new Tooltip("Ukuran font (pt). Indikator ini tetap berukuran tetap."));
            preserveSelectionOnPress(sizePicker);
            preserveSelectionOnPress(sizePicker.getEditor());
            sizePicker.valueProperty().addListener((obs, oldValue, newValue) -> {
                if (!updatingControls && newValue != null) setFontSize(newValue);
            });
            sizePicker.getEditor().setOnAction(e -> commitFontSize());
            sizePicker.getEditor().focusedProperty().addListener((obs, wasFocused, focused) -> {
                if (!focused) commitFontSize();
            });

            headingPicker = new ComboBox<>();
            headingPicker.getItems().addAll("Paragraf", "Heading 1", "Heading 2", "Heading 3");
            headingPicker.setValue("Paragraf"); headingPicker.setPrefWidth(132); headingPicker.setMinWidth(120);
            headingPicker.setTooltip(new Tooltip("Gaya paragraf"));
            preserveSelectionOnPress(headingPicker);
            headingPicker.setOnAction(e -> {
                if (!updatingControls && headingPicker.getValue() != null) {
                    command("formatBlock", switch (headingPicker.getValue()) {
                        case "Heading 1" -> "h1"; case "Heading 2" -> "h2"; case "Heading 3" -> "h3"; default -> "p";
                    });
                }
            });

            alignmentPicker = new ComboBox<>();
            alignmentPicker.getItems().addAll("Kiri", "Tengah", "Kanan");
            alignmentPicker.setValue("Kiri"); alignmentPicker.setPrefWidth(115); alignmentPicker.setMinWidth(105);
            alignmentPicker.setTooltip(new Tooltip("Perataan paragraf"));
            preserveSelectionOnPress(alignmentPicker);
            alignmentPicker.setOnAction(e -> {
                if (!updatingControls && alignmentPicker.getValue() != null) {
                    command(switch (alignmentPicker.getValue()) {
                        case "Tengah" -> "justifyCenter"; case "Kanan" -> "justifyRight"; default -> "justifyLeft";
                    });
                }
            });

            formatTools.getChildren().addAll(boldButton, italicButton, underlineButton, strikeButton,
                    separatorNode(), fontPicker, sizePicker, headingPicker, alignmentPicker);

            FlowPane actionTools = new FlowPane(7, 7);
            actionTools.getStyleClass().add("editor-action-toolbar");
            actionTools.setAlignment(Pos.CENTER_LEFT);
            actionTools.setRowValignment(VPos.CENTER);
            actionTools.setPrefWrapLength(1080);
            actionTools.setMaxWidth(Double.MAX_VALUE);
            Button list = tool("•  List", () -> command("insertUnorderedList"));
            Button insertImage = new Button("＋  Gambar"); insertImage.getStyleClass().add("button-quiet"); preserveSelectionOnPress(insertImage); insertImage.setOnAction(e -> insertChapterImage());
            Button ai = new Button("✧  AI Penulis"); ai.getStyleClass().add("button-quiet"); ai.setOnAction(e -> showAiWriter());
            MenuButton more = new MenuButton("Lainnya ▾"); more.getStyleClass().add("button-quiet");
            MenuItem reader = new MenuItem("Mode baca"); reader.setOnAction(e -> openReader(project));
            MenuItem history = new MenuItem("Riwayat versi bab"); history.setOnAction(e -> showVersionHistory(project.id()));
            MenuItem resizeImage = new MenuItem("Atur lebar gambar terpilih"); resizeImage.setOnAction(e -> resizeSelectedImage());
            MenuItem removeImage = new MenuItem("Hapus gambar terpilih"); removeImage.setOnAction(e -> removeSelectedImage());
            MenuItem exportStory = new MenuItem("Export cerita .stomake"); exportStory.setOnAction(e -> exportStomake(project));
            MenuItem exportDoc = new MenuItem("Export Word .docx"); exportDoc.setOnAction(e -> exportWord(project));
            more.getItems().addAll(reader, history, new SeparatorMenuItem(), resizeImage, removeImage,
                    new SeparatorMenuItem(), exportStory, exportDoc);
            actionTools.getChildren().addAll(list, insertImage, ai, more);

            webView.getStyleClass().add("writing-webview");
            VBox.setVgrow(webView, Priority.ALWAYS);
            engine.getLoadWorker().stateProperty().addListener((obs, old, state) -> {
                if (state == javafx.concurrent.Worker.State.SUCCEEDED) {
                    loading = true;
                    try {
                        JSObject window = (JSObject) engine.executeScript("window");
                        window.setMember("stomakeBridge", new EditorBridge(
                                () -> Platform.runLater(() -> { if (!loading) saveDelay.playFromStart(); }),
                                format -> Platform.runLater(() -> syncFormattingControls(format))));
                        engine.executeScript(editorBootstrapScript());
                        applyEditorTheme(currentTheme());
                        updateFormattingFromSelection();
                    } catch (Exception ignored) { }
                    loading = false;
                }
            });
            HBox footer = new HBox(8, status); footer.setAlignment(Pos.CENTER_RIGHT); status.getStyleClass().add("muted-label");
            area.getChildren().addAll(titleRow, formatTools, actionTools, webView, footer);
            return area;
        }

        private Node separatorNode() {
            Separator separator = new Separator();
            separator.setOrientation(Orientation.VERTICAL);
            separator.setPrefHeight(28);
            return separator;
        }

        private void preserveSelectionOnPress(Node node) {
            node.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> rememberEditorSelection());
        }

        private void rememberEditorSelection() {
            try { engine.executeScript("window.stomakeRememberSelection && window.stomakeRememberSelection()"); }
            catch (Exception ignored) { }
        }

        private ToggleButton formatToggle(String text, String tooltip, String command) {
            ToggleButton button = new ToggleButton(text);
            button.getStyleClass().add("format-toggle");
            button.setTooltip(new Tooltip(tooltip));
            preserveSelectionOnPress(button);
            button.setOnAction(e -> {
                if (!updatingControls) command(command);
            });
            return button;
        }

        private Button tool(String text, Runnable action) {
            Button button = new Button(text);
            button.getStyleClass().add("format-button");
            preserveSelectionOnPress(button);
            button.setOnAction(e -> action.run());
            return button;
        }

        private void command(String name) { command(name, null); }
        private void command(String name, String value) {
            String script = "window.stomakeCommand(" + jsQuote(name) + "," + (value == null ? "null" : jsQuote(value)) + ")";
            Object changed = engine.executeScript(script);
            webView.requestFocus();
            if (Boolean.TRUE.equals(changed)) saveDelay.playFromStart();
            updateFormattingFromSelection();
        }

        private void setFont(String family) {
            Object changed = engine.executeScript("window.stomakeSetFont(" + jsQuote(family) + ")");
            webView.requestFocus();
            if (Boolean.TRUE.equals(changed)) saveDelay.playFromStart();
            updateFormattingFromSelection();
        }

        private void setFontSize(int pt) {
            int clamped = Math.max(6, Math.min(200, pt));
            Object changed = engine.executeScript("window.stomakeSetFontSize(" + clamped + ")");
            webView.requestFocus();
            if (Boolean.TRUE.equals(changed)) saveDelay.playFromStart();
            updateFormattingFromSelection();
        }

        private void commitFontSize() {
            if (updatingControls || sizePicker == null) return;
            try {
                int value = Integer.parseInt(sizePicker.getEditor().getText().trim());
                int clamped = Math.max(6, Math.min(200, value));
                int previous = sizePicker.getValue();
                updatingControls = true;
                sizePicker.getValueFactory().setValue(clamped);
                sizePicker.getEditor().setText(Integer.toString(clamped));
                updatingControls = false;
                if (clamped != previous) setFontSize(clamped);
            } catch (NumberFormatException ignored) {
                updatingControls = true;
                sizePicker.getEditor().setText(Integer.toString(sizePicker.getValue()));
                updatingControls = false;
            }
        }

        private void syncFormattingControls(EditorFormatState format) {
            if (format == null || sizePicker == null) return;
            updatingControls = true;
            try {
                int pt = Math.max(6, Math.min(200, format.fontSize()));
                // Do not overwrite a size value while the user is actively typing in the spinner editor.
                if (!sizePicker.getEditor().isFocused()) {
                    sizePicker.getValueFactory().setValue(pt);
                    sizePicker.getEditor().setText(Integer.toString(pt));
                }
                if (fontPicker.getItems().contains(format.fontFamily())) fontPicker.setValue(format.fontFamily());
                boldButton.setSelected(format.bold());
                italicButton.setSelected(format.italic());
                underlineButton.setSelected(format.underline());
                strikeButton.setSelected(format.strike());
                headingPicker.setValue(switch (format.blockTag().toLowerCase()) {
                    case "h1" -> "Heading 1"; case "h2" -> "Heading 2"; case "h3" -> "Heading 3"; default -> "Paragraf";
                });
                alignmentPicker.setValue(switch (format.alignment().toLowerCase()) {
                    case "center" -> "Tengah"; case "right", "end" -> "Kanan"; default -> "Kiri";
                });
            } finally { updatingControls = false; }
        }

        private void updateFormattingFromSelection() {
            try { engine.executeScript("window.stomakeUpdateFormat && window.stomakeUpdateFormat()"); }
            catch (Exception ignored) { }
        }

        private String currentTheme() {
            try { return repo.setting("theme", "Dark"); }
            catch (Exception ignored) { return "Dark"; }
        }

        private String editorThemeCss() {
            return editorThemeCss(currentTheme());
        }

        private String editorThemeCss(String theme) {
            return switch (theme == null ? "Dark" : theme) {
                case "Light" -> "html,body{background:#f6f7fb;color:#26283a;} body{border-color:#e0e3ec;} p,h1,h2,h3,li{color:#26283a;}";
                case "Warm Light" -> "html,body{background:#f4eee4;color:#342d26;} body{border-color:#ded3c3;} p,h1,h2,h3,li{color:#342d26;}";
                case "Slate" -> "html,body{background:#26313d;color:#e7edf2;} body{border-color:#3b4856;} p,h1,h2,h3,li{color:#e7edf2;}";
                default -> "html,body{background:#181a24;color:#e9e7ef;} body{border-color:#303444;} p,h1,h2,h3,li{color:#e9e7ef;}";
            };
        }

        private void applyEditorTheme(String theme) {
            try {
                String css = editorThemeCss(theme).replace("\\", "\\\\").replace("'", "\\'");
                engine.executeScript("(function(){var s=document.getElementById('stomake-editor-theme');if(!s){s=document.createElement('style');s.id='stomake-editor-theme';document.head.appendChild(s);}s.textContent='" + css + "';})()");
            } catch (Exception ignored) { }
        }

        private String editorBootstrapScript() {
            return """
                    (function () {
                      const editable = document.body;
                      const ZWSP = String.fromCharCode(8203);
                      window.__stomakeLastRange = null;
                      window.__stomakeCaretMarker = null;
                      window.__stomakePendingInline = null;

                      // Earlier releases could leave <font size="7"> behind when the conversion failed.
                      // WebKit renders that legacy value as 36 pt; normalize it to the editor default.
                      editable.querySelectorAll('font[size="7"]').forEach(font => {
                        const span = document.createElement('span');
                        span.style.fontSize = '12pt';
                        while (font.firstChild) span.appendChild(font.firstChild);
                        font.parentNode.replaceChild(span, font);
                      });

                      function selection() { return window.getSelection(); }
                      function remember(force) {
                        try {
                          const sel = selection();
                          if (!sel || !sel.rangeCount) return;
                          const range = sel.getRangeAt(0);
                          if (!editable.contains(range.commonAncestorContainer)) return;
                          if (!force && !document.hasFocus()) return;
                          window.__stomakeLastRange = range.cloneRange();
                        } catch (e) { }
                      }
                      function restore() {
                        try {
                          const sel = selection();
                          const saved = window.__stomakeLastRange;
                          if (!sel || !saved || !editable.contains(saved.commonAncestorContainer)) return false;
                          sel.removeAllRanges();
                          sel.addRange(saved);
                          return true;
                        } catch (e) { return false; }
                      }
                      function activeElement() {
                        const sel = selection();
                        const node = sel && sel.anchorNode;
                        let element = node ? (node.nodeType === 1 ? node : node.parentElement) : editable;
                        if (!element || !editable.contains(element)) element = editable;
                        return element;
                      }
                      function activeBlock(element) {
                        let block = element;
                        while (block && block !== editable && !/^(P|DIV|H1|H2|H3|LI|BLOCKQUOTE|PRE)$/.test(block.tagName)) {
                          block = block.parentElement;
                        }
                        return block || editable;
                      }
                      function caretMarker(sel) {
                        if (!sel || !sel.anchorNode) return null;
                        let element = sel.anchorNode.nodeType === 1 ? sel.anchorNode : sel.anchorNode.parentElement;
                        return element && element.closest ? element.closest('span[data-stomake-caret="1"]') : null;
                      }
                      function ensureCaretMarker() {
                        const sel = selection();
                        if (!sel || !sel.rangeCount || !sel.isCollapsed) return null;
                        let marker = caretMarker(sel);
                        if (marker) {
                          window.__stomakeCaretMarker = marker;
                          return marker;
                        }
                        const range = sel.getRangeAt(0);
                        marker = document.createElement('span');
                        marker.setAttribute('data-stomake-caret', '1');
                        const text = document.createTextNode(ZWSP);
                        marker.appendChild(text);
                        range.insertNode(marker);
                        const next = document.createRange();
                        next.setStart(text, 1);
                        next.collapse(true);
                        sel.removeAllRanges();
                        sel.addRange(next);
                        window.__stomakeCaretMarker = marker;
                        window.__stomakeLastRange = next.cloneRange();
                        return marker;
                      }
                      function demoteOldCaretMarker() {
                        const sel = selection();
                        const marker = window.__stomakeCaretMarker;
                        if (!marker || (sel && sel.anchorNode && marker.contains(sel.anchorNode))) return;
                        marker.removeAttribute('data-stomake-caret');
                        const walker = document.createTreeWalker(marker, NodeFilter.SHOW_TEXT);
                        const texts = [];
                        while (walker.nextNode()) texts.push(walker.currentNode);
                        texts.forEach(node => { node.nodeValue = node.nodeValue.split(ZWSP).join(''); });
                        if (!marker.textContent && !marker.children.length) marker.remove();
                        window.__stomakeCaretMarker = null;
                      }
                      function cleanCurrentSentinel() {
                        const sel = selection();
                        const marker = caretMarker(sel);
                        if (!marker) return;
                        const walker = document.createTreeWalker(marker, NodeFilter.SHOW_TEXT);
                        const texts = [];
                        while (walker.nextNode()) texts.push(walker.currentNode);
                        texts.forEach(node => {
                          const index = node.nodeValue.indexOf(ZWSP);
                          if (index < 0) return;
                          let newOffset = null;
                          if (sel && sel.anchorNode === node) {
                            const before = node.nodeValue.slice(0, sel.anchorOffset).split(ZWSP).length - 1;
                            newOffset = Math.max(0, sel.anchorOffset - before);
                          }
                          node.nodeValue = node.nodeValue.split(ZWSP).join('');
                          if (newOffset !== null) {
                            const range = document.createRange();
                            range.setStart(node, Math.min(newOffset, node.nodeValue.length));
                            range.collapse(true);
                            sel.removeAllRanges();
                            sel.addRange(range);
                          }
                        });
                      }
                      function stateFromCommand(command, fallback) {
                        try {
                          const value = document.queryCommandState(command);
                          if (typeof value === 'boolean') return value;
                        } catch (e) { }
                        return fallback;
                      }
                      function inlineState(command, fallback) {
                        const sel = selection();
                        const pending = window.__stomakePendingInline;
                        if (sel && sel.isCollapsed && pending && pending.command === command &&
                            pending.node === sel.anchorNode && pending.offset === sel.anchorOffset) {
                          return pending.value;
                        }
                        return stateFromCommand(command, fallback);
                      }
                      function applyRangeStyle(property, value) {
                        const sel = selection();
                        if (!sel || !sel.rangeCount) return false;
                        if (sel.isCollapsed) {
                          const marker = ensureCaretMarker();
                          if (!marker) return false;
                          marker.style[property] = value;
                          remember(true);
                          updateFormat();
                          return false;
                        }
                        const range = sel.getRangeAt(0).cloneRange();
                        const walker = document.createTreeWalker(editable, NodeFilter.SHOW_TEXT);
                        const nodes = [];
                        while (walker.nextNode()) {
                          const node = walker.currentNode;
                          if (!node.nodeValue || !node.nodeValue.length) continue;
                          try { if (range.intersectsNode(node)) nodes.push(node); } catch (e) { }
                        }
                        const wrapped = [];
                        for (let i = nodes.length - 1; i >= 0; i--) {
                          const node = nodes[i];
                          let start = node === range.startContainer ? range.startOffset : 0;
                          let end = node === range.endContainer ? range.endOffset : node.nodeValue.length;
                          start = Math.max(0, Math.min(start, node.nodeValue.length));
                          end = Math.max(start, Math.min(end, node.nodeValue.length));
                          if (end <= start) continue;
                          let selectedNode = node;
                          if (start > 0) selectedNode = node.splitText(start);
                          if (end - start < selectedNode.nodeValue.length) selectedNode.splitText(end - start);
                          const span = document.createElement('span');
                          span.style[property] = value;
                          selectedNode.parentNode.insertBefore(span, selectedNode);
                          span.appendChild(selectedNode);
                          wrapped.push(span);
                        }
                        if (!wrapped.length) return false;
                        wrapped.reverse();
                        const next = document.createRange();
                        next.setStart(wrapped[0], 0);
                        next.setEndAfter(wrapped[wrapped.length - 1]);
                        sel.removeAllRanges();
                        sel.addRange(next);
                        window.__stomakeLastRange = next.cloneRange();
                        updateFormat();
                        return true;
                      }
                      function updateFormat() {
                        try {
                          const sel = selection();
                          const element = activeElement();
                          const block = activeBlock(element);
                          const style = getComputedStyle(element);
                          const blockStyle = getComputedStyle(block);
                          const px = parseFloat(style.fontSize) || 16;
                          const pt = Math.max(6, Math.min(200, Math.round(px * 0.75)));
                          const family = (style.fontFamily || 'Garamond').split(',')[0].replace(/["']/g, '').trim();
                          const marker = caretMarker(sel);
                          const weight = parseInt(style.fontWeight, 10) || 400;
                          const decoration = (style.textDecorationLine || style.textDecoration || '').toLowerCase();
                          const boldFallback = weight >= 600 || !!element.closest('b,strong');
                          const italicFallback = style.fontStyle === 'italic' || !!element.closest('i,em');
                          const underlineFallback = decoration.indexOf('underline') >= 0 || !!element.closest('u,ins');
                          const strikeFallback = decoration.indexOf('line-through') >= 0 || !!element.closest('s,strike,del');
                          const bold = inlineState('bold', boldFallback);
                          const italic = inlineState('italic', italicFallback);
                          const underline = inlineState('underline', underlineFallback);
                          const strike = inlineState('strikeThrough', strikeFallback);
                          const blockTag = (block.tagName || 'P').toLowerCase();
                          window.stomakeBridge.selectionChanged(String(pt), family, bold, italic, underline, strike,
                            blockStyle.textAlign || 'left', blockTag);
                        } catch (e) { }
                      }

                      window.stomakeRememberSelection = function () { remember(true); };
                      window.stomakeUpdateFormat = updateFormat;
                      window.stomakeCommand = function (command, value) {
                        restore();
                        const sel = selection();
                        const collapsed = !!(sel && sel.rangeCount && sel.isCollapsed);
                        const inline = ['bold', 'italic', 'underline', 'strikeThrough'].indexOf(command) >= 0;
                        let before = false;
                        if (collapsed && inline) {
                          const element = activeElement();
                          const style = getComputedStyle(element);
                          const decoration = (style.textDecorationLine || style.textDecoration || '').toLowerCase();
                          const fallback = command === 'bold'
                            ? ((parseInt(style.fontWeight, 10) || 400) >= 600 || !!element.closest('b,strong'))
                            : command === 'italic'
                              ? (style.fontStyle === 'italic' || !!element.closest('i,em'))
                              : command === 'underline'
                                ? (decoration.indexOf('underline') >= 0 || !!element.closest('u,ins'))
                                : (decoration.indexOf('line-through') >= 0 || !!element.closest('s,strike,del'));
                          before = inlineState(command, fallback);
                        }
                        document.execCommand('styleWithCSS', false, true);
                        document.execCommand(command, false, value);
                        if (collapsed && inline) {
                          const afterSel = selection();
                          window.__stomakePendingInline = afterSel ? {
                            command: command,
                            value: !before,
                            node: afterSel.anchorNode,
                            offset: afterSel.anchorOffset
                          } : null;
                        } else {
                          window.__stomakePendingInline = null;
                        }
                        remember(true);
                        updateFormat();
                        return !(collapsed && inline);
                      };
                      window.stomakeSetFont = function (family) {
                        restore();
                        const sel = selection();
                        if (!sel || !sel.rangeCount) return false;
                        if (sel.isCollapsed) {
                          const marker = ensureCaretMarker();
                          if (!marker) return false;
                          marker.style.fontFamily = family;
                          remember(true);
                          updateFormat();
                          return false;
                        }
                        const changed = applyRangeStyle('fontFamily', family);
                        remember(true);
                        return changed;
                      };
                      window.stomakeSetFontSize = function (pt) {
                        restore();
                        const size = Math.max(6, Math.min(200, Number(pt) || 12));
                        const changed = applyRangeStyle('fontSize', size + 'pt');
                        remember(true);
                        updateFormat();
                        return changed;
                      };
                      window.stomakeCleanHtml = function () {
                        const copy = editable.cloneNode(true);
                        copy.querySelectorAll('span[data-stomake-caret="1"]').forEach(marker => {
                          marker.removeAttribute('data-stomake-caret');
                          const walker = document.createTreeWalker(marker, NodeFilter.SHOW_TEXT);
                          const texts = [];
                          while (walker.nextNode()) texts.push(walker.currentNode);
                          texts.forEach(node => { node.nodeValue = node.nodeValue.split(ZWSP).join(''); });
                          if (!marker.textContent && !marker.children.length) marker.remove();
                        });
                        return copy.innerHTML;
                      };

                      document.addEventListener('selectionchange', function () {
                        const sel = selection();
                        if (document.hasFocus() && sel && sel.anchorNode && editable.contains(sel.anchorNode)) {
                          const pending = window.__stomakePendingInline;
                          if (pending && (!sel.isCollapsed || pending.node !== sel.anchorNode || pending.offset !== sel.anchorOffset)) {
                            window.__stomakePendingInline = null;
                          }
                          demoteOldCaretMarker();
                          remember(false);
                          window.requestAnimationFrame(updateFormat);
                        }
                      });
                      document.addEventListener('keyup', function () {
                        remember(false);
                        updateFormat();
                      });
                      document.addEventListener('mouseup', function () {
                        remember(false);
                        updateFormat();
                      });
                      document.addEventListener('input', function () {
                        cleanCurrentSentinel();
                        window.__stomakePendingInline = null;
                        remember(true);
                        window.stomakeBridge.inputChanged();
                        updateFormat();
                      });
                      document.addEventListener('focusin', function () { updateFormat(); });
                      updateFormat();
                    })();
                    """;
        }

        private String editorHtml(String body) {
            String theme = editorThemeCss(currentTheme());
            return "<!doctype html><html><head><meta charset='utf-8'><style>" +
                    "html,body{margin:0;min-height:100%;background:#181a24;color:#e9e7ef;}" +
                    "body{box-sizing:border-box;min-height:100vh;font-family:Garamond,serif;font-size:12pt;line-height:1.75;padding:34px 48px;max-width:100%;outline:none;border:0;}" +
                    "p{margin:0 0 1em}h1,h2,h3{line-height:1.25;color:inherit}img{max-width:100%;height:auto}a{color:#b8a8ff}" + theme +
                    "</style></head><body contenteditable='true' spellcheck='true'>" + body + "</body></html>";
        }

        private void insertChapterImage() {
            try {
                String data=ImageCropDialog.chooseCroppedBase64(stage,"Sisipkan Gambar Bab"); if(data==null)return;
                TextInputDialog width=new TextInputDialog("70");width.setTitle("Lebar Gambar");width.setHeaderText("Atur lebar gambar dalam persen (20–100)");width.setContentText("Lebar (%):");
                width.showAndWait().ifPresent(value->{try{int pct=Math.max(20,Math.min(100,Integer.parseInt(value.trim())));String html="<img src=\"data:image/png;base64,"+data+"\" style=\"width:"+pct+"%;height:auto;display:block;margin:16px auto;\" />";engine.executeScript("document.execCommand('insertHTML',false,'"+html+"')");webView.requestFocus();saveDelay.playFromStart();}catch(Exception ex){error("Gambar",ex);}});
            } catch(Exception ex){error("Sisipkan Gambar",ex);}
        }
        private void resizeSelectedImage() {
            TextInputDialog d=new TextInputDialog("70");d.setTitle("Lebar Gambar");d.setHeaderText("Pilih gambar di editor lalu masukkan lebar persen.");d.setContentText("Lebar (%):");
            d.showAndWait().ifPresent(value->{try{int pct=Math.max(20,Math.min(100,Integer.parseInt(value.trim())));String js="(function(){var s=window.getSelection();var n=s&&s.anchorNode?s.anchorNode.parentElement:null;var img=n&&n.tagName==='IMG'?n:(n?n.querySelector('img'):null);if(img){img.style.width='"+pct+"%';img.style.height='auto';img.style.display='block';img.style.margin='16px auto';}})()";engine.executeScript(js);saveDelay.playFromStart();}catch(Exception ex){error("Atur gambar",ex);}});
        }
        private void removeSelectedImage() {
            engine.executeScript("(function(){var s=window.getSelection();var n=s&&s.anchorNode?s.anchorNode.parentElement:null;var img=n&&n.tagName==='IMG'?n:(n?n.querySelector('img'):null);if(img)img.remove();})()");saveDelay.playFromStart();
        }

        private void refreshChapters() throws Exception {
            List<Chapter> rows = repo.chapters(project.id());
            chapterList.getItems().setAll(rows);
            if (!rows.isEmpty()) chapterList.getSelectionModel().select(0);
        }

        private void loadChapter(Chapter chapter) {
            current = chapter;
            titleField.setText(chapter.title());
            String body = chapter.html() == null || chapter.html().isBlank() ? escapeHtml(chapter.content()).replace("\n", "<br>") : chapter.html();
            String html = editorHtml(body);
            engine.loadContent(html);
            status.setText("Menyunting · " + chapter.title());
        }

        private void saveCurrent() {
            saveCurrentQuietly();
            status.setText("Tersimpan · " + java.time.LocalTime.now().withNano(0));
        }
        private void saveCurrentQuietly() {
            if (current == null || loading) return;
            try {
                Object htmlObj = engine.executeScript("window.stomakeCleanHtml ? window.stomakeCleanHtml() : document.body.innerHTML");
                Object textObj = engine.executeScript("document.body.innerText");
                String html = htmlObj == null ? "" : htmlObj.toString();
                String text = textObj == null ? "" : textObj.toString();
                repo.saveChapter(current.id(), titleField.getText().isBlank() ? current.title() : titleField.getText(), html, text);
                current = new Chapter(current.id(), current.projectId(), titleField.getText(), text, html, current.position());
                status.setText("Tersimpan otomatis");
            } catch (Exception ex) { status.setText("Belum tersimpan: " + ex.getMessage()); }
        }
        private void addChapter() {
            try { saveCurrentQuietly(); long id = repo.createChapter(project.id()); refreshChapters(); chapterList.getItems().stream().filter(c -> c.id() == id).findFirst().ifPresent(chapterList.getSelectionModel()::select); }
            catch (Exception ex) { error("Bab baru", ex); }
        }
        private void deleteChapter() {
            Chapter selected = chapterList.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            confirm("Hapus bab", "Bab ini akan dihapus permanen.", () -> runUi(() -> {
                try (var c = database.open(); var p = c.prepareStatement("DELETE FROM chapters WHERE id=?")) { p.setLong(1, selected.id()); p.executeUpdate(); }
            }, () -> { activeEditor = null; openEditor(project.id()); }));
        }
    }

    private void showBible() {
        try { showPage(new BibleView(), "bible", "Story Bible"); }
        catch (Exception ex) { error("Story Bible", ex); }
    }

    private final class BibleView extends BorderPane {
        private final ComboBox<Project> projectBox = new ComboBox<>();
        private final ComboBox<String> kindBox = new ComboBox<>();
        private final ListView<StoryRecord> recordList = new ListView<>();
        private final TextField name = new TextField();
        private final TextField category = new TextField();
        private final ComboBox<String> role = new ComboBox<>();
        private final TextArea details = new TextArea();
        private final ImageView imagePreview = new ImageView();
        private final ListView<String> galleryList = new ListView<>();
        private final List<GalleryImage> galleryItems = new ArrayList<>();
        private String imageData = "";
        private double imageWidth = 1.0;
        private final Map<String, String> customKindStorage = new LinkedHashMap<>();
        private final Map<String, String> customKindDisplay = new LinkedHashMap<>();
        private StoryRecord selected;
        private boolean loadingRecord;

        BibleView() throws Exception {
            getStyleClass().add("page-content");
            role.setEditable(true);
            role.setPromptText("Pilih atau tulis peran karakter...");
            role.getItems().setAll("Protagonist", "Antagonist", "Deuteragonist", "Supporting Character",
                    "Mentor", "Rival", "Love Interest", "Friend", "Family", "NPC", "Original Character", "Custom");
            role.setCellFactory(list -> new ListCell<>() {
                @Override protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty || item == null ? null : item);
                    if (!empty && item != null) setTooltip(new Tooltip(storyRoleHint(item)));
                    else setTooltip(null);
                }
            });
            projectBox.getItems().setAll(repo.projects());
            if (activeProjectId > 0) projectBox.getItems().stream().filter(p -> p.id() == activeProjectId).findFirst().ifPresent(projectBox::setValue);
            if (projectBox.getValue() == null && !projectBox.getItems().isEmpty()) projectBox.getSelectionModel().select(0);
            kindBox.getItems().addAll("Karakter", "Dunia", "Lokasi", "Barang", "Faksi", "Lore");
            for (Repository.CustomCategory custom : repo.customCategories()) {
                String storageKey = "custom_" + custom.id();
                String displayName = custom.name();
                if (kindBox.getItems().contains(displayName)) displayName = displayName + " (custom)";
                customKindStorage.put(displayName, storageKey);
                customKindDisplay.put(storageKey, displayName);
                kindBox.getItems().add(displayName);
            }
            kindBox.setValue("Karakter");
            VBox left = new VBox(12); left.getStyleClass().add("panel-card"); left.setPrefWidth(270);
            HBox selector = new HBox(8, projectBox); HBox.setHgrow(projectBox, Priority.ALWAYS);
            Label listLabel = new Label("ENTRI BIBLE"); listLabel.getStyleClass().add("nav-section-title");
            VBox.setVgrow(recordList, Priority.ALWAYS); recordList.getStyleClass().add("chapter-list");
            recordList.setCellFactory(v -> new ListCell<>() { @Override protected void updateItem(StoryRecord item, boolean empty) { super.updateItem(item, empty); setText(empty || item == null ? null : item.name()); } });
            recordList.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> { if (b != null) loadRecord(b); });
            Button add = new Button("＋  Entri baru"); add.getStyleClass().add("button-primary"); add.setMaxWidth(Double.MAX_VALUE); add.setOnAction(e -> newRecord());
            left.getChildren().addAll(selector, listLabel, recordList, add);
            VBox form = new VBox(14); form.getStyleClass().add("panel-card");
            Label heading = new Label("Detail entri"); heading.getStyleClass().add("section-heading");
            GridPane fields = new GridPane(); fields.setHgap(12); fields.setVgap(10);
            addField(fields, 0, "Jenis", kindBox); addField(fields, 1, "Nama", name); addField(fields, 2, "Kategori", category); addField(fields, 3, "Peran karakter", role);
            Label roleSuggestions = new Label("Saran: Protagonist (tokoh utama), Antagonist (penentang), Supporting Character (pendukung), Mentor (pembimbing), Rival (saingan), Love Interest, NPC, atau tulis peran sendiri.");
            roleSuggestions.getStyleClass().add("muted-label");
            roleSuggestions.setWrapText(true);
            fields.add(roleSuggestions, 1, 4);
            details.setPrefRowCount(8); details.setWrapText(true);
            details.setPromptText("Tuliskan biodata, deskripsi karakter, backstory, atau detail entri di sini...");
            VBox media = new VBox(8); media.getStyleClass().add("panel-card");
            Label mediaTitle=new Label("GAMBAR & GALERI");mediaTitle.getStyleClass().add("nav-section-title");
            imagePreview.setFitWidth(260);imagePreview.setFitHeight(190);imagePreview.setPreserveRatio(true);
            HBox mainImageActions=new HBox(8);
            Button pickImage=new Button("Pilih / Crop Gambar");pickImage.getStyleClass().add("button-quiet");pickImage.setOnAction(e->chooseBibleImage());
            Button removeImage=new Button("Hapus Gambar");removeImage.getStyleClass().add("button-quiet");removeImage.setOnAction(e->{imageData="";imagePreview.setImage(null);});
            Button resizeImage=new Button("Atur Lebar");resizeImage.getStyleClass().add("button-quiet");resizeImage.setOnAction(e->resizeBibleImage());
            mainImageActions.getChildren().addAll(pickImage,removeImage,resizeImage);
            galleryList.setPrefHeight(110);
            Button addGallery=new Button("＋ Tambah Gambar");addGallery.getStyleClass().add("button-quiet");addGallery.setOnAction(e->addGalleryImage());
            Button removeGallery=new Button("Hapus");removeGallery.getStyleClass().add("button-quiet");removeGallery.setOnAction(e->removeGalleryImage());
            Button resizeGallery=new Button("Lebar");resizeGallery.getStyleClass().add("button-quiet");resizeGallery.setOnAction(e->resizeGalleryImage());
            Button layoutGallery=new Button("Layout");layoutGallery.getStyleClass().add("button-quiet");layoutGallery.setOnAction(e->layoutGalleryImage());
            media.getChildren().addAll(mediaTitle,new Label("Gambar utama"),imagePreview,mainImageActions,new Label("Galeri gambar"),galleryList,new HBox(8,addGallery,removeGallery,resizeGallery,layoutGallery));
            Button save = new Button("Simpan entri"); save.getStyleClass().add("button-primary"); save.setOnAction(e -> saveRecord());
            Button delete = new Button("Hapus"); delete.getStyleClass().add("button-danger"); delete.setOnAction(e -> deleteRecord());
            HBox actions = new HBox(8, save, delete);
            form.getChildren().addAll(heading, fields, new Label("Deskripsi / detail"), details, media, actions);
            ScrollPane formScroll = new ScrollPane(form); formScroll.setFitToWidth(true); formScroll.getStyleClass().add("page-scroll");
            SplitPane split = new SplitPane(left, formScroll); split.setDividerPositions(0.29);
            setCenter(split);
            projectBox.valueProperty().addListener((o, a, b) -> { newRecord(); refresh(); });
            kindBox.valueProperty().addListener((o, a, b) -> {
                if (!loadingRecord) { newRecord(); refresh(); }
            });
            refresh();
        }

        private String kindToStorage(String display) {
            if (display == null) return "character";
            String custom = customKindStorage.get(display);
            if (custom != null) return custom;
            return switch (display) {
                case "Dunia" -> "world"; case "Lokasi" -> "location"; case "Barang" -> "item";
                case "Faksi" -> "faction"; case "Lore" -> "lore"; default -> "character";
            };
        }
        private String kindFromStorage(String stored) {
            if (stored == null) return "Karakter";
            String custom = customKindDisplay.get(stored);
            if (custom != null) return custom;
            return switch (stored) {
                case "world" -> "Dunia"; case "location" -> "Lokasi"; case "item" -> "Barang";
                case "faction" -> "Faksi"; case "lore" -> "Lore"; case "characters", "character" -> "Karakter";
                default -> "Karakter";
            };
        }
        private String storyRoleHint(String value) {
            return switch (value) {
                case "Protagonist" -> "Tokoh utama dalam cerita.";
                case "Antagonist" -> "Tokoh penentang atau sumber konflik.";
                case "Deuteragonist" -> "Tokoh utama kedua dengan peran penting.";
                case "Supporting Character" -> "Tokoh pendukung perkembangan cerita.";
                case "Mentor" -> "Pembimbing atau pemberi arahan.";
                case "Rival" -> "Saingan yang mendorong konflik atau perkembangan.";
                case "Love Interest" -> "Tokoh yang memiliki hubungan romantis dengan karakter lain.";
                case "Friend" -> "Teman atau sekutu.";
                case "Family" -> "Anggota keluarga atau kerabat.";
                case "NPC" -> "Tokoh tambahan yang mengisi dunia cerita.";
                case "Original Character" -> "Karakter original buatan kreator.";
                default -> "Peran bebas yang bisa kamu tentukan sendiri.";
            };
        }

        private void addField(GridPane grid, int row, String label, Node field) {
            Label l = new Label(label); l.getStyleClass().add("field-caption");
            grid.add(l, 0, row); grid.add(field, 1, row); GridPane.setHgrow(field, Priority.ALWAYS);
        }
        private void refresh() {
            try { if (projectBox.getValue() != null) recordList.getItems().setAll(repo.storyRecords(projectBox.getValue().id(), kindToStorage(kindBox.getValue()))); }
            catch (Exception ex) { error("Memuat Story Bible", ex); }
        }
        private void newRecord() {
            selected = null;
            recordList.getSelectionModel().clearSelection();
            name.clear(); category.clear(); role.setValue(null); role.getEditor().clear(); details.clear(); imageData="";imageWidth=1.0;imagePreview.setImage(null);galleryItems.clear();refreshGallery();
        }
        private void loadRecord(StoryRecord r) {
            loadingRecord = true;
            try {
                selected = r;
                kindBox.setValue(kindFromStorage(r.kind()));
                name.setText(r.name()); category.setText(r.category()); role.setValue(r.role()); role.getEditor().setText(r.role()); details.setText(r.details());
                imageData=r.imageData();imageWidth=r.imageWidth();imagePreview.setImage(ImageCropDialog.fromBase64(imageData));imagePreview.setFitWidth(260*imageWidth);
                loadGallery(r.galleryData());
            } finally { loadingRecord = false; }
        }
        private void saveRecord() {
            if (projectBox.getValue() == null || name.getText().isBlank()) { info("Isi nama entri terlebih dahulu."); return; }
            try {
                String legacyTraits = selected == null ? "" : selected.customTraits();
                String roleValue = role.getEditor().getText() == null ? "" : role.getEditor().getText().trim();
                if (roleValue.isBlank() && role.getValue() != null) roleValue = role.getValue();
                long id = repo.saveStoryRecord(new StoryRecord(selected == null ? 0 : selected.id(), projectBox.getValue().id(), kindToStorage(kindBox.getValue()), name.getText().trim(), category.getText(), details.getText(), roleValue, "{}", legacyTraits, imageData, imageWidth, galleryJson()));
                refresh(); recordList.getItems().stream().filter(r -> r.id() == id).findFirst().ifPresent(recordList.getSelectionModel()::select);
                info("Entri Story Bible tersimpan.");
            } catch (Exception ex) { error("Simpan entri", ex); }
        }
        private void deleteRecord() {
            if (selected == null) return;
            confirm("Hapus entri", "Entri ini akan dihapus dari Story Bible.", () -> runUi(() -> repo.deleteStoryRecord(selected.id()), () -> { newRecord(); refresh(); }));
        }
        private void chooseBibleImage(){try{String data=ImageCropDialog.chooseCroppedBase64(stage,"Pilih Gambar Story Bible");if(data!=null){imageData=data;imageWidth=1.0;imagePreview.setImage(ImageCropDialog.fromBase64(data));imagePreview.setFitWidth(260);}}catch(Exception ex){error("Gambar Bible",ex);}}
        private void resizeBibleImage(){TextInputDialog d=new TextInputDialog(String.valueOf((int)(imageWidth*100)));d.setTitle("Lebar gambar");d.setHeaderText("Lebar relatif gambar utama (20–100%)");d.setContentText("Lebar (%):");d.showAndWait().ifPresent(v->{try{imageWidth=Math.max(20,Math.min(100,Integer.parseInt(v.trim())))/100.0;imagePreview.setFitWidth(260*imageWidth);}catch(Exception ex){info("Masukkan angka 20–100.");}});}
        private void loadGallery(String json){galleryItems.clear();try{var nodes=new com.fasterxml.jackson.databind.ObjectMapper().readTree(json==null?"[]":json);if(nodes.isArray())for(var item:nodes){String data=item.path("data").asText("");if(!data.isBlank())galleryItems.add(new GalleryImage(data,item.path("width").asDouble(1.0),item.path("layout").asText("center")));}}catch(Exception ignored){}refreshGallery();}
        private void refreshGallery(){galleryList.getItems().setAll(galleryItems.stream().map(g->"Gambar "+(galleryItems.indexOf(g)+1)+" · "+(int)(g.width*100)+"% · "+g.layout).toList());}
        private String galleryJson(){try{var mapper=new com.fasterxml.jackson.databind.ObjectMapper();var arr=mapper.createArrayNode();for(GalleryImage g:galleryItems){var n=arr.addObject();n.put("data",g.data);n.put("width",g.width);n.put("layout",g.layout);}return mapper.writeValueAsString(arr);}catch(Exception ex){return "[]";}}
        private void addGalleryImage(){try{String data=ImageCropDialog.chooseCroppedBase64(stage,"Tambah Gambar Galeri");if(data!=null){galleryItems.add(new GalleryImage(data,1.0,"center"));refreshGallery();}}catch(Exception ex){error("Galeri",ex);}}
        private int selectedGalleryIndex(){return galleryList.getSelectionModel().getSelectedIndex();}
        private void removeGalleryImage(){int i=selectedGalleryIndex();if(i>=0){galleryItems.remove(i);refreshGallery();}}
        private void resizeGalleryImage(){int i=selectedGalleryIndex();if(i<0)return;GalleryImage old=galleryItems.get(i);TextInputDialog d=new TextInputDialog(String.valueOf((int)(old.width*100)));d.setTitle("Lebar galeri");d.setHeaderText("Lebar relatif gambar (20–100%)");d.setContentText("Lebar (%):");d.showAndWait().ifPresent(v->{try{old.width=Math.max(20,Math.min(100,Integer.parseInt(v.trim())))/100.0;refreshGallery();galleryList.getSelectionModel().select(i);}catch(Exception ex){info("Masukkan angka 20–100.");}});}
        private void layoutGalleryImage(){int i=selectedGalleryIndex();if(i<0)return;ChoiceDialog<String>d=new ChoiceDialog<>(galleryItems.get(i).layout,"center","left","right");d.setTitle("Layout Gambar");d.setHeaderText("Posisi gambar di halaman baca");d.setContentText("Layout:");d.showAndWait().ifPresent(v->{galleryItems.get(i).layout=v;refreshGallery();galleryList.getSelectionModel().select(i);});}

    }

    private void showCharacterStudio() {
        activateRoleplayMode();
    }

    private static final class GalleryImage { String data; double width; String layout; GalleryImage(String data,double width,String layout){this.data=data;this.width=width;this.layout=layout;} }

    private void showRelationships() {
        try { showPage(new RelationshipPane(repo, activeProjectId), "relationships", "Relasi Karakter"); }
        catch(Exception ex){error("Relasi Karakter",ex);}
    }

    private void showVersionHistory(long projectId) {
        try {
            if(activeEditor!=null) activeEditor.saveCurrentQuietly();
            List<Chapter> chapters=repo.chapters(projectId);if(chapters.isEmpty()){info("Belum ada bab.");return;}
            Dialog<ButtonType> dialog=new Dialog<>();dialog.setTitle("Riwayat Versi Bab");dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);
            ComboBox<Chapter> chapterBox=new ComboBox<>();chapterBox.getItems().setAll(chapters);
            if(activeEditor!=null&&activeEditor.current!=null)chapterBox.getItems().stream().filter(c->c.id()==activeEditor.current.id()).findFirst().ifPresent(chapterBox::setValue);
            if(chapterBox.getValue()==null)chapterBox.getSelectionModel().select(0);
            ListView<Repository.ChapterVersion> versions=new ListView<>();versions.setCellFactory(v->new ListCell<>(){@Override protected void updateItem(Repository.ChapterVersion item,boolean empty){super.updateItem(item,empty);setText(empty||item==null?null:item.createdAt()+" · "+item.title()+" · "+(item.content()==null?0:item.content().trim().split("\\s+").length)+" kata");}});
            WebView preview=new WebView();Button snapshot=new Button("＋ Simpan snapshot sekarang");snapshot.getStyleClass().add("button-quiet");Button restore=new Button("Pulihkan versi terpilih");restore.getStyleClass().add("button-primary");
            Runnable reload=()->{try{Chapter c=chapterBox.getValue();if(c!=null)versions.getItems().setAll(repo.chapterVersions(projectId,c.id()));}catch(Exception ex){error("Riwayat versi",ex);}};
            chapterBox.valueProperty().addListener((o,a,b)->reload.run());versions.getSelectionModel().selectedItemProperty().addListener((o,a,b)->{if(b!=null)preview.getEngine().loadContent("<html><body style='font-family:Garamond;padding:28px;line-height:1.7'><h2>"+escapeHtml(b.title())+"</h2>"+(b.html().isBlank()?escapeHtml(b.content()).replace("\n","<br>"):b.html())+"</body></html>");});
            snapshot.setOnAction(e->{try{Chapter c=chapterBox.getValue();if(c!=null)repo.snapshotChapter(projectId,c.id());reload.run();}catch(Exception ex){error("Snapshot",ex);}});
            restore.setOnAction(e->{Repository.ChapterVersion v=versions.getSelectionModel().getSelectedItem();Chapter c=chapterBox.getValue();if(v==null||c==null)return;confirm("Pulihkan versi", "Isi bab saat ini akan disimpan sebagai snapshot sebelum versi ini dipulihkan.",()->runUi(()->repo.restoreChapterVersion(projectId,c.id(),v.id()),()->{dialog.close();openEditor(projectId);}));});
            SplitPane panes=new SplitPane(versions,preview);panes.setDividerPositions(.34);VBox content=new VBox(9,new Label("Pilih bab"),chapterBox,panes,new HBox(8,snapshot,restore));content.setPadding(new Insets(14));VBox.setVgrow(panes,Priority.ALWAYS);dialog.getDialogPane().setContent(content);dialog.getDialogPane().setPrefSize(920,650);reload.run();dialog.showAndWait();
        }catch(Exception ex){error("Riwayat Versi",ex);}
    }

    private void showAiWriter() {
        try {
            String context = activeProjectId > 0 ? writerContext(activeProjectId) : "Belum ada proyek aktif.";
            WriterChatPane chat = new WriterChatPane(repo, ollama, activeProjectId, context);
            showPage(chat, "writer", "AI Penulis");
        } catch (Exception ex) {
            error("AI Penulis", ex);
        }
    }

    private String writerContext(long projectId) throws Exception {
        StringBuilder b = new StringBuilder();
        for (Project p : repo.projects()) if (p.id() == projectId) b.append("Judul: ").append(p.title()).append("\nSinopsis: ").append(p.synopsis()).append("\n");
        for (Chapter c : repo.chapters(projectId)) b.append("\nBab: ").append(c.title()).append("\n").append(c.content(), 0, Math.min(c.content().length(), 1400)).append("\n");
        for (StoryRecord r : repo.storyRecords(projectId, null)) b.append("\n").append(r.kind()).append(" — ").append(r.name()).append(": ").append(r.details(), 0, Math.min(r.details().length(), 700)).append("\n");
        return b.toString();
    }

    private void showTimeline() {
        VBox root = new VBox(14); root.getStyleClass().add("page-content");
        Label heading = new Label("Timeline cerita"); heading.getStyleClass().add("section-heading");
        ComboBox<Project> projects = new ComboBox<>();
        try { projects.getItems().setAll(repo.projects()); if (!projects.getItems().isEmpty()) { Project p = projects.getItems().stream().filter(x -> x.id() == activeProjectId).findFirst().orElse(projects.getItems().get(0)); projects.setValue(p); } }
        catch (Exception ex) { error("Timeline", ex); }
        ListView<String> events = new ListView<>(); VBox.setVgrow(events, Priority.ALWAYS);
        Runnable reload = () -> {
            try { if (projects.getValue() != null) events.getItems().setAll(repo.timeline(projects.getValue().id()).stream().map(e -> (e.date().isBlank() ? "Tanpa tanggal" : e.date()) + "   ·   " + e.title() + "\n" + e.description()).toList()); }
            catch (Exception ex) { error("Timeline", ex); }
        };
        projects.valueProperty().addListener((o, a, b) -> reload.run());
        Button add = new Button("＋ Tambah peristiwa"); add.getStyleClass().add("button-primary");
        add.setOnAction(e -> {
            if (projects.getValue() == null) return;
            Dialog<ButtonType> dialog = new Dialog<>(); dialog.setTitle("Peristiwa baru"); dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            TextField title = new TextField(); title.setPromptText("Nama peristiwa"); DatePicker date = new DatePicker(LocalDate.now()); TextArea detail = new TextArea(); detail.setPromptText("Deskripsi peristiwa"); detail.setPrefRowCount(4);
            VBox content = new VBox(8, new Label("Judul"), title, new Label("Tanggal"), date, new Label("Deskripsi"), detail); content.setPadding(new Insets(12)); dialog.getDialogPane().setContent(content);
            dialog.showAndWait().filter(x -> x == ButtonType.OK).ifPresent(x -> runUi(() -> repo.addTimelineEvent(projects.getValue().id(), title.getText(), date.getValue().toString(), detail.getText()), reload));
        });
        root.getChildren().addAll(heading, projects, add, events); reload.run();
        showPage(root, "timeline", "Timeline");
    }

    private void showStats() {
        VBox root = new VBox(18); root.getStyleClass().add("page-content");
        Label heading = new Label("Statistik menulis"); heading.getStyleClass().add("section-heading");
        ComboBox<Project> projects = new ComboBox<>();
        try { projects.getItems().setAll(repo.projects()); if (!projects.getItems().isEmpty()) projects.setValue(projects.getItems().stream().filter(p->p.id()==activeProjectId).findFirst().orElse(projects.getItems().get(0))); }
        catch (Exception ex) { error("Statistik", ex); }
        HBox metrics = new HBox(14);
        ProgressBar progress=new ProgressBar(0);progress.setMaxWidth(Double.MAX_VALUE);Label progressLabel=new Label("Target harian belum dipilih.");progressLabel.getStyleClass().add("muted-label");
        Spinner<Integer> goal=new Spinner<>(50,50000,500,50);goal.setEditable(true);Button saveGoal=new Button("Simpan target harian");saveGoal.getStyleClass().add("button-quiet");
        Runnable refresh = () -> {
            metrics.getChildren().clear(); if (projects.getValue() == null) return;
            try {
                long id=projects.getValue().id();int words=repo.wordCount(id),chapters=repo.chapterCount(id),records=repo.storyRecords(id,null).size();
                metrics.getChildren().addAll(metric("TOTAL KATA",String.format("%,d",words)),metric("JUMLAH BAB",String.valueOf(chapters)),metric("ENTRI BIBLE",String.valueOf(records)));
                int daily=repo.todayWordsWritten(id),target=repo.dailyWordGoal(id);goal.getValueFactory().setValue(target);progress.setProgress(Math.min(1.0,(double)daily/Math.max(1,target)));progressLabel.setText(String.format("Hari ini: %,d / %,d kata · %d%%",daily,target,(int)(progress.getProgress()*100)));
            }catch(Exception ex){error("Statistik",ex);}
        };
        projects.valueProperty().addListener((o,a,b)->refresh.run());
        saveGoal.setOnAction(e->{if(projects.getValue()!=null)runUi(()->repo.setDailyWordGoal(projects.getValue().id(),goal.getValue()),refresh);});
        VBox daily=new VBox(9);daily.getStyleClass().add("panel-card");Label dailyTitle=new Label("TARGET MENULIS HARIAN");dailyTitle.getStyleClass().add("nav-section-title");daily.getChildren().addAll(dailyTitle,progressLabel,progress,new HBox(8,new Label("Target kata"),goal,saveGoal));
        Label note = new Label("Hitungan kata memakai teks bab yang tersimpan. Aktivitas harian menghitung penambahan kata ketika autosave menyimpan perubahan."); note.getStyleClass().add("muted-label"); note.setWrapText(true);
        root.getChildren().addAll(heading,projects,metrics,daily,note);refresh.run();
        showPage(root,"stats","Statistik");
    }

    private Node metric(String label, String value) {
        VBox card = new VBox(9, new Label(label), new Label(value)); card.getStyleClass().add("metric-card");
        ((Label) card.getChildren().get(0)).getStyleClass().add("field-caption"); ((Label) card.getChildren().get(1)).getStyleClass().add("metric-value");
        HBox.setHgrow(card, Priority.ALWAYS); return card;
    }

    private void showSettings() {
        VBox root = new VBox(18); root.getStyleClass().add("page-content");
        Label heading = new Label("Pengaturan & Model AI"); heading.getStyleClass().add("section-heading");
        TextField url = new TextField(ollama.baseUrl()); url.setPrefWidth(360);
        TextField roleplay = new TextField(); TextField writer = new TextField();
        ComboBox<String> theme = new ComboBox<>(); theme.getItems().addAll("Dark","Light","Warm Light","Slate");
        try { theme.setValue(repo.setting("theme","Dark")); } catch(Exception ignored) { theme.setValue("Dark"); }
        theme.setOnAction(e -> {
            applyTheme(theme.getValue());
            try { repo.setSetting("theme", theme.getValue()); }
            catch (Exception ex) { error("Tema", ex); }
        });
        try { roleplay.setText(repo.setting("roleplay_model", OllamaClient.RP_UNCENSORED)); writer.setText(repo.setting("writer_model", "qwen2.5:3b")); }
        catch (Exception ignored) { roleplay.setText(OllamaClient.RP_UNCENSORED); writer.setText("qwen2.5:3b"); }
        ComboBox<String> presets = new ComboBox<>();
        presets.getItems().addAll(OllamaClient.RP_UNCENSORED, OllamaClient.LIGHT_UNCENSORED, OllamaClient.RP_QUALITY, OllamaClient.SMALL_ROLEPLAY, "dolphin-phi:latest");
        presets.setValue(roleplay.getText());
        presets.setOnAction(e -> roleplay.setText(presets.getValue()));
        Label status = new Label("Ollama belum diperiksa."); status.getStyleClass().add("muted-label");
        Button check = new Button("Periksa koneksi"); check.getStyleClass().add("button-quiet");
        check.setOnAction(e -> {
            ollama.setBaseUrl(url.getText()); status.setText("Memeriksa Ollama..."); check.setDisable(true);
            Task<List<String>> task = new Task<>() { @Override protected List<String> call() throws Exception { return ollama.listModels(); } };
            task.setOnSucceeded(ev -> { status.setText("Terhubung · " + task.getValue().size() + " model terpasang: " + String.join(", ", task.getValue())); check.setDisable(false); });
            task.setOnFailed(ev -> { status.setText("Tidak bisa terhubung: " + task.getException().getMessage()); check.setDisable(false); });
            new Thread(task, "Stomake-Ollama-Check").start();
        });
        Button pull = new Button("Unduh model yang dipilih"); pull.getStyleClass().add("button-primary");
        pull.setOnAction(e -> {
            String modelName = roleplay.getText().trim(); if (modelName.isBlank()) return;
            pull.setDisable(true); status.setText("Mengunduh " + modelName + "...");
            Task<Void> task = new Task<>() { @Override protected Void call() throws Exception { ollama.pull(modelName); return null; } };
            task.setOnSucceeded(ev -> { status.setText("Model selesai diunduh: " + modelName); pull.setDisable(false); });
            task.setOnFailed(ev -> { status.setText("Gagal mengunduh: " + task.getException().getMessage()); pull.setDisable(false); });
            new Thread(task, "Stomake-Ollama-Pull").start();
        });
        Button save = new Button("Simpan pengaturan"); save.getStyleClass().add("button-primary");
        save.setOnAction(e -> runUi(() -> {
            ollama.setBaseUrl(url.getText()); repo.setSetting("ollama_url", url.getText()); repo.setSetting("roleplay_model", roleplay.getText()); repo.setSetting("writer_model", writer.getText()); repo.setSetting("theme", theme.getValue());
        }, () -> info("Pengaturan tersimpan.")));
        VBox models = new VBox(10); models.getStyleClass().add("panel-card");
        Label modelTitle = new Label("PILIHAN MODEL ROLEPLAY"); modelTitle.getStyleClass().add("nav-section-title");
        models.getChildren().addAll(modelTitle,
                modelInfo("Gemma 3 4B Heretic · rekomendasi roleplay", OllamaClient.RP_UNCENSORED, "Fine-tune untuk creative writing dan roleplay uncensored. Q4 sekitar 2,5 GB; tetap tutup aplikasi berat saat menjalankannya di RAM 8 GB."),
                modelInfo("Qwen2.5 3B Abliterated · ringan", OllamaClient.LIGHT_UNCENSORED, "Alternatif lebih ringan untuk laptop terbatas, tetapi bukan fine-tune khusus roleplay."),
                modelInfo("Qwen3 8B Heretic · kualitas lebih tinggi", OllamaClient.RP_QUALITY, "Fine-tune roleplay uncensored yang lebih besar. Q4 sekitar 5 GB di disk dan terlalu berat untuk RAM 8 GB sebagai pilihan utama."),
                modelInfo("Nemotron Mini 4B", OllamaClient.SMALL_ROLEPLAY, "Model kecil yang dioptimalkan untuk roleplay bahasa Inggris, tetapi bukan model uncensored."));
        GridPane settings = new GridPane(); settings.setVgap(12); settings.setHgap(14);
        addSetting(settings, 0, "Alamat Ollama", url); addSetting(settings, 1, "Model chat karakter", presets); addSetting(settings, 2, "Nama model tersimpan", roleplay); addSetting(settings, 3, "Model AI penulis", writer); addSetting(settings, 4, "Tema aplikasi", theme);
        HBox buttons = new HBox(8, check, pull, save);
        VBox backup = new VBox(10); backup.getStyleClass().add("panel-card");
        Label backupTitle = new Label("DATABASE & BACKUP"); backupTitle.getStyleClass().add("nav-section-title");
        Label dbPath = new Label("Lokasi database: " + database.file()); dbPath.getStyleClass().add("muted-label"); dbPath.setWrapText(true);
        Button backupButton = new Button("Buat backup database"); backupButton.getStyleClass().add("button-quiet"); backupButton.setOnAction(e -> backupDatabase());
        Button restoreButton = new Button("Pulihkan backup database"); restoreButton.getStyleClass().add("button-danger"); restoreButton.setOnAction(e -> restoreDatabase());
        Button migrateButton = new Button("Impor database Stomake 1.x"); migrateButton.getStyleClass().add("button-quiet"); migrateButton.setOnAction(e -> importLegacyDatabase());
        backup.getChildren().addAll(backupTitle, dbPath, new HBox(8, backupButton, restoreButton, migrateButton));
        root.getChildren().addAll(heading, models, settings, buttons, status, backup);
        ScrollPane scroll = new ScrollPane(root); scroll.setFitToWidth(true); scroll.getStyleClass().add("page-scroll");
        showPage(scroll, "settings", "Pengaturan");
    }

    private void applyTheme(String name) {
        getStyleClass().removeAll("light-theme","warm-theme","slate-theme");
        if("Light".equalsIgnoreCase(name))getStyleClass().add("light-theme");
        else if("Warm Light".equalsIgnoreCase(name))getStyleClass().add("warm-theme");
        else if("Slate".equalsIgnoreCase(name))getStyleClass().add("slate-theme");
        if (activeEditor != null) activeEditor.applyEditorTheme(name);
    }

    private Node modelInfo(String title, String model, String description) {
        VBox card = new VBox(4); card.getStyleClass().add("model-info");
        Label name = new Label(title); name.getStyleClass().add("card-title");
        Label id = new Label(model); id.getStyleClass().add("model-id");
        Label desc = new Label(description); desc.getStyleClass().add("muted-label"); desc.setWrapText(true);
        card.getChildren().addAll(name, id, desc); return card;
    }
    private void addSetting(GridPane grid, int row, String label, Node input) {
        Label l = new Label(label); l.getStyleClass().add("field-caption"); grid.add(l, 0, row); grid.add(input, 1, row); GridPane.setHgrow(input, Priority.ALWAYS);
    }
    private void backupDatabase() {
        FileChooser chooser = new FileChooser(); chooser.setTitle("Simpan backup Stomake"); chooser.setInitialFileName("Stomake-backup.db");
        var target = chooser.showSaveDialog(stage); if (target == null) return;
        try { database.checkpoint(); Files.copy(database.file(), target.toPath(), StandardCopyOption.REPLACE_EXISTING); info("Backup berhasil dibuat."); }
        catch (Exception ex) { error("Backup", ex); }
    }
    private void importLegacyDatabase() {
        FileChooser chooser = new FileChooser(); chooser.setTitle("Pilih database Stomake 1.x"); chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SQLite database", "*.db"));
        var source = chooser.showOpenDialog(stage); if (source == null) return;
        confirm("Impor database versi lama", "Pastikan Stomake versi lama sudah ditutup. Database aktif akan diganti; buat backup terlebih dahulu.", () -> {
            try {
                replaceDatabaseFrom(source.toPath());
                info("Database lama sudah disalin. Tutup dan buka kembali Stomake 2.0 agar migrasi skema dijalankan.");
            } catch (Exception ex) { error("Impor database", ex); }
        });
    }
    private void restoreDatabase() {
        FileChooser chooser = new FileChooser(); chooser.setTitle("Pilih backup Stomake"); chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SQLite database", "*.db"));
        var source = chooser.showOpenDialog(stage); if (source == null) return;
        confirm("Pulihkan backup", "Data saat ini akan diganti oleh file backup. Buat backup terlebih dahulu bila diperlukan.", () -> {
            try { replaceDatabaseFrom(source.toPath()); info("Database dipulihkan. Tutup dan buka kembali Stomake agar semua data dimuat ulang."); }
            catch (Exception ex) { error("Restore", ex); }
        });
    }

    private void replaceDatabaseFrom(Path source) throws Exception {
        Path target = database.file().toAbsolutePath().normalize();
        Path input = source.toAbsolutePath().normalize();
        if (target.equals(input)) throw new IOException("Pilih file database lain, bukan database yang sedang digunakan.");
        if (!Files.isRegularFile(input)) throw new IOException("File database tidak ditemukan.");
        database.checkpoint();
        Path temp = target.resolveSibling(target.getFileName() + ".restore.tmp");
        Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
        byte[] signature = new byte[16];
        try (var stream = Files.newInputStream(temp)) {
            if (stream.read(signature) != signature.length || !new String(signature, java.nio.charset.StandardCharsets.US_ASCII).startsWith("SQLite format 3")) {
                Files.deleteIfExists(temp);
                throw new IOException("File yang dipilih bukan database SQLite yang valid.");
            }
        }
        // All live transactions are checkpointed before replacing the main file.
        Files.deleteIfExists(Path.of(target + "-wal"));
        Files.deleteIfExists(Path.of(target + "-shm"));
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.deleteIfExists(Path.of(target + "-wal"));
        Files.deleteIfExists(Path.of(target + "-shm"));
    }

    private Node errorCard(String title, Exception ex) {
        VBox box = new VBox(6, new Label(title), new Label(ex.getMessage())); box.getStyleClass().add("empty-card"); return box;
    }
    private void confirm(String title, String message, Runnable yes) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.YES, ButtonType.NO); a.setTitle(title); a.setHeaderText(null);
        if (a.showAndWait().orElse(ButtonType.NO) == ButtonType.YES) yes.run();
    }
    private void info(String message) { Alert a = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK); a.setHeaderText(null); a.showAndWait(); }
    private void error(String title, Exception ex) { Alert a = new Alert(Alert.AlertType.ERROR, ex.getMessage(), ButtonType.OK); a.setTitle(title); a.setHeaderText("Terjadi kesalahan"); a.showAndWait(); }
    private void runUi(ThrowingAction action, Runnable success) {
        try { action.run(); if (success != null) success.run(); }
        catch (Exception ex) { error("Stomake", ex); }
    }
    @FunctionalInterface private interface ThrowingAction { void run() throws Exception; }

    private record EditorFormatState(int fontSize, String fontFamily, boolean bold, boolean italic,
                                     boolean underline, boolean strike, String alignment, String blockTag) { }

    public static final class EditorBridge {
        private final Runnable onChange;
        private final Consumer<EditorFormatState> onSelection;
        public EditorBridge(Runnable onChange, Consumer<EditorFormatState> onSelection) {
            this.onChange = onChange;
            this.onSelection = onSelection;
        }
        public void inputChanged() { onChange.run(); }
        public void selectionChanged(String size, String family, boolean bold, boolean italic,
                                     boolean underline, boolean strike, String alignment, String blockTag) {
            int parsed = 12;
            try { parsed = (int) Math.round(Double.parseDouble(size)); } catch (Exception ignored) { }
            onSelection.accept(new EditorFormatState(parsed, family == null ? "Garamond" : family,
                    bold, italic, underline, strike, alignment == null ? "left" : alignment,
                    blockTag == null ? "p" : blockTag));
        }
    }

    private static String jsQuote(String value) {
        if (value == null) return "null";
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r") + "'";
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
