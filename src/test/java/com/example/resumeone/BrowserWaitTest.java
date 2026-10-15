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

        var plan = new HelloController().planResume(p, Set.of(), new ArrayList<>());

        assertEquals(List.of("Notepad", "Mosaic"), plan.steps().stream().map(s -> s.label).toList());
    }

    @Test
    void aBrowserThatIsNotRunningHasNoOpenProfile() {
        assertFalse(HelloController.profileOpen("Chrome · me@x.com", "chrome.exe", Set.of("Code.exe")));
    }

    @Test
    void withNoProfileToCheckARunningBrowserCountsAsOpen() {
        // starting it again is what duplicates its tabs, so when in doubt it is left alone
        assertTrue(HelloController.profileOpen("Mosaic", "mosaic.exe", Set.of("MOSAIC.EXE")));
    }

    @Test
    void activeProfilesComeFromLocalState(@TempDir Path dir) throws Exception {
        File state = dir.resolve("Local State").toFile();
        Files.writeString(state.toPath(),
                "{\"profile\":{\"last_active_profiles\":[\"Profile 1\"],\"info_cache\":{\"Default\":{},\"Profile 1\":{}}}}");

        assertEquals(List.of("Profile 1"), HelloController.activeProfiles(state));
        assertEquals(List.of(), HelloController.activeProfiles(dir.resolve("missing").toFile()));
    }
}
