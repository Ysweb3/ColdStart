package com.example.resumeone;

import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.example.resumeone.AppCatalog.*;
import static com.example.resumeone.Browsers.*;

/** What Resume launches and how: one LaunchStep per app or saved folder, decided up front
    without starting anything. Browsers are planned in ColdstartController.planResume, which
    owns the extension sockets their tabs go through, and appended after these. */
final class ResumePlanner {
    private ResumePlanner() {}

    // reg prints "    (Default)    REG_SZ    C:\Program Files\...\chrome.exe" - the path holds
    // spaces, so only the trailing side can be trimmed
    static String parseRegPath(String regOutput) {
        for (String line : regOutput.split("\r?\n")) {
            int marker = line.indexOf("REG_SZ");
            if (marker >= 0) {
                String value = line.substring(marker + "REG_SZ".length()).trim();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }

    // browsers are not on PATH, so `where chrome` finds nothing - windows resolves them through
    // the App Paths registry keys instead. per-user installs (common for chrome and brave) land
    // in HKCU, machine-wide ones in HKLM.
    static String browserPath(String browser) throws IOException {
        String exe = browser == null ? null : BROWSER_EXE.get(brandOf(browser).toLowerCase());
        return exe == null ? null : appPath(exe);
    }

    /** Is "name.exe" in one of the PATH directories - the first place `start name` looks.
        NOFOLLOW_LINKS: Store apps live in WindowsApps as execution-alias reparse points, which
        File.isFile and a following Files.exists both report as missing. */
    static boolean onPath(String exe, String path) {
        if (path == null) return false;
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) continue;
            try {
                if (Files.exists(Path.of(dir.trim().replace("\"", ""), exe), LinkOption.NOFOLLOW_LINKS)) return true;
            } catch (java.nio.file.InvalidPathException badEntry) {
                // a malformed PATH entry is skipped, as cmd skips it
            }
        }
        return false;
    }

