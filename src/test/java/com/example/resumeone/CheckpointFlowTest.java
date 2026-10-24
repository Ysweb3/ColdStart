package com.example.resumeone;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** What one press of Checkpoint writes onto a project: apps, the folders and exe paths the window
    listing found, and where every window sat, in each mode, and what survives undo and the disk. */
class CheckpointFlowTest {

    static final String MAXED_LEFT = "1,-8,-8,1936,1048";        // maximized on the main monitor
    static final String MAXED_RIGHT = "1,1912,-8,2576,1408";     // maximized on a second monitor
    static final String NORMAL = "0,100,50,800,600";

    static Project saved() {
        HashMap<String, HashSet<String>> apps = new HashMap<>();
        apps.put("IDE", new HashSet<>(Set.of("idea64.exe")));
        apps.put("Notes", new HashSet<>(Set.of("Notepad.exe")));
        Project p = new Project("atlas-api", "C:\\dev\\atlas-api", apps, new ArrayList<>());
        p.folders = map("idea64.exe", "C:\\dev\\atlas-api");
        p.exePaths = new HashMap<>(Map.of("idea64.exe", "C:\\JetBrains\\idea64.exe"));
        p.windows = map("idea64.exe", MAXED_LEFT, "Notepad.exe", NORMAL);
        return p;
    }

    /** exe, value, exe, value...: one value each */
    static HashMap<String, ArrayList<String>> map(String... kv) {
        HashMap<String, ArrayList<String>> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.computeIfAbsent(kv[i], k -> new ArrayList<>()).add(kv[i + 1]);
        return m;
    }

    static AppFolders.Capture capture(HashMap<String, ArrayList<String>> folders, HashMap<String, String> exePaths,
                                      HashMap<String, ArrayList<String>> windows) {
        return new AppFolders.Capture(folders, exePaths, windows, List.of());
    }

    @Test
    void eachModeAsksForWhatItsMenuItemSays() {
        // the block itself overwrites both; the chevron's three items only ever add
        assertTrue(ColdstartController.Checkpoint.OVERWRITE.tabs() && ColdstartController.Checkpoint.OVERWRITE.apps());
        assertFalse(ColdstartController.Checkpoint.OVERWRITE.merge());
        assertTrue(ColdstartController.Checkpoint.ADD_TABS.tabs() && !ColdstartController.Checkpoint.ADD_TABS.apps());
        assertTrue(ColdstartController.Checkpoint.ADD_APPS.apps() && !ColdstartController.Checkpoint.ADD_APPS.tabs());
        assertTrue(ColdstartController.Checkpoint.ADD_BOTH.tabs() && ColdstartController.Checkpoint.ADD_BOTH.apps());
        for (ColdstartController.Checkpoint add : List.of(ColdstartController.Checkpoint.ADD_TABS,
                ColdstartController.Checkpoint.ADD_APPS, ColdstartController.Checkpoint.ADD_BOTH)) {
            assertTrue(add.merge(), add + " must never drop what was saved");
        }
    }

    @Test
    void overwriteReplacesTheAppsTheirFoldersAndWhereTheirWindowsSat() {
        Project p = saved();
        HashMap<String, HashSet<String>> now = new HashMap<>();
        now.put("IDE", new HashSet<>(Set.of("Code.exe")));
        ColdstartController.applyCapture(p, false, now, capture(
                map("Code.exe", "C:\\dev\\atlas-web"),
                new HashMap<>(Map.of("Code.exe", "C:\\VSCode\\Code.exe")),
                map("Code.exe", MAXED_RIGHT, "Code.exe", NORMAL)));

        assertEquals(now, p.apps);
        assertEquals(map("Code.exe", "C:\\dev\\atlas-web"), p.folders);
        assertEquals(Map.of("Code.exe", "C:\\VSCode\\Code.exe"), p.exePaths);
        // IntelliJ and Notepad were closed: their places go with them, so resume cannot move
        // some later window into a spot that belonged to an app no longer in the project
        assertEquals(Map.of("Code.exe", List.of(MAXED_RIGHT, NORMAL)), p.windows);
    }

    @Test
    void addModeMovesAnOpenAppToWhereItIsNowAndKeepsTheRestWhereTheyWere() {
        Project p = saved();
        HashMap<String, HashSet<String>> now = new HashMap<>();
        now.put("IDE", new HashSet<>(Set.of("idea64.exe")));
        // IntelliJ is open on another monitor and a second project; Notepad is closed
        ColdstartController.applyCapture(p, true, now, capture(
                map("idea64.exe", "C:\\DEV\\ATLAS-API", "idea64.exe", "C:\\dev\\billing"),
                new HashMap<>(Map.of("idea64.exe", "D:\\JetBrains\\idea64.exe")),
                map("idea64.exe", MAXED_RIGHT)));

        assertEquals(Set.of("idea64.exe"), p.apps.get("IDE"));
        assertEquals(Set.of("Notepad.exe"), p.apps.get("Notes"), "add mode keeps a closed app");
        // the same folder in another case is not added twice; the new one is appended
        assertEquals(List.of("C:\\dev\\atlas-api", "C:\\dev\\billing"), p.folders.get("idea64.exe"));
        assertEquals("D:\\JetBrains\\idea64.exe", p.exePaths.get("idea64.exe"), "an app open now runs from where it is now");
        assertEquals(List.of(MAXED_RIGHT), p.windows.get("idea64.exe"));
        assertEquals(List.of(NORMAL), p.windows.get("Notepad.exe"), "a closed app keeps its saved place");
    }

