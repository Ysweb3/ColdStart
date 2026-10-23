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
        var owed = Map.of("Chrome · me@x.com", new ColdstartController.Owed(p, ColdstartController.Checkpoint.OVERWRITE, 100));

        assertEquals("Chrome · me@x.com", ColdstartController.owedFor(owed, "Chrome · me@x.com", 50).getKey());
        // another profile of the same brand does not
        assertNull(ColdstartController.owedFor(owed, "Chrome · you@x.com", 50));
    }

    @Test
    void aBucketSavedBeforeProfilesIsAnsweredByAnyProfileOfItsBrand() {
        var owed = Map.of("Chrome", new ColdstartController.Owed(p, ColdstartController.Checkpoint.ADD_TABS, 100));

        assertNotNull(ColdstartController.owedFor(owed, "Chrome · me@x.com", 50));
    }

    @Test
    void tooLateAndTheCheckpointKeepsItsLastTabs() {
        // tabs sent this long after belong to a later moment, not to that checkpoint
        var owed = Map.of("Edge", new ColdstartController.Owed(p, ColdstartController.Checkpoint.OVERWRITE, 100));

        assertNull(ColdstartController.owedFor(owed, "Edge", 100));
    }

    @Test
    void onlyAnOpenBrowserOwesTabs() {
        assertFalse(ColdstartController.browserOpen("Edge", Set.of("chrome.exe")));
        assertTrue(ColdstartController.browserOpen("Edge", Set.of("msedge.exe")));
        // a brand the app cannot start has nothing to check against
        assertFalse(ColdstartController.browserOpen("Mosaic", Set.of("mosaic.exe")));
    }
}
