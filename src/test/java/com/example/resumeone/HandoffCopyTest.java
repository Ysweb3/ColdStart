package com.example.resumeone;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The 5a handoff's words and shapes that the HUD derives rather than hard-codes. */
class HandoffCopyTest {

    @Test
    void checkpointSubLineReadsAsTheHandoffWordsIt() {
        assertEquals("overwrite \u00b7 2h old",
                HelloController.checkpointLine(Instant.now().minusSeconds(2 * 3600 + 60).toString()));
        // shortened: the 296px block's column truncates "overwrite \u00b7 just now"
        assertEquals("overwrite \u00b7 now", HelloController.checkpointLine(Instant.now().toString()));
        assertEquals("no checkpoint yet", HelloController.checkpointLine(null));
    }

    @Test
    void resumeSaysWhatOnePressRebuilds() {
        assertEquals("10 APPS · 14 TABS · 1 FOLDER", HelloController.resumeLine(10, 14, true));
        assertEquals("1 APP · 1 TAB · NO FOLDER", HelloController.resumeLine(1, 1, false));
    }

    @Test
    void tabGroupsAreHeadedByTheBrowsersName() {
        assertEquals("Brave", HelloController.browserLabel("brave"));
        assertEquals("Chrome", HelloController.browserLabel("Chrome"));
    }

    @Test
    void ideRowNamesTheFolderVsCodeOpens() {
        HashMap<String, HashSet<String>> apps = new HashMap<>();
        apps.put("IDE", new HashSet<>(Set.of("Code.exe")));
        Project p = new Project("atlas-api", "C:\\dev\\atlas-api", apps, new ArrayList<>());
        assertEquals("\u2026\\atlas-api", HelloController.trailingNote("IDE", p));
        p.folderPath = null;
        assertNull(HelloController.trailingNote("IDE", p));
    }

    @Test
    void notchCutsTheTopRightAndBottomLeftCorners() {
        assertEquals(List.of(0.0, 0.0, 282.0, 0.0, 296.0, 14.0, 296.0, 104.0, 14.0, 104.0, 0.0, 90.0),
                HelloController.notchPoints(296, 104, 14));
    }

    @Test
    void snapOvershootsAndLands() {
        // cubic-bezier(.34,1.4,.5,1) passes 1 on the way and settles exactly on it
        double peak = 0;
        for (int i = 0; i <= 100; i++) peak = Math.max(peak, Motion.SNAP.interpolate(0.0, 1.0, i / 100.0));
        assertTrue(peak > 1.05, "no overshoot: " + peak);
        assertEquals(1.0, Motion.SNAP.interpolate(0.0, 1.0, 1.0), 1e-6);
        assertEquals(0.0, Motion.SNAP.interpolate(0.0, 1.0, 0.0), 1e-6);
    }
}
