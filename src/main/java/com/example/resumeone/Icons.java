package com.example.resumeone;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.value.WritableValue;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import javafx.util.Duration;

import java.util.Map;

/** The 28 glyphs of "Iconography · spec 02" (claude.ai/design b78487db, Icon System slide),
    24-unit box, stroke 1.75. Each glyph is a list of parts so a part can move on its own:
    the trash lid, the slider knobs, the folder flap. Anim names are the slide's keyframes. */
final class Icons {
    private Icons() {}

    /** fill: null = transparent, "cur" = same as stroke, else a web colour (the slider knobs
        mask the rail they sit on). */
    record Part(String d, String anim, String fill) {
        Part(String d) { this(d, null, null); }
        Part(String d, String anim) { this(d, anim, null); }
    }
    record Glyph(Part... parts) {}

    // ── capture categories (static) ──────────────────────────────────────────
    static final Glyph
        IDE = g("M9.4 4 L2 12 L9.4 20 M14.6 4 L22 12 L14.6 20"),
        AI = g("M10 3 C10.7 7.4 12.6 9.3 17 10 C12.6 10.7 10.7 12.6 10 17 C9.3 12.6 7.4 10.7 3 10 C7.4 9.3 9.3 7.4 10 3 Z "
             + "M18.4 14.8 C18.7 16.8 19.1 17.2 21.1 17.5 C19.1 17.8 18.7 18.2 18.4 20.2 C18.1 18.2 17.7 17.8 15.7 17.5 C17.7 17.2 18.1 16.8 18.4 14.8 Z"),
        TERMINAL = g(rr(2, 4, 20, 16, 2.6) + " M6.5 9.8 L9.8 13 L6.5 16.2 M12.6 16.2 H17.6"),
        BROWSER = g(circ(12, 12, 10.4) + " M12 1.6 A4.5 10.4 0 1 0 12 22.4 A4.5 10.4 0 1 0 12 1.6 Z M1.6 12 H22.4"),
        VERSION = g(circ(6.4, 17.6, 3.4) + circ(17.6, 6.4, 3.4) + " M8.8 15.2 L15.2 8.8"),
        DATABASE = new Glyph(new Part(rr(3, 3, 18, 5, 1.6) + rr(3, 9.5, 18, 5, 1.6) + rr(3, 16, 18, 5, 1.6)),
                dot(6.4, 5.5, .95), dot(6.4, 12, .95), dot(6.4, 18.5, .95)),
        API = g(circ(12, 12, 10.4) + circ(12, 12, 3.2) + " M4.2 12 H8.8 M15.2 12 H19.8"),
        CONTAINERS = g(rr(2, 4, 20, 16, 2.6) + " M8.7 4 V20 M15.3 4 V20"),
        DESIGN = new Glyph(new Part("M14.6 20.8 A9 9 0 1 1 20.4 15.2 C19.6 13.2 17.4 13 16 14.2 C14.4 15.6 15.4 18 14.6 20.8 Z"),
                dot(8.2, 8.4, 1.05), dot(12.5, 6.7, 1.05), dot(6.9, 13, 1.05)),
        COMMS = g("M3 16.4 V5.4 A2.4 2.4 0 0 1 5.4 3 H18.6 A2.4 2.4 0 0 1 21 5.4 V14 A2.4 2.4 0 0 1 18.6 16.4 H9.4 L5.2 21 V16.4 Z"),
        NOTES = g(rr(4, 2, 16, 20, 2.6) + " M8 8.6 H16 M8 13.2 H16"),
        MUSIC = g(circ(7.4, 18.6, 3.4) + " M10.8 18.6 V2 M10.8 2 L20 4.8 V9.2 L10.8 6.4 Z"),
        OTHER = g("M2 18.6 V5.4 A1.4 1.4 0 0 1 3.4 4 H8.8 L11.4 7 H20.6 A1.4 1.4 0 0 1 22 8.4 V18.6 A1.4 1.4 0 0 1 20.6 20 H3.4 A1.4 1.4 0 0 1 2 18.6 Z");

