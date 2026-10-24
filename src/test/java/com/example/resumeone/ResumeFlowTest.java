package com.example.resumeone;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** What one press of Resume plans and how its rows end up, without launching anything real:
    every plan here is built, not run, except steps that fail before they could start a process. */
class ResumeFlowTest {

    /** a folder no live claude session can be in, so claudeRunningIn never matches */
    static final String NOWHERE = "C:\\coldstart-test-nowhere\\atlas-api";

    static Project project(String folder, Map<String, Set<String>> apps) {
        HashMap<String, HashSet<String>> a = new HashMap<>();
        apps.forEach((k, v) -> a.put(k, new HashSet<>(v)));
        return new Project("atlas-api", folder, a, new ArrayList<>());
    }

    static List<String> labels(ResumePlanner.ResumePlan plan) {
        return plan.steps().stream().map(s -> s.label).toList();
    }

    @Test
    void appsComeBackInTheCardsOrderAndAlphabeticallyWithinARow() {
        Project p = project(null, Map.of(
                "Communication", Set.of("Slack.exe", "Discord.exe"),
                "IDE", Set.of("idea64.exe"),
                "Notes", Set.of("notepad++.exe"),
                "Terminal", Set.of("WindowsTerminal.exe")));
        var plan = new ColdstartController().planResume(p, Set.of(), new ArrayList<>());
        // the card's top-to-bottom order: IDE, Terminal, Communication, Notes
        assertEquals(List.of(AppCatalog.appName("idea64.exe"), AppCatalog.appName("WindowsTerminal.exe"),
                AppCatalog.appName("Discord.exe"), AppCatalog.appName("Slack.exe"),
                AppCatalog.appName("notepad++.exe")), labels(plan));
    }

    @Test
    void claudeOpensItsAgentViewInTheProjectFolderEvenWhileAnotherClaudeRuns() {
        Project p = project(NOWHERE, Map.of("AI", Set.of("claude.exe")));
        // a claude open for some other project must not stand in for this one's
        var plan = new ColdstartController().planResume(p, Set.of("claude.exe"), new ArrayList<>());
        assertEquals(1, plan.steps().size());
        assertEquals("start claude agents --cwd " + NOWHERE, plan.steps().get(0).command);
        assertTrue(plan.skipped().isEmpty());
    }

    @Test
    void claudeWithNoFolderListsEverySessionAndOpensNoTerminalFolder() {
        assertEquals(List.of("cmd.exe", "/c", "start", "claude", "agents"), ResumePlanner.cliCommand("claude.exe", null, false));
        assertEquals(List.of("cmd.exe", "/c", "start", "wt", "claude", "agents"), ResumePlanner.cliCommand("claude.exe", null, true));
        assertEquals(List.of("cmd.exe", "/c", "start", "wt", "-d", NOWHERE, "claude", "agents", "--cwd", NOWHERE),
                ResumePlanner.cliCommand("claude.exe", NOWHERE, true));
    }

    @Test
    void vsCodeWithNoSavedFolderOpensTheProjectFolderOrItsLastWorkspace() {
        var withFolder = new ColdstartController().planResume(project("C:\\dev\\atlas-api", Map.of("IDE", Set.of("Code.exe"))),
                Set.of(), new ArrayList<>());
        assertEquals("code C:\\dev\\atlas-api", withFolder.steps().get(0).command);
        var without = new ColdstartController().planResume(project(null, Map.of("IDE", Set.of("Code.exe"))),
                Set.of(), new ArrayList<>());
        assertEquals("code", without.steps().get(0).command);
    }

    @Test
    void tabsSavedBeforeBrowsersWereTrackedReopenInTheDefaultBrowserLast() {
        Project p = project(null, Map.of("Notes", Set.of("notepad++.exe")));
        p.urls = new ArrayList<>(List.of("https://github.com", "chrome://newtab/", "https://docs.oracle.com"));
        var plan = new ColdstartController().planResume(p, Set.of(), new ArrayList<>());

        ResumePlanner.LaunchStep last = plan.steps().get(plan.steps().size() - 1);
        assertEquals(Browsers.DEFAULT_BROWSER, last.label);
        assertTrue(last.browser);
        // the browser's own pages are not tabs worth bringing back
        assertEquals("2 urls · default browser", last.command);
        assertEquals(List.of("https://github.com", "https://docs.oracle.com"), p.urlsByBrowser.get(Browsers.DEFAULT_BROWSER));
    }

    @Test
    void aFolderMovedSinceTheCheckpointFailsItsRowAndSaysWhere(@TempDir Path tmp) {
        String gone = tmp.resolve("deleted-since").toString();
        ResumePlanner.LaunchStep step = ResumePlanner.folderStep("idea64.exe", gone, null);
        step.run();
        assertEquals(ResumePlanner.LaunchStep.State.FAILED, step.state);
        assertEquals("no longer there: " + gone, step.error);
        assertTrue(step.logLine().contains("failed"));
    }

    @Test
    void eachIdeOpensItsFolderThroughItsOwnCommand() {
        assertEquals("code C:\\dev\\atlas-api", ResumePlanner.folderStep("Code.exe", "C:\\dev\\atlas-api", null).command);
        assertTrue(ResumePlanner.folderStep("idea64.exe", "C:\\dev\\atlas-api", null).command.startsWith("idea64 "));
        assertEquals(AppCatalog.appName("Code.exe") + " · atlas-api",
                ResumePlanner.folderStep("Code.exe", "C:\\dev\\atlas-api", null).label);
    }

