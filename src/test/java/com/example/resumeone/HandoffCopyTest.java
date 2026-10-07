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
    void checkpointSubLineSaysWhenTheLastOneWasTaken() {
        assertEquals("2h ago",
                HelloController.checkpointLine(Instant.now().minusSeconds(2 * 3600 + 60).toString()));
        assertEquals("just now", HelloController.checkpointLine(Instant.now().toString()));
        assertEquals("no checkpoint yet", HelloController.checkpointLine(null));
    }

    @Test
    void aFailedLaunchSaysWhyInTheSystemsWords() {
        HelloController.LaunchStep step = new HelloController.LaunchStep("chrome.exe", "start chrome", false, () -> { });
        assertEquals("failed to launch", HelloController.failure(step));
        step.error = "Cannot run program \"C:\\x\\chrome.exe\": CreateProcess error=2, The system cannot find the file specified";
        assertEquals("The system cannot find the file specified", HelloController.failure(step));
        step.error = "access denied";
        assertEquals("access denied", HelloController.failure(step));
    }

    @Test
    void appsWearTheirOwnNamesNotTheirExes() {
        assertEquals("IntelliJ IDEA", HelloController.appName("idea64.exe"));
        assertEquals("Windows Terminal", HelloController.appName("WindowsTerminal.exe"));
        assertEquals("VS Code", HelloController.appName("Code.exe"));
        assertEquals("PyCharm", HelloController.appName("pycharm64.exe"));
        assertEquals("Obsidian", HelloController.appName("obsidian.exe"));
        assertEquals("Claude", HelloController.appName("claude.exe"));
        assertEquals("Brave", HelloController.appName("brave"));
    }

    @Test
    void categoriesReadInSentenceCaseAndKeepAcronyms() {
        assertEquals("Version control", HelloController.categoryLabel("VersionControl"));
        assertEquals("Communication", HelloController.categoryLabel("Communication"));
        assertEquals("IDE", HelloController.categoryLabel("IDE"));
        assertEquals("API", HelloController.categoryLabel("API"));
    }

    @Test
    void tabRowsKeepThePathSoSameSiteTabsDiffer() {
        assertEquals("github.com/atlas-api/pull/42",
                HelloController.tabLabel("https://www.github.com/atlas-api/pull/42?tab=files#diff"));
        assertEquals("javalin.io", HelloController.tabLabel("https://javalin.io/"));
    }

    @Test
    void resumeSaysWhatOnePressBringsBack() {
        assertEquals("10 APPS · 14 TABS", HelloController.resumeCountLine(10, 14));
        assertEquals("1 APP · 1 TAB", HelloController.resumeCountLine(1, 1));
    }

    @Test
    void titleBarSaysWhoIsConnectedOrThatOneIsComing() {
        assertEquals("Brave connected", HelloController.statusLine(List.of("brave"), false));
        assertEquals("2 browsers connected", HelloController.statusLine(List.of("brave", "Chrome"), true));
        assertEquals("connecting…", HelloController.statusLine(List.of(), true));
        assertEquals("no browser connected", HelloController.statusLine(List.of(), false));
    }

    @Test
    void tabGroupsAreHeadedByTheBrowsersName() {
        assertEquals("Brave", HelloController.browserLabel("brave"));
        assertEquals("Chrome", HelloController.browserLabel("Chrome"));
        assertEquals("Default browser", HelloController.browserLabel(HelloController.DEFAULT_BROWSER));
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
