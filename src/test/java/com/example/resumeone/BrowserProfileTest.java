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
        String key = HelloController.browserKey("Chrome", "me@x.com");
        assertEquals("Chrome · me@x.com", key);
        assertEquals("Chrome", HelloController.browserKey("Chrome", ""), "signed in to nothing: brand only");
        assertEquals("Chrome", HelloController.brandOf(key));
        assertEquals("me@x.com", HelloController.profileOf(key));
        assertNull(HelloController.profileOf("brave"));
    }

    @Test
    void savedTabsGoOnlyToTheirOwnProfileButOldBucketsToAnyOfTheBrand() {
        assertTrue(HelloController.sameBrowser("Chrome · me@x.com", "Chrome · me@x.com"));
        assertFalse(HelloController.sameBrowser("Chrome · work@x.com", "Chrome · me@x.com"));
        assertFalse(HelloController.sameBrowser("Chrome", "Chrome · me@x.com"));
        assertTrue(HelloController.sameBrowser("Chrome · me@x.com", "Chrome"), "saved before profiles");
        assertFalse(HelloController.sameBrowser("Edge · me@x.com", "Chrome"));
    }

    @Test
    void theProfileFolderComesFromLocalStateByEmail(@TempDir Path dir) throws Exception {
        Path localState = dir.resolve("Local State");
        Files.writeString(localState, """
                {"profile":{"info_cache":{
                  "Default":{"name":"Person 1","user_name":""},
                  "Profile 2":{"name":"Work","user_name":"Me@X.com"}}}}""");
        assertEquals("Profile 2", HelloController.profileDir(localState.toFile(), "me@x.com"));
        assertNull(HelloController.profileDir(localState.toFile(), "nobody@x.com"));
        assertNull(HelloController.profileDir(dir.resolve("missing").toFile(), "me@x.com"));
        Files.writeString(localState, "{not json");
        assertNull(HelloController.profileDir(localState.toFile(), "me@x.com"), "garbage: browser picks");
        assertNull(HelloController.profileDir("Chrome"), "no profile in the key, nothing to look up");
    }

    @Test
    void theBrowserStartsOnItsProfileWithEachUrlItsOwnArgument() {
        assertEquals(List.of("chrome.exe", "--profile-directory=Profile 2", "https://a.com/?q=1&r=2"),
                HelloController.browserCommand("chrome.exe", "Profile 2", List.of("https://a.com/?q=1&r=2")));
        assertEquals(List.of("chrome.exe"), HelloController.browserCommand("chrome.exe", null, List.of()));
    }
}
