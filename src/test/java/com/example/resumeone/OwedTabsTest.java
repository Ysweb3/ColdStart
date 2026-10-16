package com.example.resumeone;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OwedTabsTest {

    private final Project p = new Project("P", null, new HashMap<>(), new ArrayList<>());

    @Test
    void anExtensionWakingInTimeAnswersItsBucket() {
        var owed = Map.of("Chrome · me@x.com", new HelloController.Owed(p, HelloController.Checkpoint.OVERWRITE, 100));

        assertEquals("Chrome · me@x.com", HelloController.owedFor(owed, "Chrome · me@x.com", 50).getKey());
        // another profile of the same brand does not
        assertNull(HelloController.owedFor(owed, "Chrome · you@x.com", 50));
    }

    @Test
    void aBucketSavedBeforeProfilesIsAnsweredByAnyProfileOfItsBrand() {
        var owed = Map.of("Chrome", new HelloController.Owed(p, HelloController.Checkpoint.ADD_TABS, 100));

        assertNotNull(HelloController.owedFor(owed, "Chrome · me@x.com", 50));
    }

    @Test
    void tooLateAndTheCheckpointKeepsItsLastTabs() {
        // tabs sent this long after belong to a later moment, not to that checkpoint
        var owed = Map.of("Edge", new HelloController.Owed(p, HelloController.Checkpoint.OVERWRITE, 100));

        assertNull(HelloController.owedFor(owed, "Edge", 100));
    }

    @Test
    void onlyAnOpenBrowserOwesTabs() {
        assertFalse(HelloController.browserOpen("Edge", Set.of("chrome.exe")));
        assertTrue(HelloController.browserOpen("Edge", Set.of("msedge.exe")));
        // a brand the app cannot start has nothing to check against
        assertFalse(HelloController.browserOpen("Mosaic", Set.of("mosaic.exe")));
    }
}
