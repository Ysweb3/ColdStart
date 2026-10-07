package com.example.resumeone;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which folder each IDE window has open, so a resume brings IntelliJ back on one folder and
 * VS Code on another instead of both on the project's single folderPath.
 *
 * A window title only carries a name ("ResumeOne – Main.java", "x.ts - ResumeOneExtension -
 * Visual Studio Code"), so the name is looked up in the app's own list of folders it has
 * opened. A window whose folder can't be told is left out; that app relaunches as before.
 */
final class AppFolders {

    record Window(String exe, String title) {}

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

    static boolean knows(String exe) {
        String lower = exe.toLowerCase();
        return VSCODE_FAMILY.containsKey(lower) || JETBRAINS.contains(lower);
    }

    /** exe -> folders its windows have open right now, for the apps knows() covers. */
    static HashMap<String, ArrayList<String>> capture() throws IOException {
        File appData = new File(System.getenv("APPDATA"));
        return resolve(captureWindows(), appData, System.getProperty("user.home"));
    }

    static HashMap<String, ArrayList<String>> resolve(List<Window> windows, File appData, String userHome) {
        HashMap<String, ArrayList<String>> out = new HashMap<>();
        Map<String, List<String>> vscodeKnown = new HashMap<>();
        Map<String, String> jetbrainsKnown = null;   // read once, only when a JetBrains window is up
        for (Window w : windows) {
            String lower = w.exe().toLowerCase();
            String folder = null;
            VsCode vs = VSCODE_FAMILY.get(lower);
            if (vs != null) {
                List<String> known = vscodeKnown.computeIfAbsent(vs.dataDir(), dir ->
                        vscodeFolders(new File(appData, dir + "\\User\\globalStorage\\storage.json")));
                folder = vscodeFolder(w.title(), known);
            } else if (JETBRAINS.contains(lower)) {
                if (jetbrainsKnown == null) jetbrainsKnown = jetbrainsProjects(new File(appData, "JetBrains"), userHome);
                folder = jetbrainsFolder(w.title(), jetbrainsKnown);
            }
            if (folder == null) continue;
            ArrayList<String> list = out.computeIfAbsent(w.exe(), k -> new ArrayList<>());
            if (list.stream().noneMatch(folder::equalsIgnoreCase)) list.add(folder);
        }
        return out;
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

    /** Every titled top-level window as (name.exe, title) - one process can own several,
        which is how two VS Code windows on two folders show up. */
    static List<Window> captureWindows() throws IOException {
        // single quotes only, as in captureRunningApps. UTF-8 out, or the en dash JetBrains
        // puts in every title arrives as '?' and the project name can't be split off.
        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command",
                "[Console]::OutputEncoding = [Text.Encoding]::UTF8; " +
                "Add-Type -AssemblyName UIAutomationClient; " +
                "$n = @{}; Get-Process | ForEach-Object { $n[$_.Id] = $_.ProcessName }; " +
                "[Windows.Automation.AutomationElement]::RootElement.FindAll(" +
                "[Windows.Automation.TreeScope]::Children, [Windows.Automation.Condition]::TrueCondition) | " +
                "ForEach-Object { $c = $_.Current; if ($c.Name -and $n[$c.ProcessId]) { $n[$c.ProcessId] + '.exe|' + $c.Name } }");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        List<Window> out = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.replace("\uFEFF", "").split("\\|", 2);
                if (parts.length == 2 && parts[0].toLowerCase().endsWith(".exe")) {
                    out.add(new Window(parts[0].trim(), parts[1].trim()));
                }
            }
        }
        return out;
    }

    private AppFolders() {}
}
