package com.example.resumeone;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("priority-high")
class HelloControllerSegregateAppsTest {

    private final HelloController controller = new HelloController();

    @Test
    void emptyRunningAppsProducesEveryCategoryEmpty() {
        HashMap<String, HashSet<String>> result = controller.segregateApps(new HashSet<>());

        assertEquals(java.util.Set.of("IDE", "Browser", "Music", "Communication", "Notes", "Terminal",
                "AI", "Database", "API", "Containers", "VersionControl", "Design", "Other"), result.keySet());
        result.values().forEach(bucket -> assertTrue(bucket.isEmpty()));
    }

    @Test
    void sampleMixOfKnownAppsIsBucketedByCategory() {
        HashSet<String> running = new HashSet<>(java.util.List.of(
                "chrome.exe", "Code.exe", "spotify.exe", "discord.exe", "notion.exe", "claude.exe"
        ));

        HashMap<String, HashSet<String>> result = controller.segregateApps(running);

        assertEquals(java.util.Set.of("chrome.exe"), result.get("Browser"));
        assertEquals(java.util.Set.of("Code.exe"), result.get("IDE"));
        assertEquals(java.util.Set.of("spotify.exe"), result.get("Music"));
        assertEquals(java.util.Set.of("discord.exe"), result.get("Communication"));
        assertEquals(java.util.Set.of("notion.exe"), result.get("Notes"));
        assertEquals(java.util.Set.of("claude.exe"), result.get("AI"));
        assertTrue(result.get("Terminal").isEmpty());
    }

    @Test
    void appsMissingFromTheKnownListAreKeptUnderOther() {
        // capture no longer depends on the known-apps map, so niche tools still get saved
        HashSet<String> running = new HashSet<>(java.util.List.of(
                "some-random-tool.exe", "chrome.exe"
        ));

        HashMap<String, HashSet<String>> result = controller.segregateApps(running);

        assertEquals(java.util.Set.of("some-random-tool.exe"), result.get("Other"));
        assertEquals(java.util.Set.of("chrome.exe"), result.get("Browser"));
    }

    @Test
    void multipleAppsInSameCategoryAreAllCaptured() {
        HashSet<String> running = new HashSet<>(java.util.List.of(
                "chrome.exe", "brave.exe", "firefox.exe", "msedge.exe"
        ));

        HashMap<String, HashSet<String>> result = controller.segregateApps(running);

        assertEquals(4, result.get("Browser").size());
    }

    @Test
    void categorizationIgnoresExecutableNameCase() {
        // tasklist reports real casing (Spotify.exe, WindowsTerminal.exe), which never matched
        // the lowercase keys the map used to rely on
        HashSet<String> running = new HashSet<>(java.util.List.of(
                "CHROME.EXE", "Spotify.exe", "windowsterminal.exe"
        ));

        HashMap<String, HashSet<String>> result = controller.segregateApps(running);

        // bucketed regardless of case, and stored with the casing windows actually reported
        assertEquals(java.util.Set.of("CHROME.EXE"), result.get("Browser"));
        assertEquals(java.util.Set.of("Spotify.exe"), result.get("Music"));
        assertEquals(java.util.Set.of("windowsterminal.exe"), result.get("Terminal"));
        assertTrue(result.get("Other").isEmpty());
    }

    @Test
    void shellAndSystemWindowsAreNotTreatedAsApps() {
        // all observed owning a visible window on a real desktop, none of them user "apps"
        assertTrue(HelloController.isIgnoredWindow("ApplicationFrameHost.exe"));
        assertTrue(HelloController.isIgnoredWindow("TextInputHost.exe"));
        assertTrue(HelloController.isIgnoredWindow("PowerToys.WorkspacesEditor.exe"));
        assertTrue(HelloController.isIgnoredWindow("PowerToys.QuickAccess.exe"));
        assertFalse(HelloController.isIgnoredWindow("brave.exe"));
        assertFalse(HelloController.isIgnoredWindow("WindowsTerminal.exe"));
    }

    @Test
    void garbledShellOutputIsNeverTreatedAsAnApp() {
        // regression: unescaped quotes made powershell echo the script back, and it got saved
        assertTrue(HelloController.isIgnoredWindow("ForEach-Object { $($_.Id)|$($_.ProcessName).exe }"));
        assertTrue(HelloController.isIgnoredWindow("Get-Process"));
    }

