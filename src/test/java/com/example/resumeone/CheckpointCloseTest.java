package com.example.resumeone;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CheckpointCloseTest {

    @Test
    void closesOnlyWhatResumeBringsBackAndNeverTerminalsBrowsersOrExplorer() {
        HashMap<String, HashSet<String>> apps = new HashMap<>();
        apps.put("IDE", new HashSet<>(List.of("Code.exe")));
        apps.put("Design", new HashSet<>(List.of("blender.exe")));
        apps.put("AI", new HashSet<>(List.of("claude.exe", "ChatGPT.exe")));
        apps.put("Terminal", new HashSet<>(List.of("WindowsTerminal.exe")));
        apps.put("Browser", new HashSet<>(List.of("chrome.exe")));
        apps.put("Files", new HashSet<>(List.of("explorer.exe")));
        apps.put("Other", new HashSet<>(List.of("someTool.exe")));
        apps.put("Apps", new HashSet<>(List.of("Spotify.exe")));

        Set<String> close = ColdstartController.appsToClose(apps);

        assertEquals(new TreeSet<>(List.of("blender.exe", "ChatGPT.exe", "Code.exe", "Spotify.exe")), new TreeSet<>(close));
    }

    @Test
    void stillOpenIsTheClosedAppsThatKeptAWindow() {
        Set<String> closed = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        closed.addAll(List.of("Code.exe", "blender.exe"));
        assertEquals(List.of("blender.exe"), ColdstartController.stillOpen(closed, Set.of("Blender.exe", "chrome.exe")));
        assertTrue(ColdstartController.stillOpen(closed, Set.of("chrome.exe")).isEmpty());
    }

    @Test
    void titlesThatHoldUnsavedWork() {
        for (String t : List.of("*notes.txt - Notepad", "\u25cf background.js - ResumeOneExtension - Visual Studio Code",
                "Blender* [C:\\x\\scene.blend]", "Untitled - Paint", "*C:\\x\\a.txt - Notepad++", "scene.blend* - Blender 4.2")) {
            assertTrue(AppFolders.looksUnsaved(t), t);
        }
        for (String t : List.of("notes.txt - Notepad", "background.js - ResumeOneExtension - Visual Studio Code",
                "Blender [C:\\x\\scene.blend]", "photo.png - Paint", "ResumeOne \u2013 ColdstartController.java")) {
            assertFalse(AppFolders.looksUnsaved(t), t);
        }
        // Word reports no marker in its title; a document with no folder was never saved
        assertTrue(AppFolders.unsavedDocument("Document1"));
        assertFalse(AppFolders.unsavedDocument("C:\\x\\report.docx"));
        assertFalse(AppFolders.unsavedDocument("https://d.docs.live.net/abc/report.docx"));
    }

    @Test
    void captureNamesTheAppsHoldingUnsavedWorkOnce() {
        var found = AppFolders.resolve(List.of(
                new AppFolders.Window("mspaint.exe", "Untitled - Paint"),
                new AppFolders.Window("mspaint.exe", "*Untitled - Paint"),
                new AppFolders.Window("Code.exe", "x.ts - proj - Visual Studio Code"),
                new AppFolders.Window("WINWORD.EXE", "C:\\Office\\WINWORD.EXE", "", "Document1")),
                new java.io.File("nowhere"), "nowhere");
        assertEquals(List.of("mspaint.exe", "WINWORD.EXE"), found.unsaved());
    }

    @Test
    void countLineReadsAsTheHandoffDoes() {
        assertEquals("6 apps \u00b7 8 tabs", ColdstartController.countLine(6, 8));
        assertEquals("1 app \u00b7 1 tab", ColdstartController.countLine(1, 1));
    }

    @Test
    void scriptQuotesNamesAndOnlyTouchesExplorerWhenAsked() {
        String s = ColdstartController.closeScript(List.of("Code.exe", "Bob's App.exe"), false);
        assertTrue(s.contains("$n = @('Code','Bob''s App')"), s);
        assertFalse(s.contains("Shell.Application"));
        assertTrue(ColdstartController.closeScript(List.of(), true).contains("$n = @(); "));
        assertTrue(ColdstartController.closeScript(List.of(), true).contains("Shell.Application"));
    }
}
