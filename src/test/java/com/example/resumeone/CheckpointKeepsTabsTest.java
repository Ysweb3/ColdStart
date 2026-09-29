package com.example.resumeone;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A checkpoint must never wipe tabs that no connected extension can put back. */
@Tag("priority-high")
class CheckpointKeepsTabsTest {

    private static HashMap<String, ArrayList<String>> saved() {
        HashMap<String, ArrayList<String>> m = new HashMap<>();
        m.put("Chrome", new ArrayList<>(List.of("https://a", "https://b")));
        m.put("Edge", new ArrayList<>(List.of("https://c")));
        m.put("brave", new ArrayList<>());
        return m;
    }

    @Test
    void nothingConnectedKeepsEverything() {
        var urls = saved();
        List<String> kept = HelloController.unconnectedWithTabs(urls, Set.of());
        assertEquals(3, urls.size());
        assertTrue(kept.contains("Chrome") && kept.contains("Edge"));
        assertFalse(kept.contains("brave"), "an empty bucket is nothing to keep");
    }

    @Test
    void nothingIsDroppedEvenForAConnectedBrowser() {
        // a connected browser overwrites its own bucket in POST /tabs; if it never answers
        // (asleep worker, dead socket) the old tabs must still be on disk
        var urls = saved();
        List<String> kept = HelloController.unconnectedWithTabs(urls, Set.of("Chrome"));
        assertEquals(List.of("https://a", "https://b"), urls.get("Chrome"));
        assertEquals(List.of("https://c"), urls.get("Edge"));
        assertEquals(List.of("Edge"), kept);
    }

    @Test
    void allConnectedMeansNothingKept() {
        var urls = saved();
        List<String> kept = HelloController.unconnectedWithTabs(urls, Set.of("Chrome", "Edge", "brave"));
        assertEquals(3, urls.size());
        assertTrue(kept.isEmpty());
    }
}
