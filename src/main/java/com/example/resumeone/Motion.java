package com.example.resumeone;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.util.Duration;

import java.util.prefs.Preferences;

/** tokens/motion.css and the motion-* guidelines of the design system (4d7c4f7e), for JavaFX.
    Quick for small feedback the pointer caused, unhurried for whole views arriving, slow only
    for rebuilding an environment. The
    checkpoint - save, button and dropdown - is deliberately still. */
final class Motion {
    private Motion() {}

    static final Duration FAST = Duration.millis(140), BASE = Duration.millis(220),
            SLOW = Duration.millis(400), CAPTURE = Duration.millis(700);

    /** A whole view arriving (switching project, opening settings). BASE on STANDARD front-loads
        the travel into ~60ms and reads as a snap; this glides the full distance instead. */
    static final Duration SETTLE = Duration.millis(380);

    static final Interpolator STANDARD = Interpolator.SPLINE(.2, .8, .3, 1),
            OUT = Interpolator.SPLINE(.16, 1, .3, 1),
            IN = Interpolator.SPLINE(.5, 0, .9, .4),
            GLIDE = Interpolator.SPLINE(.25, .1, .2, 1);

    /** Settings > How it moves, on by default and deliberately independent of Windows' own
        Animation effects switch. Off, nothing travels: arrivals keep a short fade, and icon
        presses and the launch bloom stop. The checkpoint never moves. Stored with
        java.util.prefs, so projects.json stays projects only. */
    private static final Preferences PREFS = Preferences.userNodeForPackage(Motion.class);
    static boolean REDUCED = !PREFS.getBoolean("motion", true);

    static void setEnabled(boolean on) {
        REDUCED = !on;
        PREFS.putBoolean("motion", on);
    }

    /** Arrive from (dx, dy, scale) and transparent. Reduced motion keeps only the fade, shorter:
        the arrival still reads, nothing travels. Restarting mid-flight restarts cleanly. */
    static void enter(Node n, double dx, double dy, double scale, Duration d, Interpolator ease) {
        stop(n);
        n.setOpacity(0);
        Timeline t;
        if (REDUCED) {
            n.setTranslateX(0); n.setTranslateY(0); n.setScaleX(1); n.setScaleY(1);
            t = new Timeline(new KeyFrame(FAST, new KeyValue(n.opacityProperty(), 1, ease)));
        } else {
            n.setTranslateX(dx); n.setTranslateY(dy); n.setScaleX(scale); n.setScaleY(scale);
            t = new Timeline(new KeyFrame(d,
                    new KeyValue(n.opacityProperty(), 1, ease),
                    new KeyValue(n.translateXProperty(), 0, ease), new KeyValue(n.translateYProperty(), 0, ease),
                    new KeyValue(n.scaleXProperty(), 1, ease), new KeyValue(n.scaleYProperty(), 1, ease)));
        }
        run(n, t);
    }

    /** Only what someone can see moves: nothing animates before the window is up. */
    static boolean showing(Node n) {
        return n.getScene() != null && n.getScene().getWindow() != null && n.getScene().getWindow().isShowing();
    }

    private static final String KEY = "motion";

    static void run(Node n, Timeline t) {
        n.getProperties().put(KEY, t);
        t.play();
    }

    static void stop(Node n) {
        if (n.getProperties().remove(KEY) instanceof Timeline t) t.stop();
    }
}
