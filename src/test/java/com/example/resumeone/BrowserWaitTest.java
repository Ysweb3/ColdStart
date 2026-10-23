package com.example.resumeone;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BrowserWaitTest {

    @Test
    void browserTabsComeAfterEveryOtherApp() {
        HashMap<String, HashSet<String>> apps = new HashMap<>();
        apps.put("Notes", new HashSet<>(Set.of("Notepad.exe")));
        Project p = new Project("P", null, apps, new ArrayList<>());
        // a brand with no known exe, so the plan does not depend on what this machine has installed
        p.urlsByBrowser.put("Mosaic", new ArrayList<>(List.of("https://example.com")));

        var plan = new ColdstartController().planResume(p, Set.of(), new ArrayList<>());

        assertEquals(List.of("Notepad", "Mosaic"), plan.steps().stream().map(s -> s.label).toList());
    }

    @Test
    void tabsQueuedForASleepingExtensionAreWaitingNotSent() {
        Project p = new Project("P", null, new HashMap<>(), new ArrayList<>());
        p.urlsByBrowser.put("Chrome", new ArrayList<>(List.of("https://a.com", "https://b.com")));
        ResumePlanner.LaunchStep chrome = new ResumePlanner.LaunchStep("Chrome", "", true,
                () -> { throw new ResumePlanner.Queued(); });
        ResumePlanner.ResumeRun r = new ResumePlanner.ResumeRun(p);
        r.plan = new ResumePlanner.ResumePlan(List.of(chrome), List.of());

        chrome.run();

        assertEquals(ResumePlanner.LaunchStep.State.DONE, chrome.state, "counts as done, so the run is clean");
        assertTrue(chrome.waiting);
        assertEquals(2, ColdstartController.tabsWaiting(r));
        assertTrue(chrome.logLine().contains("wait for its extension"));
    }

    @Test
    void aBrowserThatIsNotRunningHasNoOpenProfile() {
        assertFalse(Browsers.profileOpen("Chrome · me@x.com", "chrome.exe", Set.of("Code.exe")));
    }

    @Test
    void withNoProfileToCheckARunningBrowserCountsAsOpen() {
        // starting it again is what duplicates its tabs, so when in doubt it is left alone
        assertTrue(Browsers.profileOpen("Mosaic", "mosaic.exe", Set.of("MOSAIC.EXE")));
    }

    @Test
    void activeProfilesComeFromLocalState(@TempDir Path dir) throws Exception {
        File state = dir.resolve("Local State").toFile();
        Files.writeString(state.toPath(),
                "{\"profile\":{\"last_active_profiles\":[\"Profile 1\"],\"info_cache\":{\"Default\":{},\"Profile 1\":{}}}}");

        assertEquals(List.of("Profile 1"), Browsers.activeProfiles(state));
        assertEquals(List.of(), Browsers.activeProfiles(dir.resolve("missing").toFile()));
    }
}
