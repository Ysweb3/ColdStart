package com.example.resumeone;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("priority-medium")
class BrowserProfileTest {

    @Test
    void aSignedInProfileGetsItsOwnBucketAndTheBrandStillFindsTheExe() {
        String key = Browsers.browserKey("Chrome", "me@x.com");
        assertEquals("Chrome · me@x.com", key);
        assertEquals("Chrome", Browsers.browserKey("Chrome", ""), "signed in to nothing: brand only");
        assertEquals("Chrome", Browsers.brandOf(key));
        assertEquals("me@x.com", Browsers.profileOf(key));
        assertNull(Browsers.profileOf("brave"));
    }

    @Test
    void savedTabsGoOnlyToTheirOwnProfileButOldBucketsToAnyOfTheBrand() {
        assertTrue(Browsers.sameBrowser("Chrome · me@x.com", "Chrome · me@x.com"));
        assertFalse(Browsers.sameBrowser("Chrome · work@x.com", "Chrome · me@x.com"));
        assertFalse(Browsers.sameBrowser("Chrome", "Chrome · me@x.com"));
        assertTrue(Browsers.sameBrowser("Chrome · me@x.com", "Chrome"), "saved before profiles");
        assertFalse(Browsers.sameBrowser("Edge · me@x.com", "Chrome"));
    }

    @Test
    void theProfileFolderComesFromLocalStateByEmail(@TempDir Path dir) throws Exception {
        Path localState = dir.resolve("Local State");
        Files.writeString(localState, """
                {"profile":{"info_cache":{
                  "Default":{"name":"Person 1","user_name":""},
                  "Profile 2":{"name":"Work","user_name":"Me@X.com"}}}}""");
        assertEquals("Profile 2", Browsers.profileDir(localState.toFile(), "me@x.com"));
        assertEquals("Work", Browsers.profileName(localState.toFile(), "me@x.com"), "the heading shows this");
        assertNull(Browsers.profileDir(localState.toFile(), "nobody@x.com"));
        assertNull(Browsers.profileName(localState.toFile(), "nobody@x.com"), "unknown: email stays");
        assertNull(Browsers.profileDir(dir.resolve("missing").toFile(), "me@x.com"));
        Files.writeString(localState, "{not json");
        assertNull(Browsers.profileDir(localState.toFile(), "me@x.com"), "garbage: browser picks");
        assertNull(Browsers.profileDir("Chrome"), "no profile in the key, nothing to look up");
    }

    @Test
    void theBrowserStartsOnItsProfileWithEachUrlItsOwnArgument() {
        assertEquals(List.of("chrome.exe", "--profile-directory=Profile 2", "https://a.com/?q=1&r=2"),
                Browsers.browserCommand("chrome.exe", "Profile 2", List.of("https://a.com/?q=1&r=2")));
        assertEquals(List.of("chrome.exe"), Browsers.browserCommand("chrome.exe", null, List.of()));
    }
}