    // ── actions (hover / press animated) ─────────────────────────────────────
    static final Glyph
        RESUME = new Glyph(new Part("M4.6 3 L21 12 L4.6 21 Z", "nudge")),
        CHECKPOINT = new Glyph(new Part("M4.6 2 H19.4 V22 L12 15.4 L4.6 22 Z", "drop")),
        DELETE = new Glyph(new Part(rr(6, 6, 12, 16, 2.4)), new Part("M4 6 H20 M9.6 2.6 H14.4", "lid")),
        NEW = new Glyph(new Part("M12 3 V21 M3 12 H21", "quarter")),
        EDIT = new Glyph(new Part("M3 21 L7.9 19.7 L19.8 7.8 A2.5 2.5 0 0 0 16.2 4.2 L4.3 16.1 Z M15.3 5.1 L18.9 8.7", "lift")),
        BROWSE = new Glyph(new Part("M2 18.4 V5.4 A1.4 1.4 0 0 1 3.4 4 H8.6 L11.2 7 H18.6 A1.4 1.4 0 0 1 20 8.4 V10.6"),
                new Part("M2 19.8 L5.2 11.6 H22 L18.8 19.8 Z", "lift")),
        RECAPTURE = new Glyph(new Part("M18.7 20 A10.4 10.4 0 1 1 21 6.8 M16.6 6.1 L21 6.8 L22 2.4", "spin")),
        SETTINGS = new Glyph(new Part("M2 7 H22 M2 17 H22"),
                new Part(circ(8.6, 7, 2.8), "slideA", "#111315"), new Part(circ(15.4, 17, 2.8), "slideB", "#111315")),
        FAILED = new Glyph(new Part(circ(12, 12, 10.4)), new Part("M12 6.4 V13 M12 17 V17.1", "shake")),
        TIMESTAMP = new Glyph(new Part(circ(12, 12, 10.4)), new Part("M12 5.8 V12 L16.6 14.6", "sweep")),
        CHECKBOX = new Glyph(new Part(rr(3, 3, 18, 18, 3.2)), new Part("M7.4 12.2 L10.8 15.6 L16.8 8.6", "draw")),
        MINIMIZE = new Glyph(new Part("M2 12 H22", "drop")),
        MAXIMIZE = new Glyph(new Part(rr(3, 3, 18, 18, 3), "pop")),
        RESTORE = new Glyph(new Part("M7.6 7.6 V5.4 A2 2 0 0 1 9.6 3.4 H19 A2 2 0 0 1 21 5.4 V14.4 A2 2 0 0 1 19 16.4 H16.4"),
                new Part(rr(3, 7.6, 13.4, 13.4, 2.6), "lift")),
        CLOSE = new Glyph(new Part("M4.4 4.4 L19.6 19.6 M19.6 4.4 L4.4 19.6", "quarter"));

    // not on the slide: lucide check / chevron-right / app-window, for the fold rows and buttons
    static final Glyph
        CHECK = g("M20 6 L9 17 L4 12"),
        CHEVRON = g("M9 18 L15 12 L9 6"),
        APP_WINDOW = g("M2 6 A2 2 0 0 1 4 4 H20 A2 2 0 0 1 22 6 V18 A2 2 0 0 1 20 20 H4 A2 2 0 0 1 2 18 Z M10 4 V8 M2 8 H22 M6 4 V8");

    /** Project.apps keys → slide glyph; anything unlisted is "Other". */
    static final Map<String, Glyph> CATEGORY = Map.ofEntries(
            Map.entry("IDE", IDE), Map.entry("AI", AI), Map.entry("Terminal", TERMINAL),
            Map.entry("Browser", BROWSER), Map.entry("VersionControl", VERSION), Map.entry("Database", DATABASE),
            Map.entry("API", API), Map.entry("Containers", CONTAINERS), Map.entry("Design", DESIGN),
            Map.entry("Communication", COMMS), Map.entry("Notes", NOTES), Map.entry("Music", MUSIC));

