package com.example.resumeone;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@Tag("priority-high")
class CheckpointMergeTest {

    @Test
    void addModeKeepsEverySavedAppAndAddsTheNewOnes() {
        Map<String, HashSet<String>> saved = new HashMap<>();
        saved.put("IDE", new HashSet<>(Set.of("Code.exe")));
        saved.put("Notes", new HashSet<>(Set.of("Notepad.exe")));   // closed today: must survive

        Map<String, HashSet<String>> now = new HashMap<>();
        now.put("IDE", new HashSet<>(Set.of("Code.exe", "idea64.exe")));
        now.put("Music", new HashSet<>(Set.of("Spotify.exe")));      // new category

        ColdstartController.mergeApps(saved, now);

        assertEquals(Set.of("Code.exe", "idea64.exe"), saved.get("IDE"));
        assertEquals(Set.of("Notepad.exe"), saved.get("Notes"));
        assertEquals(Set.of("Spotify.exe"), saved.get("Music"));
    }

    @Test
    void addModeAppendsOnlyUnseenUrlsAfterTheSavedOnes() {
        List<String> saved = List.of("https://a.com/", "https://b.com/");
        List<String> incoming = List.of("https://b.com/", "https://c.com/", "https://a.com/");

        assertEquals(List.of("https://a.com/", "https://b.com/", "https://c.com/"),
                ColdstartController.mergeUrls(saved, incoming));
    }

    @Test
    void removingTheLastAppInACategoryDropsTheCategory() {
        Project p = new Project("x", null, new HashMap<>(), new ArrayList<>());
        p.apps.put("IDE", new HashSet<>(Set.of("Code.exe", "idea64.exe")));
        p.apps.put("Notes", new HashSet<>(Set.of("Notepad.exe")));

        ColdstartController.removeApp(p, "IDE", "Code.exe");
        ColdstartController.removeApp(p, "Notes", "Notepad.exe");
        ColdstartController.removeApp(p, "Music", "Spotify.exe");   // absent: no-op, no exception

        assertEquals(Set.of("idea64.exe"), p.apps.get("IDE"));
        assertFalse(p.apps.containsKey("Notes"));
    }

    @Test
    void removingTheLastUrlOfABrowserDropsTheBrowser() {
        Project p = new Project("x", null, new HashMap<>(), new ArrayList<>());
        p.urlsByBrowser.put("brave", new ArrayList<>(List.of("https://a.com/", "https://b.com/")));
        p.urlsByBrowser.put("Chrome", new ArrayList<>(List.of("https://c.com/")));

        ColdstartController.removeUrl(p, "brave", "https://a.com/");
        ColdstartController.removeUrl(p, "Chrome", "https://c.com/");

        assertEquals(List.of("https://b.com/"), p.urlsByBrowser.get("brave"));
        assertFalse(p.urlsByBrowser.containsKey("Chrome"));
    }
}
