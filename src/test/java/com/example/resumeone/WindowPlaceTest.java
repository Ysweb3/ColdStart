package com.example.resumeone;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class WindowPlaceTest {

    @Test
    void captureKeepsNormalAndMaximizedPlacesOnly() {
        var found = AppFolders.resolve(List.of(
                new AppFolders.Window("chrome.exe", "", "", "1,-8,-8,1936,1048", "x - Google Chrome"),
                new AppFolders.Window("chrome.exe", "", "", "0,100,50,800,600", "y - Google Chrome"),
                new AppFolders.Window("Spotify.exe", "", "", "2,-32000,-32000,160,28", "Spotify"),   // minimized
                new AppFolders.Window("explorer.exe", "", "", ",0,1040,1920,48", "Taskbar")),         // no WindowPattern
                new File("nowhere"), "nowhere");
        assertEquals(Map.of("chrome.exe", List.of("1,-8,-8,1936,1048", "0,100,50,800,600")), found.windows());
    }

    @Test
    void scriptCarriesValidPlacesQuotedAndDropsTheRest() {
        HashMap<String, ArrayList<String>> windows = new HashMap<>();
        windows.put("Code.exe", new ArrayList<>(List.of("1,-8,-8,1936,1048", "0,1,2,3,4'); Remove-Item x; ('")));
        windows.put("Bob's App.exe", new ArrayList<>(List.of("0,10,20,300,400")));
        windows.put("bad.exe", new ArrayList<>(List.of("nope")));
        String s = ColdstartController.placeScript(windows);
        assertTrue(s.contains("'Code' = [Collections.ArrayList]@('1,-8,-8,1936,1048'); "), s);
        assertTrue(s.contains("'Bob''s App' = [Collections.ArrayList]@('0,10,20,300,400'); "), s);
        assertFalse(s.contains("Remove-Item"));
        assertFalse(s.contains("'bad'"));
        assertFalse(s.contains("\""), "double quotes break ProcessBuilder's windows quoting");
    }
}
