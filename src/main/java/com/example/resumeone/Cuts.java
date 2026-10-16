package com.example.resumeone;

import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.Region;
import javafx.scene.shape.Polygon;

import java.util.Set;

/**
 * The notch means "you can act on this". RESUME and Checkpoint wear it large (14px); every
 * other control wears it small: top-right and bottom-left cut at 45°. That includes app chips
 * (they carry a remove x) and "Add a note"; the CTRL+R keycap only labels the shortcut, so it
 * stays square with containers, selection rows and window chrome.
 * CSS can't say it (-fx-shape stretches with the box, so the cut would grow with it), so each
 * matching region gets a shape cut to its own size on every resize. A shape, not a clip, so
 * the background layers and the border both follow the diagonal.
 */
final class Cuts {
    static final double CUT = 6;
    /** "cut" opts a control in by hand; "no-cut" opts one of these out. */
    private static final Set<String> CLASSES = Set.of("button", "iconbtn", "text-field", "cut");

    private Cuts() {}

    /** Cuts n and everything under it, now and whenever a child is added later. */
    static void watch(Node n) {
        if (n instanceof Region r && r.getStyleClass().stream().anyMatch(CLASSES::contains)
                && !r.getStyleClass().contains("no-cut")
                && r.getProperties().putIfAbsent("cuts.shape", true) == null) {
            r.layoutBoundsProperty().addListener((o, was, b) -> cut(r));
            cut(r);
        }
        if (n instanceof Parent p && p.getProperties().putIfAbsent("cuts.watch", true) == null) {
            p.getChildrenUnmodifiable().forEach(Cuts::watch);
            p.getChildrenUnmodifiable().addListener((ListChangeListener<Node>) c -> {
                while (c.next()) c.getAddedSubList().forEach(Cuts::watch);
            });
        }
    }

    private static void cut(Region r) {
        double w = r.getWidth(), h = r.getHeight();
        if (w <= 0 || h <= 0) return;
        Polygon shape = new Polygon();
        shape.getPoints().setAll(HelloController.notchPoints(w, h, CUT));
        r.setShape(shape);
    }
}
