package com.example.resumeone;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every glyph is well-formed path data inside the 24 box, and every part's animation is one
    Icons knows how to play. SVGPath only reports bad content at render time, so this is where
    a typo in a 28-glyph table would otherwise surface: in the running app, as a blank icon. */
class IconsTest {

    private static final Set<String> ANIMS = Set.of("nudge", "drop", "lift", "slideA", "slideB", "pop",
            "quarter", "spin", "sweep", "lid", "shake", "draw");

    private static List<Icons.Glyph> glyphs() throws Exception {
        List<Icons.Glyph> out = new ArrayList<>();
        for (Field f : Icons.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == Icons.Glyph.class) out.add((Icons.Glyph) f.get(null));
        }
        return out;
    }

    @Test
    void everyGlyphParsesAndStaysInTheBox() throws Exception {
        List<Icons.Glyph> all = glyphs();
        // 13 categories + 15 actions + check / chevron / app-window + bookmark-plus
        assertEquals(32, all.size());
        for (Icons.Glyph g : all) {
            for (Icons.Part p : g.parts()) {
                String d = p.d().trim();
                assertTrue(d.matches("[MLHVACZ][\\d .-]*([MLHVACZ][\\d .-]*)*"), "bad path: " + d);
                assertTrue(d.startsWith("M"), "must start with a move: " + d);
                for (String n : d.replaceAll("[MLHVACZ]", " ").trim().split("\\s+")) {
                    double v = Double.parseDouble(n);
                    assertTrue(v >= 0 && v <= 24, v + " leaves the 24 box in " + d);
                }
                if (p.anim() != null) assertTrue(ANIMS.contains(p.anim()), "unknown anim " + p.anim());
            }
        }
        assertEquals(12, Icons.CATEGORY.size());
    }

    @Test
    void primitivesCloseOnThemselves() {
        // a circle is two half arcs that meet where they started
        assertEquals("M1.6 12 A10.4 10.4 0 1 0 22.4 12 A10.4 10.4 0 1 0 1.6 12 Z", Icons.circ(12, 12, 10.4).trim());
        // a rounded rect: four corners, the last arc lands back on the first point
        assertEquals("M5.6 3 H19.4 A2.6 2.6 0 0 1 22 5.6 V17.4 A2.6 2.6 0 0 1 19.4 20 H5.6 "
                + "A2.6 2.6 0 0 1 3 17.4 V5.6 A2.6 2.6 0 0 1 5.6 3 Z", Icons.rr(3, 3, 19, 17, 2.6).trim());
    }
}
