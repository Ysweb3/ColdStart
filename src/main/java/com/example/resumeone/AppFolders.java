package com.example.resumeone;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which folder or file each app window has open, so a resume brings IntelliJ back on one
 * folder and VS Code on another instead of both on the project's single folderPath.
 *
 * IDEs only put a name in the title ("ResumeOne – Main.java", "x.ts - ResumeOneExtension -
 * Visual Studio Code"), so the name is looked up in the app's own list of folders it has
 * opened. Notepad++, Premiere, After Effects and older Blender put the whole path there,
 * Unity has it on its command line, and Explorer, Office and Photoshop/Illustrator are asked
 * over COM. A window whose folder can't be told is left out; that app relaunches as before.
 */
final class AppFolders {

    /** exePath and commandLine are "" when unknown; the command line is only read for the
        apps that keep their document there (Unity, Blender). */
    record Window(String exe, String exePath, String commandLine, String place, String title) {
        Window(String exe, String exePath, String commandLine, String title) { this(exe, exePath, commandLine, "", title); }
        Window(String exe, String title) { this(exe, "", "", title); }
    }

    /** "state,x,y,width,height" from UI Automation: state 0 normal, 1 maximized. Minimized
        windows and windows with no WindowPattern (the taskbar) are not placed. */
    static final Pattern PLACE = Pattern.compile("[01],-?\\d+,-?\\d+,\\d+,\\d+");

    /** What a capture found: exe -> its open folders/files, exe -> the exe's full path
        (Unity installs per version, Blender is rarely on PATH, so resume starts that file),
        and exe -> where each of its windows sat (see PLACE). */
    record Capture(HashMap<String, ArrayList<String>> folders, HashMap<String, String> exePaths,
                   HashMap<String, ArrayList<String>> windows) {}

    /** a VS Code build: its folder under %APPDATA% and the command that opens a folder in it */
    record VsCode(String dataDir, String cli) {}

    static final Map<String, VsCode> VSCODE_FAMILY = Map.of(
            "code.exe", new VsCode("Code", "code"),
            "code - insiders.exe", new VsCode("Code - Insiders", "code-insiders"),
            "cursor.exe", new VsCode("Cursor", "cursor"),
            "windsurf.exe", new VsCode("Windsurf", "windsurf"));

    // every one keeps %APPDATA%\JetBrains\<product><version>\options\recentProjects.xml
    static final List<String> JETBRAINS = List.of("idea64.exe", "pycharm64.exe", "webstorm64.exe",
            "phpstorm64.exe", "clion64.exe", "rider64.exe", "goland64.exe", "rubymine64.exe",
            "dataspell64.exe");

    // the whole path is in the title: "*C:\x\a.txt - Notepad++", "Adobe Premiere Pro 2025 -
    // C:\x\cut.prproj", "Blender* [C:\x\scene.blend]" (Blender gets more tries, see blendFile)
    static final List<String> TITLE_PATH = List.of("notepad++.exe", "adobe premiere pro.exe", "afterfx.exe");
    static final String BLENDER = "blender.exe", UNITY = "unity.exe", EXPLORER = "explorer.exe";

    // asked over COM for every open document (see captureWindows); each comes back as its own
    // line with the document's FullName standing in for the title
    static final List<String> COM_DOCS = List.of("winword.exe", "excel.exe", "powerpnt.exe",
            "photoshop.exe", "illustrator.exe");

    static boolean knows(String exe) {
        String lower = exe.toLowerCase();
        return VSCODE_FAMILY.containsKey(lower) || JETBRAINS.contains(lower) || TITLE_PATH.contains(lower)
                || COM_DOCS.contains(lower) || List.of(BLENDER, UNITY, EXPLORER).contains(lower);
    }

    /** The arguments that reopen dir in app: Unity takes it as -projectPath, the rest bare. */
    static List<String> openArgs(String app, String dir) {
        return UNITY.equalsIgnoreCase(app) ? List.of("-projectPath", dir) : List.of(dir);
    }

    /** What every open window of a known app has open right now. */
    static Capture capture() throws IOException {
        File appData = new File(System.getenv("APPDATA"));
        return resolve(captureWindows(), appData, System.getProperty("user.home"));
    }

    static Capture resolve(List<Window> windows, File appData, String userHome) {
        HashMap<String, ArrayList<String>> out = new HashMap<>();
        HashMap<String, String> exePaths = new HashMap<>();
        HashMap<String, ArrayList<String>> places = new HashMap<>();
        Map<String, List<String>> vscodeKnown = new HashMap<>();
        Map<String, String> jetbrainsKnown = null;   // read once, only when a JetBrains window is up
        List<String> blendRecent = null;
        for (Window w : windows) {
            String lower = w.exe().toLowerCase();
            // every windowed app's, not only those with a folder: an app the user moves out of
            // Other is usually on neither PATH nor App Paths, and this is how resume finds it
            if (!w.exePath().isEmpty()) exePaths.put(w.exe(), w.exePath());
            if (PLACE.matcher(w.place()).matches()) places.computeIfAbsent(w.exe(), k -> new ArrayList<>()).add(w.place());
            String folder = null;
            VsCode vs = VSCODE_FAMILY.get(lower);
            if (vs != null) {
                List<String> known = vscodeKnown.computeIfAbsent(vs.dataDir(), dir ->
                        vscodeFolders(new File(appData, dir + "\\User\\globalStorage\\storage.json")));
                folder = vscodeFolder(w.title(), known);
            } else if (JETBRAINS.contains(lower)) {
                if (jetbrainsKnown == null) jetbrainsKnown = jetbrainsProjects(new File(appData, "JetBrains"), userHome);
                folder = jetbrainsFolder(w.title(), jetbrainsKnown);
            } else if (TITLE_PATH.contains(lower)) {
                folder = fileInTitle(w.title());
            } else if (lower.equals(BLENDER)) {
                if (blendRecent == null) blendRecent = blenderRecent(new File(appData, "Blender Foundation\\Blender"));
                folder = blendFile(w, blendRecent);
            } else if (lower.equals(UNITY)) {
                folder = unityProject(w.commandLine());
            } else if (lower.equals(EXPLORER)) {
                folder = explorerFolder(w.title());
            } else if (COM_DOCS.contains(lower)) {
                folder = documentPath(w.title(), System.getenv("OneDrive"), System.getenv("OneDriveCommercial"));
            }
            if (folder == null) continue;
            ArrayList<String> list = out.computeIfAbsent(w.exe(), k -> new ArrayList<>());
            if (list.stream().noneMatch(folder::equalsIgnoreCase)) list.add(folder);
        }
        return new Capture(out, exePaths, places);
    }

    // == Path in the title or on the command line ===========================

    private static final Pattern DRIVE = Pattern.compile("[A-Za-z]:\\\\");

    /** The longest existing file path in the title. A path can hold spaces, dashes and
        brackets, so each one starting at a drive letter is cut back a word at a time until
        what is left is a file - with the "]" or " -" that followed it trimmed off. */
    static String fileInTitle(String title) {
        Matcher m = DRIVE.matcher(title);
        while (m.find()) {
            String rest = title.substring(m.start());
            for (int end = rest.length(); end > 3; end = rest.lastIndexOf(' ', end - 1)) {
                String raw = rest.substring(0, end);
                for (String candidate : List.of(raw, raw.replaceAll("[\\])\\s-]+$", ""))) {
                    if (new File(candidate).isFile()) return candidate;
                }
            }
        }
        return null;
    }

    private static final Pattern BLEND_ARG = Pattern.compile("\"([^\"]+\\.blend)\"|(\\S+\\.blend)\\b", Pattern.CASE_INSENSITIVE);

    /** Blender changed its title across versions (path in brackets, then name only), so: a
        .blend it was started with, then a path in the title, then a recent file the title names.
        ponytail: two recent files with one name resolve to the newest version's first; a
        Blender add-on reporting bpy.data.filepath would be exact. */
    static String blendFile(Window w, List<String> recent) {
        Matcher arg = BLEND_ARG.matcher(w.commandLine());
        while (arg.find()) {
            String path = arg.group(1) != null ? arg.group(1) : arg.group(2);
            if (new File(path).isFile()) return path;
        }
        String inTitle = fileInTitle(w.title());
        if (inTitle != null) return inTitle;
        for (String part : w.title().split(" [-\u2013] |[\\[\\]()]")) {
            String name = part.replace("*", "").trim();
            if (name.isEmpty()) continue;
            for (String path : recent) {
                String file = new File(path).getName();
                if (name.equalsIgnoreCase(file) || name.equalsIgnoreCase(file.replaceFirst("(?i)\\.blend$", ""))) {
                    return path;
                }
            }
        }
        return null;
    }

    /** Every version's config\recent-files.txt, newest version first, most recent file first. */
    static List<String> blenderRecent(File blenderDir) {
        List<String> out = new ArrayList<>();
        File[] versions = blenderDir.listFiles(File::isDirectory);
        if (versions == null) return out;
        Arrays.sort(versions, Comparator.comparing(File::getName).reversed());
        for (File version : versions) {
            try {
                for (String line : Files.readAllLines(new File(version, "config\\recent-files.txt").toPath())) {
                    if (!line.isBlank() && new File(line.trim()).isFile()) out.add(line.trim());
                }
            } catch (IOException ex) {
                // a version that never saved a file has none
            }
        }
        return out;
    }

    private static final Pattern PROJECT_PATH = Pattern.compile("(?i)-projectpath\\s+(?:\"([^\"]+)\"|(\\S+))");

    /** Unity's title is "Project - Scene - Platform - Unity 6", but the Hub starts it with
        -projectPath, so the folder is on the command line. */
    static String unityProject(String commandLine) {
        Matcher m = PROJECT_PATH.matcher(commandLine);
        if (!m.find()) return null;
        String dir = m.group(1) != null ? m.group(1) : m.group(2);
        return new File(dir).isDirectory() ? new File(dir).getPath() : null;
    }

    /** Shell.Application hands each Explorer window (or tab) over as a file:/// url; a window
        whose title shows the full path (a folder option) counts too. Home, This PC and other
        virtual folders have no path and are left out. */
    static String explorerFolder(String title) {
        String path = title;
        if (title.startsWith("file:")) {
            try {
                path = Paths.get(URI.create(title)).toString();
            } catch (RuntimeException ex) {
                return null;
            }
        }
        File f = new File(path);
        return f.isAbsolute() && f.isDirectory() ? f.getPath() : null;
    }

    // == Office and Adobe, over COM =========================================
    //   FullName is a path, a bare name for an unsaved "Document1" (left out), or for a file in
    //   a synced OneDrive folder its web address - the same file is on disk under the OneDrive
    //   root, path for path.

    private static final Pattern ONEDRIVE_PERSONAL = Pattern.compile("(?i)https://d\\.docs\\.live\\.net/[0-9a-f]+/(.+)");
    private static final Pattern ONEDRIVE_WORK = Pattern.compile("(?i)https://[^/]+-my\\.sharepoint\\.com/personal/[^/]+/Documents/(.+)");

    static String documentPath(String fullName, String oneDrive, String oneDriveWork) {
        String path = fullName;
        Matcher personal = ONEDRIVE_PERSONAL.matcher(fullName), work = ONEDRIVE_WORK.matcher(fullName);
        if (personal.matches() && oneDrive != null) path = oneDrive + "\\" + urlPath(personal.group(1));
        else if (work.matches() && oneDriveWork != null) path = oneDriveWork + "\\" + urlPath(work.group(1));
        File f = new File(path);
        // Excel's startup folder (PERSONAL.XLSB) opens by itself, hidden; it is not the user's work
        if (!f.isAbsolute() || !f.isFile() || path.toUpperCase().contains("\\XLSTART\\")) return null;
        return f.getPath();
    }

    private static String urlPath(String s) {
        // a literal '+' is part of the name, not an encoded space
        return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8).replace('/', '\\');
    }

    // == VS Code family =====================================================
    //   title: "[● ]file - folder[ - profile] - Visual Studio Code", or "name (Workspace)" for a
    //   .code-workspace. storage.json holds a file:/// uri for every folder ever opened.

    private static final Pattern FILE_URI = Pattern.compile("\"(file:///[^\"]+)\"");

    /** Every local folder or .code-workspace file storage.json mentions, as a path. */
    static List<String> vscodeFolders(File storageJson) {
        List<String> out = new ArrayList<>();
        String text;
        try {
            text = Files.readString(storageJson.toPath());
        } catch (IOException ex) {
            return out;   // not installed, or never run
        }
        Matcher m = FILE_URI.matcher(text);
        while (m.find()) {
            String path;
            try {
                path = Paths.get(URI.create(m.group(1))).toString();
            } catch (RuntimeException ex) {
                continue;
            }
            File f = new File(path);
            boolean usable = f.isDirectory() || (f.isFile() && path.endsWith(".code-workspace"));
            if (usable && out.stream().noneMatch(path::equalsIgnoreCase)) out.add(path);
        }
        return out;
    }

    /** The known folder this title names. Segments are read from the app name backwards, so
        the folder wins over an open file that happens to share its name.
        ponytail: two known folders with one name resolve to the first listed; read
        state.vscdb's recency order if that ever bites. */
    static String vscodeFolder(String title, List<String> known) {
        String[] parts = title.split(" - ");
        for (int i = parts.length - 2; i >= 0; i--) {
            String part = parts[i].trim();
            for (String path : known) {
                if (part.equalsIgnoreCase(vscodeName(path))) return path;
            }
        }
        return null;
    }

    static String vscodeName(String path) {
        String name = new File(path).getName();
        return name.endsWith(".code-workspace")
                ? name.substring(0, name.length() - ".code-workspace".length()) + " (Workspace)"
                : name;
    }

    // == JetBrains ==========================================================
    //   title: "ResumeOne – HelloApplication.java" (an en dash), or "name [C:\path] – file" on
    //   older builds. recentProjects.xml keys each project by its path and keeps the title its
    //   window last had, which carries the project name when that differs from the folder's.

    private static final Pattern BRACKET_PATH = Pattern.compile("\\[([A-Za-z]:\\\\[^\\]]*)]");
    private static final Pattern FRAME_TITLE = Pattern.compile("frameTitle=\"([^\"]*)\"");

    /** path -> last frame title ("" when none), from every JetBrains product's recentProjects.xml */
    static Map<String, String> jetbrainsProjects(File jetbrainsDir, String userHome) {
        Map<String, String> out = new LinkedHashMap<>();
        File[] products = jetbrainsDir.listFiles(File::isDirectory);
        if (products == null) return out;
        for (File product : products) {
            String xml;
            try {
                xml = Files.readString(new File(product, "options\\recentProjects.xml").toPath());
            } catch (IOException ex) {
                continue;
            }
            String[] entries = xml.split("<entry key=\"");
            for (int i = 1; i < entries.length; i++) {
                String entry = entries[i];
                int end = entry.indexOf('"');
                if (end < 0) continue;
                String path = new File(unescape(entry.substring(0, end)).replace("$USER_HOME$", userHome)).getPath();
                if (!new File(path).isDirectory()) continue;
                Matcher m = FRAME_TITLE.matcher(entry);
                out.putIfAbsent(path, m.find() ? unescape(m.group(1)) : "");
            }
        }
        return out;
    }

    static String jetbrainsFolder(String title, Map<String, String> known) {
        Matcher bracket = BRACKET_PATH.matcher(title);
        if (bracket.find() && new File(bracket.group(1)).isDirectory()) return bracket.group(1);
        String name = projectName(title);
        if (name.isEmpty()) return null;
        // the saved title names the project even when the folder is called something else
        for (Map.Entry<String, String> e : known.entrySet()) {
            if (name.equals(projectName(e.getValue()))) return e.getKey();
        }
        for (String path : known.keySet()) {
            if (name.equalsIgnoreCase(new File(path).getName())) return path;
        }
        return null;
    }

    static String projectName(String title) {
        return title.split(" \u2013 ", 2)[0].trim();
    }

    private static String unescape(String xml) {
        return xml.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&amp;", "&");
    }

    // == Window listing =====================================================

    /** Every titled top-level window - one process can own several, which is how two VS Code
        windows on two folders show up - plus one line per Explorer window from Shell.Application,
        with its file:/// url standing in for the title. */
    static List<Window> captureWindows() throws IOException {
        // single quotes only, as in captureRunningApps; fields are tab-separated ([char]9, as
        // "`t" would need double quotes) since titles and command lines can hold '|'. UTF-8 out,
        // or the en dash JetBrains puts in every title arrives as '?' and can't be split on.
        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command",
                "[Console]::OutputEncoding = [Text.Encoding]::UTF8; $t = [char]9; " +
                "Add-Type -AssemblyName UIAutomationClient; " +
                "$p = @{}; Get-Process | ForEach-Object { $p[$_.Id] = $_ }; " +
                "$c = @{}; Get-CimInstance Win32_Process -Filter 'Name=''Unity.exe'' or Name=''blender.exe''' | " +
                "ForEach-Object { $c[[int]$_.ProcessId] = $_.CommandLine }; " +
                "[Windows.Automation.AutomationElement]::RootElement.FindAll(" +
                "[Windows.Automation.TreeScope]::Children, [Windows.Automation.Condition]::TrueCondition) | " +
                "ForEach-Object { $e = $_.Current; $q = $p[$e.ProcessId]; if ($e.Name -and $q) { " +
                // where the window sits, for resume to put it back (AppFolders.PLACE)
                "$s = ''; try { $s = [int]$_.GetCurrentPattern([Windows.Automation.WindowPattern]::Pattern).Current.WindowVisualState } catch { }; " +
                "$r = $e.BoundingRectangle; $w = '' + $s + ',' + [int]$r.X + ',' + [int]$r.Y + ',' + [int]$r.Width + ',' + [int]$r.Height; " +
                "$q.ProcessName + '.exe' + $t + $q.Path + $t + $c[$e.ProcessId] + $t + $w + $t + $e.Name } }; " +
                "(New-Object -ComObject Shell.Application).Windows() | " +
                "ForEach-Object { 'explorer.exe' + $t + $t + $t + $t + $_.LocationURL }; " +
                // only apps already running: GetActiveObject never starts one. Adobe may not
                // register there, so it gets New-Object, which attaches to the running copy.
                // ponytail: an Adobe app quitting at that instant would start again; drop the
                // New-Object fallback if that is ever seen
                "foreach ($a in @(@('WINWORD','Word.Application','Documents'), @('EXCEL','Excel.Application','Workbooks'), " +
                "@('POWERPNT','PowerPoint.Application','Presentations'), @('Photoshop','Photoshop.Application','Documents'), " +
                "@('Illustrator','Illustrator.Application','Documents'))) { " +
                "$q = Get-Process -Name $a[0] -ErrorAction SilentlyContinue | Select-Object -First 1; if (-not $q) { continue }; " +
                "$o = $null; try { $o = [Runtime.InteropServices.Marshal]::GetActiveObject($a[1]) } catch { }; " +
                "if (-not $o -and $a[0] -in @('Photoshop','Illustrator')) { try { $o = New-Object -ComObject $a[1] } catch { } }; " +
                "if ($o) { foreach ($d in $o.($a[2])) { try { $q.ProcessName + '.exe' + $t + $q.Path + $t + $t + $t + $d.FullName } catch { } } } }");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        // an Office app with a modal dialog up can leave a COM call waiting; the checkpoint
        // must not wait with it. Killed, the stream ends and what was read so far is kept.
        CompletableFuture.runAsync(process::destroyForcibly,
                CompletableFuture.delayedExecutor(15, TimeUnit.SECONDS));
        List<Window> out = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] f = line.replace("\uFEFF", "").split("\t", 5);
                if (f.length == 5 && f[0].toLowerCase().endsWith(".exe")) {
                    out.add(new Window(f[0].trim(), f[1].trim(), f[2].trim(), f[3].trim(), f[4].trim()));
                }
            }
        }
        return out;
    }

    private AppFolders() {}
}
