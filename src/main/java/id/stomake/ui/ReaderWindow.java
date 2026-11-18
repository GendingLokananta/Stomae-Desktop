package id.stomake.ui;

import id.stomake.data.Repository;
import id.stomake.data.Repository.Chapter;
import id.stomake.data.Repository.Project;
import id.stomake.data.Repository.StoryRecord;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.ArrayList;
import java.util.List;

/** Distraction-free reader with its own window chrome, collapsible contents and selectable reading themes. */
public final class ReaderWindow {
    private record Section(String title, String html) { }
    private ReaderWindow() { }

    public static void open(Stage owner, Project project, Repository repo) throws Exception {
        List<Section> sections = buildSections(project, repo);

        Stage reader = new Stage();
        reader.initStyle(StageStyle.UNDECORATED);
        reader.initOwner(owner);
        reader.setTitle("Mode Baca — " + project.title());
        reader.setResizable(true);
        reader.setFullScreenExitHint("Tekan ESC atau F11 untuk keluar dari fullscreen.");

        WindowChrome chrome = new WindowChrome(reader, "Mode Baca · " + project.title());

        Label title = new Label("✦  " + project.title());
        title.getStyleClass().add("reader-heading");

        Button tocToggle = new Button("☰  Sembunyikan Daftar Isi");
        tocToggle.getStyleClass().add("button-quiet");
        ComboBox<String> themePicker = new ComboBox<>();
        themePicker.getItems().addAll("Paper", "Light", "Warm Light", "Dark", "Slate");
        themePicker.setTooltip(new javafx.scene.control.Tooltip("Tema tampilan membaca"));
        themePicker.setPrefWidth(145);
        String initialTheme = "Paper";
        try { initialTheme = repo.setting("reader_theme", "Paper"); }
        catch (Exception ignored) { }
        if (!themePicker.getItems().contains(initialTheme)) initialTheme = "Paper";
        themePicker.setValue(initialTheme);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(12, title, spacer, tocToggle, new Separator(), new Label("Tema"), themePicker);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(11, 18, 11, 18));
        toolbar.getStyleClass().add("reader-toolbar");
        VBox top = new VBox(chrome, toolbar);

        ListView<String> toc = new ListView<>();
        for (Section section : sections) toc.getItems().add(section.title());
        toc.setPrefWidth(250);
        toc.getStyleClass().add("reader-toc");

        WebView view = new WebView();
        view.getStyleClass().add("reader-webview");
        SplitPane split = new SplitPane(toc, view);
        split.setDividerPositions(0.23);

        BorderPane root = new BorderPane(split);
        root.getStyleClass().add("reader-root");
        root.setTop(top);

        final boolean[] tocVisible = { true };
        tocToggle.setOnAction(event -> {
            if (tocVisible[0]) {
                split.getItems().remove(toc);
                tocVisible[0] = false;
                tocToggle.setText("☰  Tampilkan Daftar Isi");
            } else {
                split.getItems().add(0, toc);
                tocVisible[0] = true;
                tocToggle.setText("☰  Sembunyikan Daftar Isi");
                split.setDividerPositions(0.23);
            }
        });

        final String[] selectedTheme = { themePicker.getValue() };
        themePicker.setOnAction(event -> {
            selectedTheme[0] = themePicker.getValue();
            try { repo.setSetting("reader_theme", selectedTheme[0]); }
            catch (Exception ignored) { }
            injectTheme(view, selectedTheme[0]);
        });

        toc.getSelectionModel().selectedIndexProperty().addListener((obs, oldValue, newValue) -> {
            int index = newValue == null ? -1 : newValue.intValue();
            if (index >= 0 && index < sections.size()) {
                view.getEngine().loadContent(wrap(sections.get(index).html(), selectedTheme[0]));
            }
        });

        if (!sections.isEmpty()) toc.getSelectionModel().select(0);
        else view.getEngine().loadContent(wrap("<h1>Belum ada konten</h1><p>Tambahkan bab atau materi ke cerita ini untuk mulai membaca.</p>", selectedTheme[0]));

