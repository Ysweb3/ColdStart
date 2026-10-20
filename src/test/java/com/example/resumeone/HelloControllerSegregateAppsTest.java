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
                "AI", "Database", "API", "Containers", "VersionControl", "Design", "Video", "Office", "Files", "Other", "Apps",
                "CAD", "Research", "Finance", "Games", "Planning"),
                result.keySet());
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
        java.util.List<String> inWt = HelloController.cliCommand("claude.exe", "C:\\proj", true);
        assertEquals(java.util.List.of("cmd.exe", "/c", "start", "wt", "-d", "C:\\proj",
                "claude", "agents", "--cwd", "C:\\proj"), inWt);

        java.util.List<String> bare = HelloController.cliCommand("claude.exe", "C:\\proj", false);
        assertEquals(java.util.List.of("cmd.exe", "/c", "start", "claude", "agents", "--cwd", "C:\\proj"), bare);

        // regression: a project saved without a folder used to launch nothing at all
        assertEquals(java.util.List.of("cmd.exe", "/c", "start", "wt", "claude", "agents"),
                HelloController.cliCommand("claude.exe", null, true));
    }

    @Test
    void otherCliesReopenTheirLastChatInTheProjectFolder() {
        assertEquals(java.util.List.of("cmd.exe", "/c", "start", "wt", "-d", "C:\\proj", "codex", "resume", "--last"),
                HelloController.cliCommand("codex-cli.exe", "C:\\proj", true));
        assertEquals(java.util.List.of("cmd.exe", "/c", "start", "gemini", "--resume"),
                HelloController.cliCommand("gemini-cli.exe", "C:\\proj", false));
        // no folder, no "last chat here": a fresh one
        assertEquals(java.util.List.of("cmd.exe", "/c", "start", "kiro-cli"),
                HelloController.cliCommand("kiro-cli.exe", null, false));

        // wt is not started on its own: each CLI brings its own terminal window
        HashMap<String, HashSet<String>> apps = new HashMap<>();
        apps.put("AI", new HashSet<>(java.util.Set.of("codex-cli.exe", "aider.exe")));
        apps.put("Terminal", new HashSet<>(java.util.Set.of("WindowsTerminal.exe")));
        Project p = new Project("P", "C:\\proj", apps, new java.util.ArrayList<>());
        var plan = new HelloController().planResume(p, java.util.Set.of("codex-cli.exe"), new java.util.ArrayList<>());
        assertEquals(java.util.List.of("Aider", "Codex CLI"), plan.steps().stream().map(s -> s.label).toList(),
                "a CLI already running elsewhere still opens for this project");
        assertTrue(HelloController.appsToClose(apps).stream().noneMatch(HelloController::isCli));
    }

    @Test
    void desktopAppsAreToldApartFromTheirCliesByWhereTheyInstall() {
        String store = "C:\\Program Files\\WindowsApps\\Claude_2.42304.2.0_x64__pzs8sxrjxfjjc\\app\\claude.exe";
        assertEquals("Claude Desktop.exe", HelloController.capturedApp("claude.exe", true, store));
        assertNull(HelloController.capturedApp("claude.exe", false, store), "a helper or the tray: no window");
        assertEquals("claude.exe", HelloController.capturedApp("claude.exe", true, "C:\\Users\\y\\.local\\bin\\claude.exe"),
                "a CLI in conhost owns a window too");
        assertEquals("codex-cli.exe", HelloController.capturedApp("codex.exe", false,
                "C:\\Users\\y\\AppData\\Roaming\\npm\\node_modules\\@openai\\codex\\vendor\\codex.exe"));
        assertEquals("Goose.exe", HelloController.capturedApp("Goose.exe", true, "C:\\Users\\y\\AppData\\Local\\Programs\\Goose\\Goose.exe"));
        assertEquals("steam.exe", HelloController.capturedApp("steamwebhelper.exe", true, "C:\\Steam\\bin\\cef\\steamwebhelper.exe"));
        assertEquals("Spotify.exe", HelloController.capturedApp("Spotify.exe", true, "C:\\x\\Spotify.exe"));

        // the desktop app lands in AI and starts by its Store id; it is not closed by name
        var result = controller.segregateApps(new HashSet<>(java.util.Set.of("Claude Desktop.exe")));
        assertEquals(java.util.Set.of("Claude Desktop.exe"), result.get("AI"));
        Project p = new Project("P", "C:\\proj", result, new java.util.ArrayList<>());
        var plan = new HelloController().planResume(p, java.util.Set.of(), new java.util.ArrayList<>());
        assertEquals(java.util.List.of("start Claude Desktop"), plan.steps().stream().map(s -> s.command).toList());
        assertTrue(HelloController.appsToClose(result).isEmpty());
    }

    @Test
    void nodeCliesAreFoundByThePackageInTheirCommandLine() {
        assertEquals("gemini-cli.exe", HelloController.nodeCli(
                "\"C:\\Program Files\\nodejs\\node.exe\" C:\\Users\\y\\AppData\\Roaming\\npm\\node_modules\\@google\\gemini-cli\\dist\\index.js"));
        assertEquals("copilot-cli.exe", HelloController.nodeCli("node C:\\npm\\node_modules\\@github\\copilot\\index.js"));
        assertNull(HelloController.nodeCli("node C:\\x\\node_modules\\@github\\copilot-language-server\\dist\\main.js --stdio"),
                "an editor's copilot server is not the CLI");
        assertNull(HelloController.nodeCli("node C:\\proj\\server.js"), "a dev server is not an app");
    }

    @Test
    void storeAppsAreStartedByTheIdInTheirManifest(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        java.nio.file.Path pkg = tmp.resolve("WindowsApps").resolve("5319275A.WhatsAppDesktop_2.2640.100.0_x64__cv1g1gvanyjgm");
        java.nio.file.Files.createDirectories(pkg);
        // the real manifest's shape: Id before Executable, other attributes around them
        java.nio.file.Files.writeString(pkg.resolve("AppxManifest.xml"), "<Package><Applications>"
                + "<Application Id=\"Helper\" Executable=\"helper.exe\" EntryPoint=\"Windows.FullTrustApplication\"/>"
                + "<Application Id=\"App\" Executable=\"WhatsApp.Root.exe\" EntryPoint=\"Windows.FullTrustApplication\">"
                + "</Application></Applications></Package>");
        assertEquals("5319275A.WhatsAppDesktop_cv1g1gvanyjgm!App",
                HelloController.storeAppId(pkg.resolve("whatsapp.root.exe").toString()));
        assertNull(HelloController.storeAppId(pkg.resolve("other.exe").toString()));
        assertNull(HelloController.storeAppId("C:\\Program Files\\Spotify\\Spotify.exe"));
        assertNull(HelloController.storeAppId(tmp.resolve("WindowsApps").resolve("Gone_1.0_x64__abc").resolve("a.exe").toString()),
                "updated away: no manifest");
    }

    @Test
    void appsForEveryKindOfWorkHaveACategory() {
        var result = controller.segregateApps(new HashSet<>(java.util.List.of(
                "acad.exe", "zotero.exe", "tally.exe", "steam.exe", "Todoist.exe",
                "WhatsApp.Root.exe", "soffice.exe", "Ableton Live 12 Suite.exe", "StataMP-64.exe", "gimp-2.10.exe")));
        assertEquals(java.util.Set.of("acad.exe"), result.get("CAD"));
        assertEquals(java.util.Set.of("zotero.exe", "StataMP-64.exe"), result.get("Research"), "a * entry matches by prefix");
        assertEquals(java.util.Set.of("tally.exe"), result.get("Finance"));
        assertEquals(java.util.Set.of("steam.exe"), result.get("Games"));
        assertEquals(java.util.Set.of("Todoist.exe"), result.get("Planning"));
        assertEquals(java.util.Set.of("WhatsApp.Root.exe"), result.get("Communication"));
        assertEquals(java.util.Set.of("soffice.exe"), result.get("Office"));
        assertEquals(java.util.Set.of("Ableton Live 12 Suite.exe"), result.get("Music"));
        assertEquals(java.util.Set.of("gimp-2.10.exe"), result.get("Design"));
        assertTrue(result.get("Other").isEmpty());
        assertEquals("Stata", HelloController.appName("StataMP-64.exe"));
        assertEquals("LibreOffice", HelloController.appName("soffice.exe"));
        assertEquals("WhatsApp", HelloController.appName("WhatsApp.Root.exe"));
        assertEquals("GIMP", HelloController.appName("gimp-2.10.exe"));
        assertEquals("Ableton Live 12 Suite", HelloController.appName("Ableton Live 12 Suite.exe"));
    }

    @Test
    void noAppIsListedInTwoCategories() {
        // Map.ofEntries iterates in no fixed order: an exe under two keys would land in either
        java.util.Map<String, String> seen = new HashMap<>();
        HelloController.KNOWN_APPS.forEach((category, exes) -> exes.forEach(exe -> {
            String before = seen.put(exe.toLowerCase(), category);
            assertNull(before, exe + " is under " + before + " and " + category);
        }));
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
    void videoEditorsAreAVideoCategoryNotOther() {
        // Other is never relaunched, so an editor's main app used to stay shut on resume
        var result = controller.segregateApps(new HashSet<>(java.util.List.of(
                "Adobe Premiere Pro.exe", "AfterFX.exe", "Resolve.exe")));
        assertEquals(java.util.Set.of("Adobe Premiere Pro.exe", "AfterFX.exe", "Resolve.exe"), result.get("Video"));
        assertTrue(result.get("Other").isEmpty());
        assertEquals("After Effects", HelloController.appName("AfterFX.exe"));
        assertEquals("DaVinci Resolve", HelloController.appName("Resolve.exe"));
        assertEquals("Adobe Premiere Pro", HelloController.appName("Adobe Premiere Pro.exe"));
    }

    @Test
    void everydayWorkAppsAreRelaunchedNotLeftInOther() {
        // Other is never relaunched: Paint and Office were captured and then stayed shut on resume
        var result = controller.segregateApps(new HashSet<>(java.util.List.of(
                "mspaint.exe", "WINWORD.exe", "EXCEL.exe", "OUTLOOK.exe", "obs64.exe")));
        assertEquals(java.util.Set.of("mspaint.exe"), result.get("Design"));
        assertEquals(java.util.Set.of("WINWORD.exe", "EXCEL.exe"), result.get("Office"));
        assertEquals(java.util.Set.of("OUTLOOK.exe"), result.get("Communication"));
        assertEquals(java.util.Set.of("obs64.exe"), result.get("Video"));
        assertTrue(result.get("Other").isEmpty());
        assertEquals("Paint", HelloController.appName("mspaint.exe"));
        assertEquals("Word", HelloController.appName("WINWORD.exe"));
        assertEquals("OBS Studio", HelloController.appName("obs64.exe"));
    }

    @Test
    void appsSavedUnderOtherMoveOnceTheyAreKnown() {
        HashMap<String, HashSet<String>> apps = new HashMap<>();
        apps.put("Other", new HashSet<>(java.util.List.of("mspaint.exe", "NVIDIA App.exe")));
        apps.put("Design", new HashSet<>(java.util.List.of("Figma.exe")));
        Project p = new Project("P", null, apps, new java.util.ArrayList<>());

        HelloController.recategorizeOther(p);

        assertEquals(java.util.Set.of("Figma.exe", "mspaint.exe"), p.apps.get("Design"));
        assertEquals(java.util.Set.of("NVIDIA App.exe"), p.apps.get("Other"), "still unknown, stays");
    }

    @Test
    void otherAppsCanBeRelaunchedOrHidden() {
        HashMap<String, HashSet<String>> apps = new HashMap<>();
        apps.put("Other", new HashSet<>(java.util.Set.of("Tool.exe", "NVIDIA App.exe")));
        Project p = new Project("P", null, apps, new java.util.ArrayList<>());
        try {
            // + : Tool moves to Apps and comes back on resume, in every project
            HelloController.relaunched.add("tool.exe");
            HelloController.recategorizeOther(p);
            assertEquals(java.util.Set.of("Tool.exe"), p.apps.get("Apps"));
            var plan = new HelloController().planResume(p, java.util.Set.of(), new java.util.ArrayList<>());
            assertEquals(java.util.List.of("Tool"), plan.steps().stream().map(s -> s.label).toList());

            // x : NVIDIA App is off the card here and never captured again
            HelloController.hidden.add("nvidia app.exe");
            assertTrue(HelloController.isIgnoredWindow("NVIDIA App.exe"));
            assertTrue(HelloController.visibleApps(p, "Other").isEmpty());

            // Settings' undo: Tool goes back to Other on the next sort
            HelloController.relaunched.clear();
            HelloController.recategorizeOther(p);
            assertEquals(java.util.Set.of("Tool.exe", "NVIDIA App.exe"), p.apps.get("Other"));
        } finally {
            HelloController.relaunched.clear();
            HelloController.hidden.clear();
        }
    }

    @Test
    void onlyCapturedAppsKeepAnExePath() {
        // what the window listing hands back on this machine: the overlay is an ignored window,
        // java.exe is coldstart itself, explorer.exe the desktop - none of them were captured
        java.util.Map<String, String> exePaths = new HashMap<>(java.util.Map.of(
                "idea64.exe", "C:\\JetBrains\\bin\\idea64.exe",
                "NVIDIA Overlay.exe", "C:\\NVIDIA\\NVIDIA Overlay.exe",
                "java.exe", "C:\\jdk\\bin\\java.exe",
                "explorer.exe", "C:\\WINDOWS\\Explorer.EXE"));
        HashMap<String, HashSet<String>> apps = new HashMap<>();
        apps.put("IDE", new HashSet<>(java.util.Set.of("idea64.exe")));

        HelloController.keepCapturedExePaths(exePaths, apps);

        assertEquals(java.util.Set.of("idea64.exe"), exePaths.keySet());
    }

    @Test
    void copilotAppIsAiAndRelaunchesByItsStoreId() {
        // the Windows Copilot app runs as mscopilot.exe, which is on neither PATH nor App Paths
        var result = new HelloController().segregateApps(new java.util.HashSet<>(java.util.List.of("mscopilot.exe")));
        assertEquals(java.util.Set.of("mscopilot.exe"), result.get("AI"));
        assertEquals("Copilot", HelloController.appName("mscopilot.exe"));

        Project p = new Project("P", null, result, new java.util.ArrayList<>());
        var plan = new HelloController().planResume(p, java.util.Set.of(), new java.util.ArrayList<>());
        assertEquals(java.util.List.of("Copilot"), plan.steps().stream().map(s -> s.label).toList());
    }

    @Test
    void plainNotepadIsANotesAppNotOther() {
        var result = new HelloController().segregateApps(new java.util.HashSet<>(java.util.List.of("Notepad.exe")));
        assertEquals(java.util.Set.of("Notepad.exe"), result.get("Notes"));
        assertEquals(java.util.Set.of(), result.getOrDefault("Other", new java.util.HashSet<>()));
    }
}
