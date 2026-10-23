package com.example.resumeone;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the RESUME block promises and what 1l reports have to agree. */
class ResumeOutcomeTest {

    @Test
    void theResumeCountLeavesOutWhatResumeNeverLaunches() {
        HashMap<String, HashSet<String>> apps = new HashMap<>();
        apps.put("IDE", new HashSet<>(Set.of("Code.exe")));
        apps.put("AI", new HashSet<>(Set.of("claude.exe")));
        apps.put("Terminal", new HashSet<>(Set.of("WindowsTerminal.exe")));   // hosts claude: opens with it
        apps.put("Browser", new HashSet<>(Set.of("brave.exe", "msedge.exe")));
        apps.put("Other", new HashSet<>(Set.of("some-random-tool.exe")));    // shown, never relaunched
        Project p = new Project("atlas", null, apps, new ArrayList<>());
        p.urlsByBrowser.put("brave", new ArrayList<>(List.of("https://a.com/")));  // brave returns as tabs

        // every chip on the card: Brave returns as its tabs and the terminal with claude, but
        // both still open; only Other is left out
        assertEquals(5, ColdstartController.launchableApps(p));
        assertEquals(6, ColdstartController.countApps(p));
    }

    @Test
    void aStoppedOrFailedRunNeverSaysResumed() {
        ColdstartController.ResumeRun r = new ColdstartController.ResumeRun(new Project("atlas", null, new HashMap<>(), new ArrayList<>()));
        assertEquals("RESUMED", ColdstartController.outcome(r));
        r.stopped = true;
        assertEquals("STOPPED", ColdstartController.outcome(r));
        r.error = "powershell exited 1";
        assertEquals("NOT RESUMED", ColdstartController.outcome(r));
    }

    @Test
    void aBrowserWithEveryTabOpenIsAlreadyOpenNotLaunched() {
        ColdstartController.LaunchStep chrome = new ColdstartController.LaunchStep("chrome", "", true,
                () -> { throw new ColdstartController.AlreadyOpen(); });
        ColdstartController.LaunchStep brave = new ColdstartController.LaunchStep("brave", "", true, () -> { });
        ColdstartController.ResumeRun r = new ColdstartController.ResumeRun(new Project("atlas", null, new HashMap<>(), new ArrayList<>()));
        r.plan = new ColdstartController.ResumePlan(List.of(chrome, brave), List.of("idea64.exe"));
        chrome.run();
        brave.run();

        assertEquals(ColdstartController.LaunchStep.State.SKIPPED, chrome.state);
        assertEquals(ColdstartController.LaunchStep.State.DONE, brave.state);
        assertEquals(2, r.done());   // both finished, so the run still reads 2 / 2
        assertEquals(List.of("idea64.exe", "chrome"), r.alreadyOpen());
        assertEquals("RESUMED", ColdstartController.outcome(r));
    }

    @Test
    void tabsCountWhatTheExtensionOpenedNotEverySavedUrl() {
        Project p = new Project("atlas", null, new HashMap<>(), new ArrayList<>());
        p.urlsByBrowser.put("Edge", new ArrayList<>(List.of("https://a/", "https://b/", "https://c/", "https://d/")));
        p.urlsByBrowser.put("Chrome", new ArrayList<>(List.of("https://e/", "https://f/")));
        ColdstartController.LaunchStep edge = new ColdstartController.LaunchStep("Edge", "", true, () -> { });
        ColdstartController.LaunchStep chrome = new ColdstartController.LaunchStep("Chrome", "", true, () -> { });
        ColdstartController.ResumeRun r = new ColdstartController.ResumeRun(p);
        r.plan = new ColdstartController.ResumePlan(List.of(edge, chrome), List.of());
        edge.run();
        chrome.run();
        edge.opened = 1;   // its extension: three of the four were up already
        // chrome gave no count (command line), so both of its urls count as opened

        assertEquals(3, ColdstartController.tabsOpened(r));
        assertEquals(3, ColdstartController.tabsAlreadyOpen(r));
        assertEquals("1 app", ColdstartController.count(1, "app"));
        assertEquals("0 tabs", ColdstartController.count(0, "tab"));
    }

    @Test
    void pathLookupFindsAnExeAndSkipsBadEntries(@TempDir Path dir) throws IOException {
        Files.createFile(dir.resolve("tool.exe"));
        String path = "\"C:\\bad<entry\";" + ";" + dir;
        assertTrue(ColdstartController.onPath("tool.exe", path));
        assertFalse(ColdstartController.onPath("missing.exe", path));
        assertFalse(ColdstartController.onPath("tool.exe", null));
    }
}
