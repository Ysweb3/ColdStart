package com.example.resumeone;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.input.MouseEvent;
import javafx.util.Duration;

import java.util.prefs.Preferences;

/** tokens/motion.css and the 5a handoff's animations, for JavaFX. Quick for small feedback the
    pointer caused, unhurried for whole views arriving, slow only for rebuilding an environment.
    One easing curve, STANDARD; the overshoot SNAP is only ever the checkpoint confirmation. */
final class Motion {
    private Motion() {}

    static final Duration INSTANT = Duration.millis(80), FAST = Duration.millis(140), BASE = Duration.millis(220),
            SLOW = Duration.millis(400), CAPTURE = Duration.millis(700);

    /** A whole view arriving (switching project, opening settings). BASE on STANDARD front-loads
        the travel into ~60ms and reads as a snap; this glides the full distance instead. */
    static final Duration SETTLE = Duration.millis(380);

    static final Interpolator STANDARD = Interpolator.SPLINE(.2, .8, .3, 1),
            OUT = Interpolator.SPLINE(.16, 1, .3, 1),
            IN = Interpolator.SPLINE(.5, 0, .9, .4),
            GLIDE = Interpolator.SPLINE(.25, .1, .2, 1),
            SNAP = bezier(.34, 1.4, .5, 1),
            /** CSS ease-in-out: gentle start and gentle stop, for the empty-state entrance. */
            IN_OUT = Interpolator.SPLINE(.42, 0, .58, 1);

    /** cubic-bezier(x1, y1, x2, y2) as CSS reads it. SPLINE rejects a y outside 0..1, so the
        handoff's overshoot curve needs its own: solve x(t) by bisection, return y(t). */
    static Interpolator bezier(double x1, double y1, double x2, double y2) {
        return new Interpolator() {
            @Override protected double curve(double x) {
                double lo = 0, hi = 1, t = x;
                for (int i = 0; i < 40 && Math.abs(cubic(t, x1, x2) - x) > 1e-6; i++) {
                    if (cubic(t, x1, x2) < x) lo = t; else hi = t;
                    t = (lo + hi) / 2;
                }
                return cubic(t, y1, y2);
            }
        };
    }

    private static double cubic(double t, double p1, double p2) {
        double u = 1 - t;
        return 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t;
    }

    /** Settings > How it moves, on by default and deliberately independent of Windows' own
        Animation effects switch. Off, nothing travels: arrivals keep a short fade, presses no
        longer shrink and the checkpoint swaps without its ring and bar. Stored with java.util.prefs, so projects.json stays projects only. */
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

    /** Press is scale(.985) for 80ms, system-wide. Hover only ever changes colour. */
    static void pressable(Node n) {
        n.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> pressTo(n, .985));
        n.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> pressTo(n, 1));
    }

    private static void pressTo(Node n, double s) {
        if (REDUCED && s != 1) return;
        new Timeline(new KeyFrame(INSTANT,
                new KeyValue(n.scaleXProperty(), s, STANDARD), new KeyValue(n.scaleYProperty(), s, STANDARD))).play();
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
