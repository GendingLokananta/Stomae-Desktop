package id.stomake.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

/** Custom Windows title bar with explicit minimize, maximize, fullscreen and close controls. */
public final class WindowChrome extends HBox {
    private final Stage stage;
    private final Button maximizeButton = new Button("□");
    private double dragOffsetX;
    private double dragOffsetY;

    public WindowChrome(Stage stage) {
        this.stage = stage;
        getStyleClass().add("window-chrome");
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(0, 8, 0, 16));
        setMinHeight(42);
        setPrefHeight(42);

        Label mark = new Label("S");
        mark.getStyleClass().add("window-brand-mark");
        Label title = new Label("Stomake 2.2");
        title.getStyleClass().add("window-title");
        HBox brand = new HBox(9, mark, title);
        brand.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button minimize = control("—", "Minimize");
        maximizeButton.getStyleClass().add("window-control-button");
        maximizeButton.setFocusTraversable(false);
        maximizeButton.setTooltip(new javafx.scene.control.Tooltip("Maximize / Restore"));
        Button fullscreen = control("⛶", "Fullscreen (F11)");
        Button close = control("×", "Close");
        close.getStyleClass().add("window-close-button");

        minimize.setOnAction(e -> stage.setIconified(true));
        maximizeButton.setOnAction(e -> toggleMaximized());
        fullscreen.setOnAction(e -> stage.setFullScreen(!stage.isFullScreen()));
        close.setOnAction(e -> stage.close());

        stage.maximizedProperty().addListener((obs, oldValue, maximized) ->
                maximizeButton.setText(maximized ? "❐" : "□"));
        stage.fullScreenProperty().addListener((obs, oldValue, fullScreen) -> {
            fullscreen.setText(fullScreen ? "⤢" : "⛶");
            fullscreen.setTooltip(new javafx.scene.control.Tooltip(fullScreen ? "Keluar Fullscreen (Esc)" : "Fullscreen (F11)"));
        });

        getChildren().addAll(brand, spacer, minimize, maximizeButton, fullscreen, close);
        attachDrag(brand);
        attachDrag(spacer);
    }

    private void attachDrag(Node node) {
        node.setOnMousePressed(e -> {
            dragOffsetX = e.getScreenX() - stage.getX();
            dragOffsetY = e.getScreenY() - stage.getY();
        });
        node.setOnMouseDragged(e -> {
            if (!stage.isMaximized() && !stage.isFullScreen() && e.isPrimaryButtonDown()) {
                stage.setX(e.getScreenX() - dragOffsetX);
                stage.setY(e.getScreenY() - dragOffsetY);
            }
        });
        node.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && !stage.isFullScreen()) toggleMaximized();
        });
    }

    private Button control(String text, String tooltip) {
        Button button = new Button(text);
        button.getStyleClass().add("window-control-button");
        button.setFocusTraversable(false);
        button.setTooltip(new javafx.scene.control.Tooltip(tooltip));
        return button;
    }

    private void toggleMaximized() {
        if (!stage.isFullScreen()) stage.setMaximized(!stage.isMaximized());
    }

    public void toggleFullScreen() {
        stage.setFullScreen(!stage.isFullScreen());
    }
}