    /** The exe's App Paths registration (HKCU, then HKLM) - where `start` looks after PATH. */
    static String appPath(String exe) throws IOException {
        for (String hive : List.of("HKCU", "HKLM")) {
            ProcessBuilder pb = new ProcessBuilder("reg", "query",
                    hive + "\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\" + exe, "/ve");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            String path = parseRegPath(output.toString());
            if (path != null && new File(path).exists()) {
                return path;
            }
        }
        return null;
    }

    // an app that is already open should be left alone rather than opened a second time.
    // a browser we are about to hand urls to is skipped as well - resumeProject launches it with
    // those tabs as arguments, so launching it here too is what creates the duplicate window.
    // browsers with no saved urls are not skipped: nothing else would bring their window back.
    static boolean shouldLaunch(String app, Set<String> running, Set<String> handledBrowsers) {
        // CLI sessions are per-project-folder, not a singleton: a claude window open for
        // some other project must never suppress opening this project's own session.
        // ponytail: only claude can tell which folder it runs in (claudeRunningIn); another CLI
        // already open on this project comes back twice
        if (isCli(app)) {
            return true;
        }
        for (String open : running) {
            if (open.equalsIgnoreCase(app)) {
                return false;
            }
        }
        return !handledBrowsers.contains(app.toLowerCase());
    }

    // a CLI was captured next to WindowsTerminal.exe because it ran inside a terminal tab.
    // launched separately, `start wt` only brings back an empty PowerShell window beside the
    // one the CLI's console opens - so the CLI is launched *through* wt and wt itself is skipped.
    static List<String> cliCommand(String exe, String folder, boolean inTerminal) {
        List<String> command = new ArrayList<>(List.of("cmd.exe", "/c", "start"));
        if (inTerminal) {
            command.add("wt");
            if (folder != null) command.addAll(List.of("-d", folder));
        }
        List<String> resume = CLI_RESUME.get(exe.toLowerCase());
        if (exe.equalsIgnoreCase("claude.exe")) {
            // the agent view lists every conversation for the project, not just the latest one;
            // --cwd scopes it to sessions started under this folder - with no folder it shows them all
            command.addAll(resume);
            if (folder != null) command.addAll(List.of("--cwd", folder));
        } else {
            // the others' "last chat" is the last one in the folder they start in: with no
            // project folder there is none to go back to, so a new one
            command.addAll(folder != null ? resume : resume.subList(0, 1));
        }
        return command;
    }

    @FunctionalInterface
    interface Launch { void start() throws IOException, URISyntaxException, AlreadyOpen, Queued; }

    /** A launch that found nothing to do: every saved tab was already open in that browser. */
    static final class AlreadyOpen extends Exception {
        AlreadyOpen() { super(null, null, false, false); }
    }

    /** A launch handed to an extension that has not connected yet: it opens them when it does. */
    static final class Queued extends Exception {
        Queued() { super(null, null, false, false); }
    }

    /** One process launch: the row's label, the command it shows, and how it went. */
    static final class LaunchStep {
        /** SKIPPED: ran, but there was nothing to open - shown as already open, not launched */
        enum State { QUEUED, LAUNCHING, DONE, SKIPPED, FAILED }
        final String label, command;
        final boolean browser;
        final Launch launch;
        volatile State state = State.QUEUED;
        volatile String error;
        /** DONE, but its tabs are still waiting for the browser's extension to connect */
        volatile boolean waiting;
        /** a browser's tabs its extension says it opened; -1 = no count (command line, an older
            extension), so every saved url counts */
        volatile int opened = -1;

        LaunchStep(String label, String command, boolean browser, Launch launch) {
            this.label = label;
            this.command = command;
            this.browser = browser;
            this.launch = launch;
        }

        /** one app failing to launch (bad folder, missing exe, ...) must not abort the rest */
        void run() {
            state = State.LAUNCHING;
            try {
                launch.start();
                state = State.DONE;
            } catch (AlreadyOpen ex) {
                state = State.SKIPPED;
            } catch (Queued ex) {
                waiting = true;
                state = State.DONE;
            } catch (IOException | URISyntaxException ex) {
                error = ex.getMessage();
                state = State.FAILED;
                System.err.println("failed to launch " + label + ": " + error);
            }
        }

        String logLine() {
            return switch (state) {
                case DONE -> waiting ? label + ": tabs wait for its extension to connect" : label + " → " + command;
                case SKIPPED -> label + ": every tab already open";
                default -> label + " failed: " + error;
            };
        }
    }

    record ResumePlan(List<LaunchStep> steps, List<String> skipped) {}

    /** Everything one press of Resume produced; 1e and 1l render from it. */
    static final class ResumeRun {
        final Project project;
        final List<String> log = new CopyOnWriteArrayList<>();
        volatile ResumePlan plan = new ResumePlan(List.of(), List.of());
        long tookMs;
        /** Stop was pressed with steps still queued: 1l says STOPPED and offers the rest. */
        volatile boolean stopped;
        /** The capture before planning failed, so nothing was planned or launched. */
        volatile String error;

        ResumeRun(Project project) { this.project = project; }

        int total()     { return plan.steps().size(); }
        /** finished fine, including a browser that had every tab open already */
        long done()     { return plan.steps().stream().filter(s -> s.state == LaunchStep.State.DONE || s.state == LaunchStep.State.SKIPPED).count(); }
        long failed()   { return plan.steps().stream().filter(s -> s.state == LaunchStep.State.FAILED).count(); }
        long queued()   { return plan.steps().stream().filter(s -> s.state == LaunchStep.State.QUEUED).count(); }
        long appsDone() { return plan.steps().stream().filter(s -> s.state == LaunchStep.State.DONE && !s.browser).count(); }

        /** Left alone: apps the plan found running, plus browsers that turned out to have every tab. */
        List<String> alreadyOpen() {
            List<String> all = new ArrayList<>(plan.skipped());
            plan.steps().stream().filter(s -> s.state == LaunchStep.State.SKIPPED).forEach(s -> all.add(s.label));
            return all;
        }
    }

    /** What the project's saved IDEs have open right now, so resume skips only those folders.
        Empty when no folder was saved or the listing failed: asking VS Code or a JetBrains IDE
        for a folder it already has open just brings that window forward. */
    static Map<String, ArrayList<String>> liveFolders(Project projectObj) {
        if (projectObj.folders == null || projectObj.folders.isEmpty()) return Map.of();
        try {
            return AppFolders.capture().folders();
        } catch (IOException ex) {
            return Map.of();
        }
    }

    /** The apps half of planResume: every captured app but the browsers handed their tabs,
        in CATEGORY_ORDER. Nothing starts until a step runs. */
    static ResumePlan appPlan(Project projectObj, Set<String> running, Map<String, ArrayList<String>> openFolders,
                              Set<String> handledBrowsers, List<String> log) {
        boolean cliInTerminal = hasCli(projectObj) && hasApp(projectObj, "WindowsTerminal.exe");
        List<LaunchStep> steps = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        // CATEGORY_ORDER only, so the rows walk the card top to bottom. Other is captured and
        // shown but never relaunched - the card says so, and now it is true.
        String folder = projectObj.folderPath;
        for (String category : CATEGORY_ORDER) {
            HashSet<String> bucket = projectObj.apps.get(category);
            if (bucket == null) continue;
            for (String app : new TreeSet<>(bucket)) {
                // a browser handed its tabs above is coming back, not "already open"
                if (handledBrowsers.contains(app.toLowerCase())) {
                    log.add("skipping " + app + ": opens with its tabs");
                    continue;
                }
                List<String> dirs = projectObj.folders == null ? null : projectObj.folders.get(app);
                if (dirs != null && !dirs.isEmpty()) {
                    // one window per saved folder: the app being up only rules out the folders
                    // it already has open, not the rest
                    List<String> open = openFolders.getOrDefault(app, new ArrayList<>());
                    boolean any = false;
                    for (String dir : dirs) {
                        if (open.stream().anyMatch(dir::equalsIgnoreCase)) {
                            log.add("skipping " + app + ": already open on " + dir);
                            continue;
                        }
                        steps.add(folderStep(app, dir, projectObj.exePaths == null ? null : projectObj.exePaths.get(app)));
                        any = true;
                    }
                    if (!any) skipped.add(app);
                    continue;
                }
                if (!shouldLaunch(app, running, handledBrowsers)) {
                    skipped.add(app);
                    log.add("skipping (already open or handled): " + app);
                    continue;
                }
                String appName = app.replace(".exe", "");

                if (isCli(app)) {
                    if (appName.equalsIgnoreCase("claude") && folder != null && claudeRunningIn(folder)) {
                        skipped.add(app);
                        log.add("skipping claude: already running in " + folder);
                        continue;
                    }
                    List<String> cmd = cliCommand(app, folder, cliInTerminal);
                    // open the CLI on its last chat, in the project folder when there is one
                    steps.add(new LaunchStep(appName(app), String.join(" ", cmd.subList(2, cmd.size())), false, () ->
                            new ProcessBuilder(cmd).directory(folder == null ? null : new File(folder)).start()));

                } else if (appName.equalsIgnoreCase("Code")) {
                    // open VS Code in the project folder; with none it reopens its last
                    // workspace rather than silently not coming back
                    List<String> cmd = new ArrayList<>(List.of("cmd.exe", "/c", "code"));
                    if (folder != null) cmd.add(folder);
                    steps.add(new LaunchStep(appName(app), folder == null ? "code" : "code " + folder, false, () ->
                            new ProcessBuilder(cmd).start()));

                } else if (appName.equalsIgnoreCase("WindowsTerminal")) {
                    if (cliInTerminal) {
                        log.add("skipping wt: the CLIs launch inside it");
                        continue;
                    }
                    // WindowsTerminal.exe is the process name, but "wt" is the actual
                    // launch command - "start WindowsTerminal" can't resolve it
                    steps.add(new LaunchStep(appName(app), "start wt", false, () ->
                            new ProcessBuilder("cmd.exe", "/c", "start", "wt").start()));

                } else if (SHELL_APPS.containsKey(app.toLowerCase())) {
                    // a Store app with no exe on PATH or in App Paths: started by its app id,
                    // the way the Start menu does it - or a launcher by its url
                    String target = SHELL_APPS.get(app.toLowerCase());
                    steps.add(new LaunchStep(appName(app), "start " + appName(app), false, () ->
                            new ProcessBuilder("explorer.exe", target).start()));

                } else {
                    // no folder needed: this used to sit under the folderPath guard too, so a
                    // project without a folder relaunched nothing at all
                    // `start` reports nothing back when windows cannot find the exe - it shows its
                    // own dialog and the step would read as launched - so look first, the way
                    // start does: PATH, then the App Paths keys
                    String exePath = projectObj.exePaths == null ? null : projectObj.exePaths.get(app);
                    // a desktop twin's name finds its CLI on PATH (codex.exe for Codex.exe)
                    boolean twin = TWINS.values().stream().anyMatch(t -> t.get(0).equalsIgnoreCase(app));
                    steps.add(new LaunchStep(appName(app), "start " + appName, false, () -> {
                        if (twin || !onPath(app, System.getenv("PATH")) && appPath(app) == null) {
                            // not findable by name (most apps moved out of Other): the exe the
                            // last checkpoint saw it run from
                            if (exePath == null || !new File(exePath).isFile()) throw new IOException("cannot find " + app);
                            // a Store app's exe only runs with its package's identity: by app id
                            String storeId = storeAppId(exePath);
                            if (storeId != null) {
                                new ProcessBuilder("explorer.exe", "shell:AppsFolder\\" + storeId).start();
                                return;
                            }
                            new ProcessBuilder(exePath).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                            return;
                        }
                        // the "" is start's window title: without it, a name with a space
                        // ("Adobe Premiere Pro") arrives quoted and start takes it as the title,
                        // opening an empty console instead of the app
                        new ProcessBuilder("cmd.exe", "/c", "start", "", appName).start();
                    }));
                }
            }
        }
        return new ResumePlan(steps, skipped);
    }

    /** Opens one saved folder or file in the app that had it: a VS Code build through its own
        command, anything else from the exe it ran from last time (the same Unity version, a
        Blender that is not on PATH), or else through `start` like any other app. */
    static LaunchStep folderStep(String app, String dir, String exePath) {
        String label = appName(app) + " · " + new File(dir).getName();
        AppFolders.VsCode vs = AppFolders.VSCODE_FAMILY.get(app.toLowerCase());
        String name = vs != null ? vs.cli() : app.replaceFirst("(?i)\\.exe$", "");
        List<String> args = AppFolders.openArgs(app, dir);
        return new LaunchStep(label, name + " " + String.join(" ", args), false, () -> {
            // moved or deleted since: VS Code would open an empty window and read as restored
            if (!new File(dir).exists()) throw new IOException("no longer there: " + dir);
            List<String> cmd = new ArrayList<>();
            if (vs != null) {
                cmd.addAll(List.of("cmd.exe", "/c", vs.cli()));
            } else if (exePath != null && new File(exePath).isFile()) {
                cmd.add(exePath);
            } else if (onPath(app, System.getenv("PATH")) || appPath(app) != null) {
                cmd.addAll(List.of("cmd.exe", "/c", "start", "", name));
            } else {
                throw new IOException("cannot find " + app);
            }
            cmd.addAll(args);
            // nothing reads the child's output, and a chatty one (Blender logs to stdout)
            // would stall on a full pipe
            new ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        });
    }

    // every running claude writes ~/.claude/sessions/<pid>.json with its cwd. a live one for
    // this folder (that is not a background one) means its window is already up, so opening
    // another would only duplicate it.
    static boolean claudeRunningIn(String folderPath) {
        return claudeRunningIn(folderPath, new File(System.getProperty("user.home"), ".claude/sessions"));
    }

    static boolean claudeRunningIn(String folderPath, File sessionsDir) {
        File[] files = sessionsDir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) return false;
        for (File f : files) {
            try (Reader r = new FileReader(f)) {
                Map<String, Object> session = new Gson().fromJson(r, Map.class);
                Object cwd = session.get("cwd");
                Object pid = session.get("pid");
                // a background session ("kind":"bg") outlives the terminal it started in and has
                // no window, so a leftover one from yesterday must not stand in for an open claude
                if ("bg".equals(session.get("kind"))) continue;
                // the file outlives a crash, so the pid has to be checked too
                if (cwd != null && pid != null && folderPath.equalsIgnoreCase(cwd.toString())
                        && ProcessHandle.of(((Number) pid).longValue()).map(ProcessHandle::isAlive).orElse(false)) {
                    return true;
                }
            } catch (IOException | RuntimeException ex) {
                // a half-written or odd file is not a reason to skip the launch
            }
        }
        return false;
    }
}