    @Test
    void claudeCapturedInsideTerminalIsLaunchedThroughWt() {
        // regression: `start wt` + `start claude` left an empty PowerShell window next to claude's
        java.util.List<String> inWt = HelloController.claudeCommand("C:\\proj", true);
        assertEquals(java.util.List.of("cmd.exe", "/c", "start", "wt", "-d", "C:\\proj",
                "claude", "agents", "--cwd", "C:\\proj"), inWt);

        java.util.List<String> bare = HelloController.claudeCommand("C:\\proj", false);
        assertEquals(java.util.List.of("cmd.exe", "/c", "start", "claude", "agents", "--cwd", "C:\\proj"), bare);

        // regression: a project saved without a folder used to launch nothing at all
        assertEquals(java.util.List.of("cmd.exe", "/c", "start", "wt", "claude", "agents"),
                HelloController.claudeCommand(null, true));
    }

    @Test
    void alreadyRunningAppsAreNotLaunchedAgain() {
        java.util.Set<String> running = java.util.Set.of("Spotify.exe", "brave.exe");
        java.util.Set<String> noBrowsers = java.util.Set.of();

        assertFalse(HelloController.shouldLaunch("spotify.exe", running, noBrowsers),
                "already open, matched case-insensitively");
        assertTrue(HelloController.shouldLaunch("Postman.exe", running, noBrowsers),
                "not running, so launch it");
    }

    @Test
    void onlyTheBrowsersReceivingUrlsAreSkipped() {
        java.util.Set<String> nothingRunning = java.util.Set.of();
        java.util.Set<String> chromeHandled = java.util.Set.of("chrome.exe");

        // resumeProject launches chrome itself with the saved tabs as arguments
        assertFalse(HelloController.shouldLaunch("chrome.exe", nothingRunning, chromeHandled));
        assertFalse(HelloController.shouldLaunch("CHROME.EXE", nothingRunning, chromeHandled),
                "windows reports real casing, the handled set is lowercased");
        assertTrue(HelloController.shouldLaunch("chrome.exe", nothingRunning, java.util.Set.of()));

        // a browser with no saved urls still needs launching, or its window is lost on resume
        assertTrue(HelloController.shouldLaunch("firefox.exe", nothingRunning, chromeHandled),
                "captured but has no url bucket of its own");
        assertTrue(HelloController.shouldLaunch("Spotify.exe", nothingRunning, chromeHandled),
                "non-browsers are unaffected by urls");
    }

    @Test
    void browserNamesFromTheExtensionMapToExecutablesCaseInsensitively() {
        // getBrowserFromBrands() sends "brave" lowercased but "Chrome"/"Edge"/"Opera" capitalised
        assertEquals("chrome.exe", HelloController.BROWSER_EXE.get("Chrome".toLowerCase()));
        assertEquals("msedge.exe", HelloController.BROWSER_EXE.get("Edge".toLowerCase()));
        assertEquals("brave.exe", HelloController.BROWSER_EXE.get("brave".toLowerCase()));
        assertNull(HelloController.BROWSER_EXE.get(HelloController.DEFAULT_BROWSER),
                "the unknown-browser bucket has no executable, so it falls back to Desktop.browse");
    }

    @Test
    void registryOutputIsParsedIntoAnExecutablePath() {
        // real `reg query "...\\App Paths\\chrome.exe" /ve` output, key line included
        String output = "\r\n"
                + "HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\chrome.exe\r\n"
                + "    (Default)    REG_SZ    C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe\r\n";

        assertEquals("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                HelloController.parseRegPath(output),
                "the path contains spaces, so only the trailing side may be trimmed");
    }

    @Test
    void missingRegistryKeyParsesToNoPath() {
        assertNull(HelloController.parseRegPath(
                "ERROR: The system was unable to find the specified registry key or value.\r\n"));
        assertNull(HelloController.parseRegPath(""));
    }

    @Test
    void executableNamesContainingSpacesAreCategorized() {
        HashSet<String> running = new HashSet<>(java.util.List.of("Docker Desktop.exe", "LM Studio.exe"));

        HashMap<String, HashSet<String>> result = controller.segregateApps(running);

        assertEquals(java.util.Set.of("Docker Desktop.exe"), result.get("Containers"));
        assertEquals(java.util.Set.of("LM Studio.exe"), result.get("AI"));
    }

    @Test
    void plainNotepadIsANotesAppNotOther() {
        var result = new HelloController().segregateApps(new java.util.HashSet<>(java.util.List.of("Notepad.exe")));
        assertEquals(java.util.Set.of("Notepad.exe"), result.get("Notes"));
        assertEquals(java.util.Set.of(), result.getOrDefault("Other", new java.util.HashSet<>()));
    }
}
