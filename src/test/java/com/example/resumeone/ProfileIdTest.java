package com.example.resumeone;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProfileIdTest {

    @Test
    void theExtensionsIdIsFoundInItsOwnProfilesStorage(@TempDir Path userData) throws Exception {
        String yash = "3f2b8c1e-4d5a-4b6c-9e7f-0123456789ab", ys = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d";
        Path a = Files.createDirectories(userData.resolve("Default/Local Extension Settings/bnofj"));
        Path b = Files.createDirectories(userData.resolve("Profile 1/Local Extension Settings/bnofj"));
        Files.createDirectories(userData.resolve("Profile 1/Local Extension Settings/other"));
        // LevelDB's log: framing bytes around the key and the JSON-quoted value
        Files.write(a.resolve("000003.log"), ("\u0001\u0002rwProfileId\"" + yash + "\"\u0000").getBytes(StandardCharsets.ISO_8859_1));
        Files.write(b.resolve("000005.ldb"), ("xx\"" + ys + "\"yy").getBytes(StandardCharsets.ISO_8859_1));

        assertEquals("Default", ColdstartController.pidDir(userData.toFile(), yash));
        assertEquals("Profile 1", ColdstartController.pidDir(userData.toFile(), ys));
        assertNull(ColdstartController.pidDir(userData.toFile(), "00000000-0000-4000-8000-000000000000"));
        // only an id goes into the search, never anything else a socket url carries
        assertNull(ColdstartController.pidDir(userData.toFile(), "..\\..\\x"));
        assertNull(ColdstartController.pidDir(userData.toFile(), null));
    }

    @Test
    void aProfileIsFoundByItsNameOrFolderAsWellAsItsEmail(@TempDir Path dir) throws Exception {
        Path localState = dir.resolve("Local State");
        Files.writeString(localState, """
                {"profile":{"info_cache":{
                  "Default":{"name":"Yash","user_name":""},
                  "Profile 1":{"name":"ys","user_name":"me@x.com"}}}}""");
        assertEquals("Profile 1", ColdstartController.profileDir(localState.toFile(), "me@x.com"));
        assertEquals("Default", ColdstartController.profileDir(localState.toFile(), "Yash"));
        assertEquals("Default", ColdstartController.profileDir(localState.toFile(), "Default"));
        assertEquals("ys", ColdstartController.profileName(localState.toFile(), "Profile 1"));
    }

    @Test
    void indexOfFindsBytesAnywhere() {
        byte[] hay = "abcabd".getBytes(StandardCharsets.US_ASCII);
        assertEquals(3, ColdstartController.indexOf(hay, "abd".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(-1, ColdstartController.indexOf(hay, "abe".getBytes(StandardCharsets.US_ASCII)));
    }
}