        Scene scene = new Scene(root, 1280, 840);
        var css = ReaderWindow.class.getResource("/id/stomake/theme.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.F11) {
                chrome.toggleFullScreen();
                event.consume();
            } else if (event.getCode() == KeyCode.ESCAPE && reader.isFullScreen()) {
                reader.setFullScreen(false);
                event.consume();
            }
        });

        reader.setScene(scene);
        reader.setMinWidth(720);
        reader.setMinHeight(500);
        reader.show();
        reader.centerOnScreen();
    }

    private static List<Section> buildSections(Project project, Repository repo) throws Exception {
        List<Section> sections = new ArrayList<>();
        if (!project.coverImage().isBlank()) {
            sections.add(new Section("Sampul", "<div style='text-align:center'><img style='max-width:65%;max-height:75vh' src='data:image/png;base64," + project.coverImage() + "'></div><h1 style='text-align:center'>" + esc(project.title()) + "</h1>"));
        }
        if (!project.synopsis().isBlank()) {
            sections.add(new Section("Sinopsis", "<h1>Sinopsis</h1><p>" + esc(project.synopsis()).replace("\n", "</p><p>") + "</p>"));
        }
        int chapterNumber = 1;
        for (Chapter chapter : repo.chapters(project.id())) {
            String html = chapter.html() == null || chapter.html().isBlank()
                    ? "<p>" + esc(chapter.content()).replace("\n", "</p><p>") + "</p>"
                    : chapter.html();
            StringBuilder media = new StringBuilder();
            if (chapter.imageData() != null && !chapter.imageData().isBlank() && !html.contains(chapter.imageData())) {
                media.append("<p><img style='display:block;max-width:80%;height:auto;margin:16px auto' src='data:image/png;base64,")
                        .append(chapter.imageData()).append("'></p>");
            }
            try {
                var gallery = new com.fasterxml.jackson.databind.ObjectMapper().readTree(chapter.galleryData() == null ? "[]" : chapter.galleryData());
                if (gallery.isArray()) for (var item : gallery) {
                    String data = item.path("data").asText("");
                    if (!data.isBlank() && !html.contains(data)) {
                        int width = (int) Math.max(10, Math.min(100, item.path("width").asDouble(1.0) * 100));
                        String layout = item.path("layout").asText("center");
                        String align = "left".equalsIgnoreCase(layout) ? "float:left;margin:8px 18px 8px 0;"
                                : "right".equalsIgnoreCase(layout) ? "float:right;margin:8px 0 8px 18px;"
                                : "display:block;margin:16px auto;";
                        media.append("<p><img style='max-width:").append(width).append("%;height:auto;").append(align)
                                .append("' src='data:image/png;base64,").append(data).append("'></p>");
                    }
                }
            } catch (Exception ignored) { }
            sections.add(new Section("Bab " + chapterNumber + " — " + chapter.title(), "<h1>" + esc(chapter.title()) + "</h1>" + html + media));
            chapterNumber++;
        }
        for (StoryRecord record : repo.storyRecords(project.id(), null)) {
            String html = "<h1>" + esc(record.name()) + "</h1><p><b>" + esc(record.category()) + "</b> · " + esc(record.role()) + "</p><p>"
                    + esc(record.details()).replace("\n", "</p><p>") + "</p>";
            if (!record.imageData().isBlank()) html += "<p><img style='max-width:70%' src='data:image/png;base64," + record.imageData() + "'></p>";
            try {
                var gallery = new com.fasterxml.jackson.databind.ObjectMapper().readTree(record.galleryData());
                if (gallery.isArray()) for (var image : gallery) {
                    String data = image.path("data").asText("");
                    if (!data.isBlank()) html += "<p><img style='max-width:" + (int) (image.path("width").asDouble(1.0) * 100)
                            + "%' src='data:image/png;base64," + data + "'></p>";
                }
            } catch (Exception ignored) { }
            sections.add(new Section(record.category() + " — " + record.name(), html));
        }
        var relationships = repo.relationships(project.id());
        if (!relationships.isEmpty()) {
            StringBuilder html = new StringBuilder("<h1>Hubungan Karakter</h1>");
            for (var relationship : relationships) {
                html.append("<p><b>").append(esc(relationship.sourceName())).append("</b> — ")
                        .append(esc(relationship.relation())).append(" — <b>").append(esc(relationship.targetName()))
                        .append("</b><br>").append(esc(relationship.notes())).append("</p>");
            }
            sections.add(new Section("Hubungan Karakter", html.toString()));
        }
        var timeline = repo.timeline(project.id());
        if (!timeline.isEmpty()) {
            StringBuilder html = new StringBuilder("<h1>Timeline</h1>");
            for (var event : timeline) {
                html.append("<p><b>").append(esc(event.date().isBlank() ? "Tanpa tanggal" : event.date())).append(" — ")
                        .append(esc(event.title())).append("</b><br>").append(esc(event.description())).append("</p>");
            }
            sections.add(new Section("Timeline", html.toString()));
        }
        return sections;
    }

    private static String wrap(String html, String theme) {
        return "<!doctype html><html><head><meta charset='utf-8'><style>"
                + "body{font-family:Garamond,Georgia,serif;font-size:18px;line-height:1.85;max-width:920px;margin:52px auto;padding:0 42px}"
                + "h1,h2,h3{line-height:1.25}img{height:auto;max-width:100%}p{margin:0 0 1em}"
                + themeCss(theme) + "</style></head><body>" + html + "</body></html>";
    }

    private static void injectTheme(WebView view, String theme) {
        try {
            String css = themeCss(theme).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
            String script = "(function(){var s=document.getElementById('stomake-reader-theme');"
                    + "if(!s){s=document.createElement('style');s.id='stomake-reader-theme';document.head.appendChild(s);}"
                    + "s.textContent=\"" + css + "\";})();";
            view.getEngine().executeScript(script);
        } catch (Exception ignored) { }
    }

    private static String themeCss(String theme) {
        String background;
        String foreground;
        String link;
        switch (theme == null ? "Paper" : theme) {
            case "Dark" -> { background = "#181a24"; foreground = "#e9e7ef"; link = "#b8a8ff"; }
            case "Warm Light" -> { background = "#f1e8da"; foreground = "#3b3128"; link = "#7757a5"; }
            case "Slate" -> { background = "#26313d"; foreground = "#e7edf2"; link = "#a9c9ee"; }
            case "Light" -> { background = "#ffffff"; foreground = "#252638"; link = "#6554b7"; }
            default -> { background = "#fbfaf7"; foreground = "#292832"; link = "#6956a8"; }
        }
        return "html,body{background:" + background + " !important;color:" + foreground + " !important;}"
                + "body,body *{color:" + foreground + " !important;}a{color:" + link + " !important;}";
    }

    private static String esc(String value) {
        return (value == null ? "" : value).replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