    /** A size×size node. Parts carry the style class "ico" so CSS hover rules can recolour
        them (author CSS beats the colour set here, as it does everywhere in JavaFX). */
    static Pane node(Glyph glyph, double size, String color) {
        Group g = new Group();
        for (Part part : glyph.parts()) {
            SVGPath p = new SVGPath();
            p.setContent(part.d());
            p.setStroke(Color.web(color));
            p.setStrokeWidth(1.75);
            p.setStrokeLineCap(StrokeLineCap.ROUND);
            p.setStrokeLineJoin(StrokeLineJoin.ROUND);
            p.setFill(part.fill() == null ? Color.TRANSPARENT
                    : "cur".equals(part.fill()) ? Color.web(color) : Color.web(part.fill()));
            if (part.fill() != null && "cur".equals(part.fill())) p.setStroke(null);
            if ("draw".equals(part.anim())) p.getStrokeDashArray().add(15.0);
            p.getStyleClass().add("ico");
            if (part.anim() != null) p.getProperties().put("anim", part.anim());
            g.getChildren().add(p);
        }
        // Scale about the keyline box's own origin, inside a Pane that leaves children at 0,0:
        // the 24 box lands exactly on the control box. Centring on the glyph's ink bounds instead
        // would shift every asymmetric mark (the AI sparkle, the music note) off its neighbours.
        g.getTransforms().add(new Scale(size / 24, size / 24));
        Pane box = new Pane(g);
        box.setMinSize(size, size);
        box.setPrefSize(size, size);
        box.setMaxSize(size, size);
        return box;
    }

    /** Gives a hand-drawn SVGPath (the FXML verbs) one of the slide's animations. */
    static void tag(SVGPath p, String anim) {
        p.getStyleClass().add("ico");
        p.getProperties().put("anim", anim);
    }

    /** Plays the glyph's animations once. */
    static void play(Node iconNode) {
        if (Motion.REDUCED) return;   // every one of these is travel; the press colour still answers
        for (Node n : iconNode.lookupAll(".ico")) {
            String anim = (String) n.getProperties().get("anim");
            if (anim == null) continue;
            Timeline t = (Timeline) n.getProperties().computeIfAbsent("tl", k -> timeline((SVGPath) n, anim));
            t.stop();
            t.playFromStart();
        }
    }

