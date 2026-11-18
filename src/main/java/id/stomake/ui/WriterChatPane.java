package id.stomake.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import id.stomake.ai.OllamaClient;
import id.stomake.data.Repository;
import id.stomake.data.Repository.AiConversation;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.ArrayList;
import java.util.List;

/** Persistent, project-scoped writing assistant chat. */
public final class WriterChatPane extends BorderPane {
    private record Turn(String role, String content) {}

    private final Repository repo;
    private final OllamaClient ollama;
    private final long projectId;
    private final String storyContext;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ListView<AiConversation> history = new ListView<>();
    private final VBox transcript = new VBox(12);
    private final ScrollPane scroll = new ScrollPane(transcript);
    private final TextArea composer = new TextArea();
    private final ComboBox<String> model = new ComboBox<>();
    private final Label status = new Label("Chat AI tersimpan di perangkat ini.");
    private final Button send = new Button("Kirim  ↗");
    private final List<Turn> turns = new ArrayList<>();
    private long conversationId;
    private String conversationTitle = "Percakapan baru";
    private boolean loading;

    public WriterChatPane(Repository repo, OllamaClient ollama, long projectId, String storyContext) {
        this.repo = repo;
        this.ollama = ollama;
        this.projectId = projectId;
        this.storyContext = storyContext == null ? "" : storyContext;
        buildUi();
        loadHistory();
        refreshModels();
    }