    @Test
    void anAppThatCannotBeFoundFailsInsteadOfReadingAsLaunched() {
        String app = "coldstart-test-not-installed.exe";
        Project p = project(null, Map.of("Apps", Set.of(app)));
        p.exePaths = new HashMap<>(Map.of(app, "C:\\coldstart-test-nowhere\\" + app));
        var plan = new ColdstartController().planResume(p, Set.of(), new ArrayList<>());
        ResumePlanner.LaunchStep step = plan.steps().get(0);
        step.run();
        assertEquals(ResumePlanner.LaunchStep.State.FAILED, step.state);
        assertEquals("cannot find " + app, step.error);
    }

    @Test
    void oneFailedLaunchNeverStopsTheRest() {
        List<String> ran = new ArrayList<>();
        List<ResumePlanner.LaunchStep> steps = List.of(
                new ResumePlanner.LaunchStep("a", "", false, () -> { ran.add("a"); throw new IOException("boom"); }),
                new ResumePlanner.LaunchStep("b", "", false, () -> ran.add("b")),
                new ResumePlanner.LaunchStep("c", "", true, () -> { ran.add("c"); throw new ResumePlanner.AlreadyOpen(); }));
        steps.forEach(ResumePlanner.LaunchStep::run);
        assertEquals(List.of("a", "b", "c"), ran);
        assertEquals(List.of(ResumePlanner.LaunchStep.State.FAILED, ResumePlanner.LaunchStep.State.DONE,
                ResumePlanner.LaunchStep.State.SKIPPED), steps.stream().map(s -> s.state).toList());
        assertEquals("boom", steps.get(0).error);
    }

    @Test
    void theRunTalliesEachRowByHowItEnded() {
        var app = step("IntelliJ IDEA", false, ResumePlanner.LaunchStep.State.DONE);
        var chrome = step("Chrome", true, ResumePlanner.LaunchStep.State.DONE);
        var brave = step("brave", true, ResumePlanner.LaunchStep.State.SKIPPED);
        var broken = step("Blender", false, ResumePlanner.LaunchStep.State.FAILED);
        var later = step("Slack", false, ResumePlanner.LaunchStep.State.QUEUED);
        ResumePlanner.ResumeRun r = new ResumePlanner.ResumeRun(project(null, Map.of()));
        r.plan = new ResumePlanner.ResumePlan(List.of(app, chrome, brave, broken, later), List.of("Notepad.exe"));

        assertEquals(5, r.total());
        assertEquals(3, r.done(), "a browser with every tab open already finished fine");
        assertEquals(1, r.failed());
        assertEquals(1, r.queued());
        assertEquals(1, r.appsDone(), "browsers are counted as tabs, not apps");
        assertEquals(List.of("Notepad.exe", "brave"), r.alreadyOpen());
    }

    static ResumePlanner.LaunchStep step(String label, boolean browser, ResumePlanner.LaunchStep.State state) {
        ResumePlanner.LaunchStep s = new ResumePlanner.LaunchStep(label, "", browser, () -> { });
        s.state = state;
        return s;
    }

    @Test
    void tabsAreCountedAcrossEveryBrowserAndProfile() {
        Project p = project(null, Map.of());
        p.urlsByBrowser.put("Chrome · Work", new ArrayList<>(List.of("https://a.dev", "https://b.dev")));
        p.urlsByBrowser.put("brave", new ArrayList<>(List.of("https://c.dev")));
        assertEquals(3, ColdstartController.countTabs(p));
    }

    @Test
    void placementScriptKeepsEachAppsPlacesInCaptureOrder() {
        LinkedHashMap<String, ArrayList<String>> windows = new LinkedHashMap<>();
        windows.put("CODE.EXE", new ArrayList<>(List.of(CheckpointFlowTest.MAXED_RIGHT, CheckpointFlowTest.NORMAL)));
        String s = ColdstartController.placeScript(windows);
        // the process name, without .exe in any case, and the places in the order they were seen
        assertTrue(s.contains("'CODE' = [Collections.ArrayList]@('" + CheckpointFlowTest.MAXED_RIGHT + "','"
                + CheckpointFlowTest.NORMAL + "'); "), s);
        assertTrue(s.contains("$q.RemoveAt(0)"), "each new window takes the next place, once");
        assertTrue(s.contains("if ($seen[$h]) { continue }"), "windows open before the resume are never moved");
        assertTrue(s.contains("AddSeconds(" + ColdstartController.PLACE_SECONDS + ")"));
    }

    @Test
    void placementScriptWithNothingValidWantsNothing() {
        HashMap<String, ArrayList<String>> windows = new HashMap<>();
        windows.put("Code.exe", new ArrayList<>(List.of("2,0,0,800,600", "")));
        assertTrue(ColdstartController.placeScript(windows).contains("$want = @{ }; "));
    }

    @Test
    void nothingSavedToPlaceStartsNoPowerShell() {
        long t = System.nanoTime();
        ColdstartController.placeWindows(null);
        ColdstartController.placeWindows(new HashMap<>());
        // a PowerShell start costs ~300ms or more; returning well under that means none was spawned
        assertTrue(System.nanoTime() - t < 100_000_000L);
    }
}
