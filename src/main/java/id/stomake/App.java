package id.stomake;

import id.stomake.ai.OllamaClient;
import id.stomake.data.Database;
import id.stomake.data.Repository;
import id.stomake.ui.MainView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;

public class App extends Application {
    private Database database;
    private Repository repository;
    private OllamaClient ollama;

    @Override
    public void start(Stage stage) throws Exception {
        Path dbFile = Database.prepareDataFile();
        database = new Database(dbFile);
        repository = new Repository(database);
        ollama = new OllamaClient();
        ollama.setBaseUrl(repository.setting("ollama_url", "http://localhost:11434"));

        MainView root = new MainView(stage, repository, database, ollama);
        stage.setOnCloseRequest(event -> root.saveBeforeExit());
        Scene scene = new Scene(root, 1280, 760);
        var css = getClass().getResource("/id/stomake/theme.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        stage.setTitle("Stomake 2.1.1 — Writing & Character Studio");
        stage.setMinWidth(1040);
        stage.setMinHeight(640);
        stage.setScene(scene);
        stage.centerOnScreen();
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