    private void buildUi() {
        setPadding(new Insets(18));
        VBox sidebar = new VBox(10);
        sidebar.setPadding(new Insets(12));
        sidebar.setPrefWidth(230);
        sidebar.setMinWidth(185);
        sidebar.getStyleClass().add("panel-card");
        Label historyLabel = new Label("RIWAYAT CHAT"); historyLabel.getStyleClass().add("nav-section-title");
        Button newChat = new Button("＋  Percakapan baru"); newChat.getStyleClass().add("button-primary"); newChat.setMaxWidth(Double.MAX_VALUE);
        Button delete = new Button("Hapus percakapan"); delete.getStyleClass().add("button-quiet"); delete.setMaxWidth(Double.MAX_VALUE);
        history.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(AiConversation item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setTooltip(null); }
                else { setText(item.title()); setTooltip(new Tooltip(item.updatedAt() == null ? "" : item.updatedAt())); }
            }
        });
        VBox.setVgrow(history, Priority.ALWAYS);
        sidebar.getChildren().addAll(historyLabel, newChat, history, delete);
        newChat.setOnAction(e -> newConversation());
        delete.setOnAction(e -> deleteConversation());
        history.getSelectionModel().selectedItemProperty().addListener((obs, old, value) -> {
            if (!loading && value != null) openConversation(value);
        });

        VBox main = new VBox(12);
        main.setPadding(new Insets(4, 4, 4, 16));
        HBox top = new HBox(10); top.setAlignment(Pos.CENTER_LEFT);
        Label heading = new Label("AI Penulis"); heading.getStyleClass().add("section-heading");
        Region gap = new Region(); HBox.setHgrow(gap, Priority.ALWAYS);
        model.setEditable(true); model.setPrefWidth(270);
        try { model.setValue(repo.setting("writer_model", "qwen2.5:3b")); } catch (Exception ignored) { model.setValue("qwen2.5:3b"); }
        Button refresh = new Button("↻ Model"); refresh.getStyleClass().add("button-quiet"); refresh.setOnAction(e -> refreshModels());
        top.getChildren().addAll(heading, gap, new Label("MODEL"), model, refresh);
        transcript.getStyleClass().add("writer-transcript");
        transcript.setPadding(new Insets(16));
        scroll.setFitToWidth(true); scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("chat-scroll"); VBox.setVgrow(scroll, Priority.ALWAYS);
        composer.setPromptText("Tulis ide, pertanyaan, atau instruksi untuk partner menulismu… (Ctrl+Enter untuk mengirim)");
        composer.setWrapText(true); composer.setPrefRowCount(3); composer.setMaxHeight(110);
        composer.setOnKeyPressed(e -> {
            if (e.isControlDown() && e.getCode() == javafx.scene.input.KeyCode.ENTER) { sendMessage(); e.consume(); }
        });
        send.getStyleClass().add("button-primary"); send.setOnAction(e -> sendMessage());
        HBox inputRow = new HBox(10, composer, send); inputRow.setAlignment(Pos.BOTTOM_RIGHT); HBox.setHgrow(composer, Priority.ALWAYS);
        status.getStyleClass().add("muted-label");
        main.getChildren().addAll(top, scroll, inputRow, status);
        setLeft(sidebar); setCenter(main);
    }

    private void loadHistory() {
        try {
            List<AiConversation> rows = repo.aiConversations(projectId);
            loading = true;
            history.getItems().setAll(rows);
            loading = false;
            if (!rows.isEmpty()) history.getSelectionModel().select(0);
            else showEmptyState();
        } catch (Exception ex) { status.setText("Gagal memuat riwayat: " + ex.getMessage()); }
    }

    private void newConversation() {
        loading = true;
        history.getSelectionModel().clearSelection();
        loading = false;
        conversationId = 0;
        conversationTitle = "Percakapan baru";
        turns.clear();
        showEmptyState();
    }

    private void openConversation(AiConversation conversation) {
        try {
            conversationId = conversation.id();
            conversationTitle = conversation.title();
            turns.clear();
            JsonNode root = mapper.readTree(conversation.messages() == null ? "[]" : conversation.messages());
            if (root.isArray()) for (JsonNode item : root) {
                String role = item.path("role").asText("user");
                String content = item.path("content").asText("");
                if (!content.isBlank() && (role.equals("user") || role.equals("assistant"))) turns.add(new Turn(role, content));
            }
            renderTurns();
            status.setText("Percakapan tersimpan · " + turns.size() + " pesan");
        } catch (Exception ex) { status.setText("Gagal membuka percakapan: " + ex.getMessage()); }
    }

    private void deleteConversation() {
        AiConversation selected = history.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Hapus percakapan ‘" + selected.title() + "’?", ButtonType.CANCEL, ButtonType.OK);
        confirm.setHeaderText("Hapus riwayat AI Penulis");
        confirm.showAndWait().filter(x -> x == ButtonType.OK).ifPresent(x -> {
            try {
                repo.deleteAiConversation(selected.id(), projectId);
                newConversation(); loadHistory(); status.setText("Percakapan dihapus.");
            } catch (Exception ex) { status.setText("Gagal menghapus: " + ex.getMessage()); }
        });
    }

    private void sendMessage() {
        String prompt = composer.getText().trim();
        String chosenModel = model.getValue() == null ? "" : model.getValue().trim();
        if (prompt.isBlank() || chosenModel.isBlank() || send.isDisable()) return;
        if (conversationId == 0) conversationTitle = prompt.lines().findFirst().orElse("Percakapan baru").strip();
        if (conversationTitle.length() > 44) conversationTitle = conversationTitle.substring(0, 41) + "…";
        turns.add(new Turn("user", prompt));
        composer.clear();
        renderTurns();
        try { saveConversation(); }
        catch (Exception ex) { status.setText("Pesan belum tersimpan: " + ex.getMessage()); }
        send.setDisable(true); status.setText("AI sedang menulis…");
        List<OllamaClient.Message> messages = new ArrayList<>();
        messages.add(new OllamaClient.Message("system", "Kamu adalah partner penulisan fiksi yang kreatif, suportif, dan detail. Jawab dalam bahasa pengguna. Pertahankan fakta cerita; jangan mengubah canon tanpa diminta. Tawarkan pilihan yang berguna dan jangan mengulang seluruh konteks.\nKONTEKS CERITA:\n" + storyContext));
        for (Turn turn : turns) messages.add(new OllamaClient.Message(turn.role(), turn.content()));
        try { repo.setSetting("writer_model", chosenModel); } catch (Exception ignored) { }
        Task<String> task = new Task<>() {
            @Override protected String call() throws Exception { return ollama.chat(chosenModel, messages, 0.85, 4096, 900); }
        };
        task.setOnSucceeded(e -> {
            turns.add(new Turn("assistant", task.getValue()));
            try { saveConversation(); loadHistoryAndSelect(); status.setText("Tersimpan · " + turns.size() + " pesan"); }
            catch (Exception ex) { status.setText("Jawaban diterima, tetapi gagal menyimpan: " + ex.getMessage()); }
            renderTurns(); send.setDisable(false);
        });
        task.setOnFailed(e -> { status.setText("Ollama gagal: " + task.getException().getMessage()); send.setDisable(false); });
        Thread thread = new Thread(task, "Stomake-Writer-Chat"); thread.setDaemon(true); thread.start();
    }

    private void saveConversation() throws Exception {
        ArrayNode array = mapper.createArrayNode();
        for (Turn turn : turns) {
            ObjectNode item = array.addObject(); item.put("role", turn.role()); item.put("content", turn.content());
        }
        conversationId = repo.saveAiConversation(new AiConversation(conversationId, projectId, conversationTitle, mapper.writeValueAsString(array), ""));
    }

    private void loadHistoryAndSelect() throws Exception {
        List<AiConversation> rows = repo.aiConversations(projectId);
        loading = true;
        history.getItems().setAll(rows);
        for (AiConversation row : rows) if (row.id() == conversationId) { history.getSelectionModel().select(row); break; }
        loading = false;
    }

    private void refreshModels() {
        refreshModelButton(true);
        Task<List<String>> task = new Task<>() { @Override protected List<String> call() throws Exception { return ollama.listModels(); } };
        task.setOnSucceeded(e -> {
            String selected = model.getValue();
            model.getItems().setAll(task.getValue());
            if (selected != null && !selected.isBlank()) model.setValue(selected);
            else if (!task.getValue().isEmpty()) model.setValue(task.getValue().get(0));
            refreshModelButton(false); status.setText("Daftar model diperbarui · " + task.getValue().size() + " model");
        });
        task.setOnFailed(e -> { refreshModelButton(false); status.setText("Tidak dapat membaca model Ollama: " + task.getException().getMessage()); });
        Thread thread = new Thread(task, "Stomake-Writer-Models"); thread.setDaemon(true); thread.start();
    }

    private void refreshModelButton(boolean busy) {
        // Keep the toolbar responsive while a model list request is active.
        if (getCenter() instanceof VBox main && !main.getChildren().isEmpty() && main.getChildren().get(0) instanceof HBox toolbar) {
            for (Node node : toolbar.getChildren()) if (node instanceof Button b && "↻ Model".equals(b.getText())) b.setDisable(busy);
        }
    }

    private void renderTurns() {
        transcript.getChildren().clear();
        for (Turn turn : turns) {
            Label who = new Label(turn.role().equals("user") ? "KAMU" : "AI PENULIS"); who.getStyleClass().add("field-caption");
            Label body = new Label(turn.content()); body.setWrapText(true); body.setMaxWidth(740); body.getStyleClass().add("writer-message-text");
            VBox bubble = new VBox(6, who, body); bubble.getStyleClass().add(turn.role().equals("user") ? "writer-user-bubble" : "writer-ai-bubble"); bubble.setMaxWidth(780);
            HBox row = new HBox(bubble); row.setAlignment(turn.role().equals("user") ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT); transcript.getChildren().add(row);
        }
        if (turns.isEmpty()) showEmptyState();
        javafx.application.Platform.runLater(() -> scroll.setVvalue(1.0));
    }

    private void showEmptyState() {
        transcript.getChildren().clear();
        Label title = new Label("Mulai percakapan baru"); title.getStyleClass().add("panel-heading");
        Label hint = new Label("AI Penulis akan menggunakan sinopsis, bab, dan Story Bible proyek aktif sebagai konteks.");
        hint.setWrapText(true); hint.getStyleClass().add("muted-label");
        VBox empty = new VBox(8, title, hint); empty.setPadding(new Insets(40, 28, 40, 28)); empty.setAlignment(Pos.CENTER);
        transcript.getChildren().add(empty);
    }
}
