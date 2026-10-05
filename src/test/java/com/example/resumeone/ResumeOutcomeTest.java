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
        assertEquals(5, HelloController.launchableApps(p));
        assertEquals(6, HelloController.countApps(p));
    }

    @Test
    void aStoppedOrFailedRunNeverSaysResumed() {
        HelloController.ResumeRun r = new HelloController.ResumeRun(new Project("atlas", null, new HashMap<>(), new ArrayList<>()));
        assertEquals("RESUMED", HelloController.outcome(r));
        r.stopped = true;
        assertEquals("STOPPED", HelloController.outcome(r));
        r.error = "powershell exited 1";
        assertEquals("NOT RESUMED", HelloController.outcome(r));
    }

    @Test
    void pathLookupFindsAnExeAndSkipsBadEntries(@TempDir Path dir) throws IOException {
        Files.createFile(dir.resolve("tool.exe"));
        String path = "\"C:\\bad<entry\";" + ";" + dir;
        assertTrue(HelloController.onPath("tool.exe", path));
        assertFalse(HelloController.onPath("missing.exe", path));
        assertFalse(HelloController.onPath("tool.exe", null));
    }
}
