package com.example.resumeone;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The guards that keep one project's checkpoint from being written over by another's. */
class DataSafetyTest {

    private static Project project(String name) {
        return new Project(name, null, new HashMap<>(), new ArrayList<>());
    }

    @Test
    void aTakenNameCannotBeReused() {
        List<Project> projects = List.of(project("atlas-api"));
        assertTrue(HelloController.nameTaken(projects, "atlas-api"));
        assertTrue(HelloController.nameTaken(projects, "  Atlas-API "));
        assertFalse(HelloController.nameTaken(projects, "atlas-web"));
    }

    @Test
    void tabsLandOnTheProjectThatAskedWhileItsWindowIsOpen() {
        Project asked = project("atlas-api"), onScreen = project("ledger");
        assertSame(asked, HelloController.tabsTarget(asked, 100, 50, onScreen));
        // after the window: a manual send from the popup goes to whatever is on screen
        assertSame(onScreen, HelloController.tabsTarget(asked, 100, 150, onScreen));
        // undone inside the window: the reply is dropped
        assertNull(HelloController.tabsTarget(null, 100, 50, onScreen));
    }

    @Test
    void restorePutsBackAppsTabsAndTime() {
        HelloController c = new HelloController();
        Project p = project("atlas-api");
        p.apps.put("IDE", new HashSet<>(Set.of("idea64.exe")));
        p.urlsByBrowser.put("brave", new ArrayList<>(List.of("https://github.com")));
        p.lastCheckpoint = "2026-09-01T10:00:00Z";
        String before = c.gson.toJson(p);

        p.apps = new HashMap<>();
        p.urlsByBrowser = new HashMap<>();
        p.lastCheckpoint = "2026-10-02T10:00:00Z";
        c.restore(p, before);

        assertEquals(Set.of("idea64.exe"), p.apps.get("IDE"));
        assertEquals(List.of("https://github.com"), p.urlsByBrowser.get("brave"));
        assertEquals("2026-09-01T10:00:00Z", p.lastCheckpoint);
    }
}
