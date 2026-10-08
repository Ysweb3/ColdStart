package com.example.resumeone;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    void aLateReplyToTheLastCheckpointDoesNotEndThisOnesWait() {
        // 2 browsers, 1 still owed from a checkpoint whose window is open: wait for 3
        assertEquals(3, HelloController.repliesToWait(2, 1, 100, 50));
        // window closed: that reply is no longer expected
        assertEquals(2, HelloController.repliesToWait(2, 1, 100, 150));
        assertEquals(2, HelloController.repliesToWait(2, 0, 100, 50));
    }

    @Test
    void onlyTheExtensionGetsPastTheServerGate() {
        assertTrue(HelloController.fromExtension("chrome-extension://kgmhnaigjjoknmiipbhokbkpieaclmpg"));
        assertTrue(HelloController.fromExtension(null));
        assertFalse(HelloController.fromExtension("https://evil.example"));
        assertFalse(HelloController.fromExtension("http://localhost:3000"));
        // a sandboxed iframe or a file:// page
        assertFalse(HelloController.fromExtension("null"));
    }

    @Test
    void aQuickSecondSaveKeepsTheFirstOnesUndo() {
        // "Saved" still up (now < savedUntil) and an undo exists: keep it, so Ctrl+Z reaches
        // back past a double click
        assertFalse(HelloController.replaceUndo(50, 100, true));
        assertTrue(HelloController.replaceUndo(50, 100, false));
        assertTrue(HelloController.replaceUndo(150, 100, true));
    }

    @Test
    void restorePutsBackAppsTabsAndTime() {
        HelloController c = new HelloController();
        Project p = project("atlas-api");
        p.apps.put("IDE", new HashSet<>(Set.of("idea64.exe")));
        p.urlsByBrowser.put("brave", new ArrayList<>(List.of("https://github.com")));
        p.lastCheckpoint = "2026-09-01T10:00:00Z";
        p.folders = new HashMap<>(Map.of("idea64.exe", new ArrayList<>(List.of("C:\\atlas-api"))));
        p.exePaths = new HashMap<>(Map.of("idea64.exe", "C:\\JetBrains\\idea64.exe"));
        String before = c.gson.toJson(p);

        p.apps = new HashMap<>();
        p.urlsByBrowser = new HashMap<>();
        p.lastCheckpoint = "2026-10-02T10:00:00Z";
        // the undone checkpoint had IntelliJ on another folder: resume must not open that one
        p.folders = new HashMap<>(Map.of("idea64.exe", new ArrayList<>(List.of("C:\\other"))));
        p.exePaths = new HashMap<>();
        c.restore(p, before);

        assertEquals(Set.of("idea64.exe"), p.apps.get("IDE"));
        assertEquals(List.of("https://github.com"), p.urlsByBrowser.get("brave"));
        assertEquals("2026-09-01T10:00:00Z", p.lastCheckpoint);
        assertEquals(List.of("C:\\atlas-api"), p.folders.get("idea64.exe"));
        assertEquals("C:\\JetBrains\\idea64.exe", p.exePaths.get("idea64.exe"));
    }
}
