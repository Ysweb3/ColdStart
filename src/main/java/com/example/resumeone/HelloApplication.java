package com.example.resumeone;
import javafx.application.Application;
import javafx.scene.Cursor;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.scene.text.Font;
import javafx.scene.input.KeyCode;

import java.io.IOException;
import java.io.InputStream;

/**
 * JavaFX entry point. Owns only window-level concerns: bundled fonts, the undecorated stage,
 * and the drag/resize/min/max/close handling that an undecorated stage loses. Everything
 * inside the window is hud-view.fxml + HelloController.
 */
public class HelloApplication extends Application {

    /** How close to an edge the pointer has to be to start a resize drag. */
    private static final int EDGE = 6;

    @Override
    public void start(Stage stage) throws IOException {
        loadFonts();    

        // HUD v2 prototype. Swap back to "hello-view.fxml" (and 800x600) for the real app.
        FXMLLoader fxmlLoader = new FXMLLoader(HelloApplication.class.getResource("hud-view.fxml"));
        Parent root = fxmlLoader.load();
        Scene scene = new Scene(root, 1000, 640);
        Cuts.watch(root);
        stage.setTitle("Resume One");
        stage.initStyle(StageStyle.UNDECORATED);
        stage.setMinWidth(960);   // --app-min-w from the design system
        stage.setMinHeight(560);

        // No OS chrome, so the app's own title bar has to move the window and the
        // window buttons have to drive the stage themselves.
        Node titleBar = root.lookup("#titleBar");
        double[] grab = new double[2];
        titleBar.setOnMousePressed(e -> {
            grab[0] = e.getSceneX();
            grab[1] = e.getSceneY();
        });
        titleBar.setOnMouseDragged(e -> {
            if (stage.isMaximized()) return;
            stage.setX(e.getScreenX() - grab[0]);
            stage.setY(e.getScreenY() - grab[1]);
        });
        windowButton(root.lookup("#btnMin"), Icons.MINIMIZE, () -> stage.setIconified(true));
        StackPane max = windowButton(root.lookup("#btnMax"), Icons.MAXIMIZE, () -> stage.setMaximized(!stage.isMaximized()));
        windowButton(root.lookup("#btnClose"), Icons.CLOSE, stage::close);
        // maximize shows restore while maximized, as the slide pairs them
        stage.maximizedProperty().addListener((o, was, is) ->
                max.getChildren().setAll(Icons.node(is ? Icons.RESTORE : Icons.MAXIMIZE, 14, GLYPH)));

        // ponytail: right/bottom/corner only. Add the other five edges if anyone
        // actually misses them.
        scene.setOnMouseMoved(e -> {
            boolean right = e.getSceneX() >= scene.getWidth() - EDGE;
            boolean bottom = e.getSceneY() >= scene.getHeight() - EDGE;
            scene.setCursor(right && bottom ? Cursor.SE_RESIZE
                    : right ? Cursor.E_RESIZE
                    : bottom ? Cursor.S_RESIZE
                    : Cursor.DEFAULT);
        });
        scene.setOnMouseDragged(e -> {
            Cursor c = scene.getCursor();
            if (c == Cursor.E_RESIZE || c == Cursor.SE_RESIZE) {
                stage.setWidth(Math.max(stage.getMinWidth(), e.getScreenX() - stage.getX()));
            }
            if (c == Cursor.S_RESIZE || c == Cursor.SE_RESIZE) {
                stage.setHeight(Math.max(stage.getMinHeight(), e.getScreenY() - stage.getY()));
            }
        });

        stage.setScene(scene);
        stage.show();

    }

    /** IconButton ghost ink (--text-secondary); hud.css takes it to ink on hover, brick on close. */
    static final String GLYPH = "#A8A29E";

    /** The window buttons are 32px StackPanes (the bigger target) with the handoff's 14px glyph;
        this draws it and puts them in the Tab order and on Enter/Space. */
    private static StackPane windowButton(Node node, Icons.Glyph glyph, Runnable action) {
        StackPane box = (StackPane) node;
        box.getChildren().setAll(Icons.node(glyph, 14, GLYPH));
        Icons.animate(box, box);   // the box, not the glyph: maximize swaps its child
        Motion.pressable(box);
        node.setFocusTraversable(true);
        // named for Narrator, which otherwise reads three anonymous nodes in the title bar
        node.setAccessibleRole(javafx.scene.AccessibleRole.BUTTON);
        node.setAccessibleText(glyph == Icons.MINIMIZE ? "Minimize" : glyph == Icons.CLOSE ? "Close" : "Maximize");
        node.setOnMouseClicked(e -> action.run());
        node.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) {
                action.run();
                e.consume();
            }
        });
        return box;
    }

    /** JavaFX CSS silently ignores @font-face, so the bundled faces are registered here
        instead — without this every -fx-font-family falls back to the system font. */
    private static void loadFonts() throws IOException {
        for (String name : new String[]{"IBMPlexSans-Regular", "IBMPlexSans-SemiBold", "IBMPlexSans-Bold",
                                        "IBMPlexMono-Regular", "IBMPlexMono-Bold"}) {
            try (InputStream in = HelloApplication.class.getResourceAsStream("fonts/" + name + ".ttf")) {
                Font.loadFont(in, 12);
            }
        }
    }

}
