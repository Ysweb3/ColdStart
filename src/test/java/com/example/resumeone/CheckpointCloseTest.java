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

        Set<String> close = HelloController.appsToClose(apps);

        assertEquals(new TreeSet<>(List.of("blender.exe", "ChatGPT.exe", "Code.exe", "Spotify.exe")), new TreeSet<>(close));
    }

    @Test
    void stillOpenIsTheClosedAppsThatKeptAWindow() {
        Set<String> closed = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        closed.addAll(List.of("Code.exe", "blender.exe"));
        assertEquals(List.of("blender.exe"), HelloController.stillOpen(closed, Set.of("Blender.exe", "chrome.exe")));
        assertTrue(HelloController.stillOpen(closed, Set.of("chrome.exe")).isEmpty());
    }

    @Test
    void scriptQuotesNamesAndOnlyTouchesExplorerWhenAsked() {
        String s = HelloController.closeScript(List.of("Code.exe", "Bob's App.exe"), false);
        assertTrue(s.contains("$n = @('Code','Bob''s App')"), s);
        assertFalse(s.contains("Shell.Application"));
        assertTrue(HelloController.closeScript(List.of(), true).contains("$n = @(); "));
        assertTrue(HelloController.closeScript(List.of(), true).contains("Shell.Application"));
    }
}
