package id.stomake;

import id.stomake.ai.OllamaClient;
import id.stomake.data.Database;
import id.stomake.data.Repository;
import id.stomake.ui.MainView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import java.nio.file.Files;
import java.nio.file.Path;

public class App extends Application {
    private Database database;
    private Repository repository;
    private OllamaClient ollama;

    @Override
    public void start(Stage stage) throws Exception {
        // Explicit custom chrome provides reliable minimize/maximize/fullscreen/close controls.
        stage.initStyle(StageStyle.UNDECORATED);
        stage.setResizable(true);
        stage.setFullScreenExitHint("Tekan ESC atau F11 untuk keluar dari fullscreen.");
        Path dbFile = Database.prepareDataFile();
        database = new Database(dbFile);
        repository = new Repository(database);
        ollama = new OllamaClient();
        ollama.setBaseUrl(repository.setting("ollama_url", "http://localhost:11434"));

        MainView root = new MainView(stage, repository, database, ollama);
        stage.setOnCloseRequest(event -> root.saveBeforeExit());
        Scene scene = new Scene(root, 1280, 760);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.F11) { root.toggleFullScreen(); event.consume(); }
            else if (event.getCode() == KeyCode.ESCAPE && stage.isFullScreen()) { stage.setFullScreen(false); event.consume(); }
        });
        var css = getClass().getResource("/id/stomake/theme.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        stage.setTitle("Stomake 2.2.3 — Story Maker & Roleplay");
        stage.setMinWidth(980);
        stage.setMinHeight(620);
        stage.setScene(scene);
        stage.centerOnScreen();
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