    /** Press plays (the slide also plays on hover; dropped as too busy). Adds a handler, clobbers none. */
    static void animate(Node trigger, Node iconNode) {
        trigger.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> play(iconNode));
    }

    // shake keeps the slide's --ease-in out / --ease-out back: an error is allowed to jolt
    private static final Interpolator IN = Motion.IN, OUT = Motion.OUT;
    // everything else leaves briskly but coasts to a stop at the far point, then eases home;
    // --ease-in arrived there at full speed and reversed, which read as a snap
    private static final Interpolator GO = Interpolator.SPLINE(.2, .6, .35, 1),
            BACK = Interpolator.SPLINE(.45, 0, .25, 1),
            TURNING = Interpolator.SPLINE(.4, 0, .2, 1);

    /** Between --dur-base (220ms, which whipped) and the slide's 700ms (sluggish on a button).
        A full turn gets longer still so the rotation stays legible rather than a blur. */
    private static final int BASE = 360, TURN = 640;

    // ponytail: every mid key sits at 45% (slide uses 42–55%); the eye can't tell at 700ms
    private static Timeline timeline(SVGPath p, String anim) {
        return switch (anim) {
            case "nudge" -> out(BASE, p.translateXProperty(), 0, 2.2, 0);
            case "drop" -> out(BASE, p.translateYProperty(), -4, .8, 0);
            case "lift" -> both(BASE, p.translateXProperty(), p.translateYProperty(), 1, -2.2);
            case "slideA" -> out(BASE, p.translateXProperty(), 0, 5.4, 0);
            case "slideB" -> out(BASE, p.translateXProperty(), 0, -5.4, 0);
            case "pop" -> both(BASE, p.scaleXProperty(), p.scaleYProperty(), .84, .84);
            case "quarter" -> out(BASE, p.rotateProperty(), 0, 90, 0);
            case "spin", "sweep" -> turn(pivot(p, 12, 12).angleProperty());
            case "lid" -> {
                Rotate r = pivot(p, p.getLayoutBounds().getMinX() + .875, p.getLayoutBounds().getCenterY());
                yield new Timeline(
                        new KeyFrame(Duration.millis(BASE * .45), new KeyValue(r.angleProperty(), -15, GO), new KeyValue(p.translateYProperty(), -.7, GO)),
                        new KeyFrame(Duration.millis(BASE), new KeyValue(r.angleProperty(), 0, BACK), new KeyValue(p.translateYProperty(), 0, BACK)));
            }
            case "shake" -> new Timeline(
                    new KeyFrame(Duration.millis(BASE * .22), new KeyValue(p.translateXProperty(), -1.5, IN)),
                    new KeyFrame(Duration.millis(BASE * .52), new KeyValue(p.translateXProperty(), 1.5, OUT)),
                    new KeyFrame(Duration.millis(BASE * .78), new KeyValue(p.translateXProperty(), -.8, OUT)),
                    new KeyFrame(Duration.millis(BASE), new KeyValue(p.translateXProperty(), 0, OUT)));
            case "draw" -> new Timeline(
                    new KeyFrame(Duration.ZERO, new KeyValue(p.strokeDashOffsetProperty(), 15)),
                    new KeyFrame(Duration.millis(BASE), new KeyValue(p.strokeDashOffsetProperty(), 0, OUT)));
            default -> throw new IllegalArgumentException(anim);
        };
    }

    /** a → b (GO) → c (BACK), the slide's three-key shape. */
    private static Timeline out(int ms, WritableValue<Number> prop, double a, double b, double c) {
        return new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(prop, a)),
                new KeyFrame(Duration.millis(ms * .45), new KeyValue(prop, b, GO)),
                new KeyFrame(Duration.millis(ms), new KeyValue(prop, c, BACK)));
    }

    /** One unbroken turn: a mid key made the speed spike through it. */
    private static Timeline turn(DoubleProperty angle) {
        return new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(angle, 0)),
                new KeyFrame(Duration.millis(TURN), new KeyValue(angle, 360, TURNING)));
    }

    /** Two properties leaving their rest value together and coming back. */
    private static Timeline both(int ms, DoubleProperty x, DoubleProperty y, double bx, double by) {
        double rx = x.get(), ry = y.get();
        return new Timeline(
                new KeyFrame(Duration.millis(ms * .45), new KeyValue(x, bx, GO), new KeyValue(y, by, GO)),
                new KeyFrame(Duration.millis(ms), new KeyValue(x, rx, BACK), new KeyValue(y, ry, BACK)));
    }

    /** Node.rotate pivots on the part's own centre; the spin/sweep/lid pivot elsewhere. */
    private static Rotate pivot(SVGPath p, double x, double y) {
        Rotate r = new Rotate(0, x, y);
        p.getTransforms().add(r);
        return r;
    }

    private static Glyph g(String d) { return new Glyph(new Part(d)); }
    private static Part dot(double cx, double cy, double r) { return new Part(circ(cx, cy, r), null, "cur"); }

    static String circ(double cx, double cy, double r) {
        return f(" M% %  A% % 0 1 0 % %  A% % 0 1 0 % % Z", cx - r, cy, r, r, cx + r, cy, r, r, cx - r, cy);
    }

    static String rr(double x, double y, double w, double h, double r) {
        return f(" M% % H% A% % 0 0 1 % % V% A% % 0 0 1 % % H% A% % 0 0 1 % % V% A% % 0 0 1 % % Z",
                x + r, y, x + w - r, r, r, x + w, y + r, y + h - r, r, r, x + w - r, y + h,
                x + r, r, r, x, y + h - r, y + r, r, r, x + r, y);
    }

    /** '%' → the next number, rounded to 0.001 so 12 - 10.4 prints as 1.6, not 1.5999999999999996. */
    private static String f(String pattern, double... v) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (char c : pattern.toCharArray()) {
            if (c != '%') { sb.append(c); continue; }
            double n = Math.round(v[i++] * 1000) / 1000.0;
            sb.append(n == (long) n ? String.valueOf((long) n) : String.valueOf(n));
        }
        return sb.toString().replace("  ", " ");
    }
}
