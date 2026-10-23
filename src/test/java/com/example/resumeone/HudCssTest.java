package com.example.resumeone;

import javafx.css.CssParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** JavaFX skips a bad CSS rule with a console warning at runtime; this fails the build instead. */
class HudCssTest {

    @Test
    void theParserReportsABrokenRule() {
        // proves the check below can fail at all
        CssParser.errorsProperty().clear();
        new CssParser().parse(".x { -fx-background-color: ; }  .y { color }");
        assertEquals(false, CssParser.errorsProperty().isEmpty());
    }

    @Test
    void hudCssParsesWithoutErrors() throws Exception {
        CssParser.errorsProperty().clear();
        new CssParser().parse(ColdstartController.class.getResource("hud.css"));
        assertEquals(java.util.List.of(), CssParser.errorsProperty().stream().map(Object::toString).toList());
    }
}