    @Test
    void aFailedWindowListingKeepsTheSavedFoldersAndPlaces() {
        Project p = saved();
        HashMap<String, ArrayList<String>> folders = p.folders, windows = p.windows;
        HashMap<String, String> exePaths = p.exePaths;
        HashMap<String, HashSet<String>> now = new HashMap<>();
        now.put("IDE", new HashSet<>(Set.of("idea64.exe")));

        // overwrite still records the apps, but forgets no folder or place it could not see
        ColdstartController.applyCapture(p, false, now, null);

        assertEquals(now, p.apps);
        assertSame(folders, p.folders);
        assertSame(exePaths, p.exePaths);
        assertSame(windows, p.windows);
    }

    @Test
    void addTabsLeavesTheAppsAndTheirWindowsAlone() {
        Project p = saved();
        String before = new ColdstartController().gson.toJson(p);
        ColdstartController.applyCapture(p, true, null, null);
        assertEquals(before, new ColdstartController().gson.toJson(p));
    }

    @Test
    void aProjectFromBeforeFoldersWereSavedTakesThemOnItsFirstAdd() {
        Project p = saved();
        p.folders = null;
        p.exePaths = null;
        p.windows = null;
        ColdstartController.applyCapture(p, true, new HashMap<>(), capture(
                map("idea64.exe", "C:\\dev\\atlas-api"), new HashMap<>(), map("idea64.exe", MAXED_LEFT)));
        assertEquals(map("idea64.exe", "C:\\dev\\atlas-api"), p.folders);
        assertEquals(map("idea64.exe", MAXED_LEFT), p.windows);
        assertNotNull(p.exePaths);
    }

    @Test
    void undoPutsEveryWindowBackWhereTheEarlierCheckpointHadIt() {
        ColdstartController c = new ColdstartController();
        Project p = saved();
        String before = c.gson.toJson(p);

        ColdstartController.applyCapture(p, false, new HashMap<>(), capture(
                new HashMap<>(), new HashMap<>(), map("Code.exe", MAXED_RIGHT)));
        assertEquals(Set.of("Code.exe"), p.windows.keySet());

        c.restore(p, before);
        assertEquals(map("idea64.exe", MAXED_LEFT, "Notepad.exe", NORMAL), p.windows);
        assertEquals(map("idea64.exe", "C:\\dev\\atlas-api"), p.folders);
        assertEquals(Map.of("idea64.exe", "C:\\JetBrains\\idea64.exe"), p.exePaths);
    }

    @Test
    void windowPlacesSurviveTheTripToDiskAndBack() {
        ColdstartController c = new ColdstartController();
        Project p = saved();
        p.windows.get("idea64.exe").add(MAXED_RIGHT);   // two IntelliJ windows, capture order kept

        Project back = c.gson.fromJson(c.gson.toJson(p), Project.class);

        assertEquals(List.of(MAXED_LEFT, MAXED_RIGHT), back.windows.get("idea64.exe"));
        assertEquals(List.of(NORMAL), back.windows.get("Notepad.exe"));
        assertEquals(p.folders, back.folders);
        assertEquals(p.exePaths, back.exePaths);
    }

    @Test
    void aProjectSavedBeforeWindowPlacesLoadsWithNone() {
        Project back = new ColdstartController().gson.fromJson(
                "{\"projectName\":\"old\",\"apps\":{\"IDE\":[\"idea64.exe\"]},\"urlsByBrowser\":{}}", Project.class);
        assertNull(back.windows);
        ColdstartController.placeWindows(back.windows);   // returns at once: nothing to place, no PowerShell
    }

    @Test
    void onlyTheAppsTheCheckpointKeptHoldAPlace() {
        // the window listing sees coldstart's own java.exe and the desktop's explorer.exe too
        HashMap<String, ArrayList<String>> windows = map("idea64.exe", MAXED_LEFT, "java.exe", NORMAL,
                "explorer.exe", "1,0,0,1920,1080");
        HashMap<String, HashSet<String>> apps = new HashMap<>();
        apps.put("IDE", new HashSet<>(Set.of("IDEA64.EXE")));
        ColdstartController.keepCapturedExePaths(windows, apps);
        assertEquals(Set.of("idea64.exe"), windows.keySet());
    }

    @Test
    void anAppSeenOnlyThroughItsWindowsJoinsTheCardOnce() {
        HashMap<String, HashSet<String>> apps = new HashMap<>();
        apps.put("IDE", new HashSet<>(Set.of("Code.exe")));
        // Explorer's folder windows are not in Get-Process; VS Code is, in another case
        ColdstartController.addFolderApps(apps, map("explorer.exe", "C:\\Users\\me\\Downloads", "code.exe", "C:\\dev"));
        assertEquals(Set.of("Code.exe"), apps.get("IDE"));
        assertEquals(Set.of("explorer.exe"), apps.get("Files"));
    }

    @Test
    void placesOnASecondMonitorAreKeptAndMalformedOnesAreNot() {
        var found = AppFolders.resolve(List.of(
                new AppFolders.Window("Code.exe", "", "", MAXED_RIGHT, "a - Visual Studio Code"),
                new AppFolders.Window("Code.exe", "", "", "0,-1600,200,1200,900", "b - Visual Studio Code"),   // left of main
                new AppFolders.Window("Code.exe", "", "", "2,0,0,800,600", "c - Visual Studio Code"),      // minimized
                new AppFolders.Window("Code.exe", "", "", "1,0,0,-5,600", "d - Visual Studio Code"),       // negative size
                new AppFolders.Window("Code.exe", "", "", "1,x,0,800,600", "e - Visual Studio Code")),     // garbage
                new File("nowhere"), "nowhere");
        assertEquals(List.of(MAXED_RIGHT, "0,-1600,200,1200,900"), found.windows().get("Code.exe"));
    }
}
