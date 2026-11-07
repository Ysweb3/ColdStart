package com.example.resumeone;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class WindowPlaceTest {

    @Test
    void captureKeepsEveryStateButWindowsWithNone() {
        var found = AppFolders.resolve(List.of(
                new AppFolders.Window("chrome.exe", "", "", "1,-8,-8,1936,1048", "x - Google Chrome"),
                new AppFolders.Window("chrome.exe", "", "", "0,100,50,800,600", "y - Google Chrome"),
                new AppFolders.Window("Spotify.exe", "", "", "2,200,150,900,600", "Spotify"),       // minimized
                new AppFolders.Window("Code.exe", "", "", "3,0,0,900,600", "a - Visual Studio Code"), // minimized from maximized
                new AppFolders.Window("Slack.exe", "", "", "", "Slack"),                              // placement unread
                new AppFolders.Window("explorer.exe", "", "", ",0,1040,1920,48", "Taskbar")),         // no WindowPattern
                new File("nowhere"), "nowhere");
        // each place carries its window's title, so resume can tell two windows of one app apart
        assertEquals(Map.of("chrome.exe", List.of("1,-8,-8,1936,1048\tx - Google Chrome", "0,100,50,800,600\ty - Google Chrome"),
                "Spotify.exe", List.of("2,200,150,900,600\tSpotify"), "Code.exe", List.of("3,0,0,900,600\ta - Visual Studio Code")),
                found.windows());
    }

    @Test
    void aTitleNeverCarriesThePlacersSeparators() {
        assertEquals("a b c - x", AppFolders.placeTitle("a\tb;c | - x"));
        assertTrue(AppFolders.PLACE.matcher("0,1,2,3,4\t" + AppFolders.placeTitle("x;y|z\tw")).matches());
        assertFalse(AppFolders.PLACE.matcher("0,1,2,3,4\tx;y").matches());
        assertTrue(AppFolders.PLACE.matcher("0,1,2,3,4").matches(), "places saved before titles still count");
    }

    @Test
    void placesGoInAsDataAndTheScriptHasNoDoubleQuotes() {
        assertEquals("Code|1,-8,-8,1936,1048", WindowPlacer.request("Code.exe", List.of("1,-8,-8,1936,1048", "0,1,2,3,4'); Remove-Item x; ('")));
        assertEquals("Bob's App|0,10,20,300,400", WindowPlacer.request("Bob's App.exe", List.of("0,10,20,300,400")));
        assertNull(WindowPlacer.request("bad.exe", List.of("nope")));
        assertFalse(WindowPlacer.script().contains("\""), "double quotes break ProcessBuilder's windows quoting");
    }
}
