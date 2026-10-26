package com.example.resumeone;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class AppFoldersTest {

    @TempDir File home;

    /** IntelliJ on one folder and VS Code on another, read back the way each app stores them. */
    private HashMap<String, ArrayList<String>> captureTwoIdes(File appData, File coldStart, File extension) throws IOException {
        // VS Code: storage.json lists folders as file:///c%3A/... uris
        String uri = "file:///" + extension.getPath().replace(":", "%3A").replace('\\', '/');
        File storage = new File(appData, "Code\\User\\globalStorage\\storage.json");
        storage.getParentFile().mkdirs();
        Files.writeString(storage.toPath(), "{\"profileAssociations\":{\"workspaces\":{\"" + uri + "\":\"__default__profile__\"}}}");
        // IntelliJ: recentProjects.xml keys by $USER_HOME$ path and keeps the last frame title
        File options = new File(appData, "JetBrains\\IdeaIC2025.2\\options");
        options.mkdirs();
        String key = "$USER_HOME$/" + home.toPath().relativize(coldStart.toPath()).toString().replace('\\', '/');
        Files.writeString(new File(options, "recentProjects.xml").toPath(),
                "<application><component name=\"RecentProjectsManager\"><option name=\"additionalInfo\"><map>"
                        + "<entry key=\"" + key + "\"><value><RecentProjectMetaInfo frameTitle=\"ColdStart \u2013 Main.java\">"
                        + "</RecentProjectMetaInfo></value></entry></map></option></component></application>");
        return AppFolders.resolve(List.of(
                new AppFolders.Window("idea64.exe", "ColdStart \u2013 ColdstartApplication.java"),
                new AppFolders.Window("Code.exe", "\u25cf background.js - ColdStartExtension - Visual Studio Code"),
                new AppFolders.Window("Code.exe", "Welcome - Visual Studio Code"),   // no folder: left out
                new AppFolders.Window("chrome.exe", "ColdStart - Google Chrome")),   // not an IDE
                appData, home.getPath()).folders();
    }

    @Test
    void officeDocumentsResolveFromComFullNames() throws IOException {
        File oneDrive = new File(home, "OneDrive");
        File deck = new File(oneDrive, "Desktop\\Q3 plan+notes.pptx");
        File startup = new File(home, "AppData\\Microsoft\\Excel\\XLSTART\\PERSONAL.XLSB");
        assertTrue(deck.getParentFile().mkdirs() && startup.getParentFile().mkdirs());
        Files.writeString(deck.toPath(), "");
        Files.writeString(startup.toPath(), "");

        assertEquals(deck.getPath(), AppFolders.documentPath(deck.getPath(), null, null));
        // a synced OneDrive file comes back as its web address; '+' stays a '+'
        assertEquals(deck.getPath(), AppFolders.documentPath(
                "https://d.docs.live.net/9a3f0c12be4d7e61/Desktop/Q3%20plan+notes.pptx", oneDrive.getPath(), null));
        assertNull(AppFolders.documentPath("Document1", oneDrive.getPath(), null));   // never saved
        assertNull(AppFolders.documentPath(startup.getPath(), null, null));            // Excel opens it itself

        // the COM line carries the exe path, so resume starts that Office install with the file
        var found = AppFolders.resolve(List.of(new AppFolders.Window("POWERPNT.exe",
                "C:\\Office16\\POWERPNT.EXE", "", deck.getPath())), new File(home, "AppData"), home.getPath());
        assertEquals(List.of(deck.getPath()), found.folders().get("POWERPNT.exe"));
        assertEquals("C:\\Office16\\POWERPNT.EXE", found.exePaths().get("POWERPNT.exe"));
    }

    @Test
    void pathsInTitlesCommandLinesAndExplorerResolve() throws IOException {
        File work = new File(home, "My Work (2026)");
        File txt = new File(work, "a - notes.txt");
        File blend = new File(work, "scene.blend");
        File cut = new File(work, "cut.prproj");
        File unity = new File(home, "Unity Game");
        assertTrue(work.mkdirs() && unity.mkdirs());
        for (File f : List.of(txt, blend, cut)) Files.writeString(f.toPath(), "");

        // spaces, brackets and " - " inside the path itself must survive the cut-back
        assertEquals(txt.getPath(), AppFolders.fileInTitle("*" + txt.getPath() + " - Notepad++"));
        assertEquals(cut.getPath(), AppFolders.fileInTitle("Adobe Premiere Pro 2025 - " + cut.getPath()));
        assertNull(AppFolders.fileInTitle("new 1 - Notepad++"));

        // Blender: bracketed path (old title), then a name matched against recent-files.txt
        assertEquals(blend.getPath(), AppFolders.blendFile(new AppFolders.Window("blender.exe", "Blender* [" + blend.getPath() + "]"), List.of()));
        File recent = new File(home, "AppData\\Blender Foundation\\Blender\\4.2\\config\\recent-files.txt");
        recent.getParentFile().mkdirs();
        Files.writeString(recent.toPath(), blend.getPath() + "\n");
        List<String> known = AppFolders.blenderRecent(new File(home, "AppData\\Blender Foundation\\Blender"));
        assertEquals(blend.getPath(), AppFolders.blendFile(new AppFolders.Window("blender.exe", "* scene - Blender 4.2"), known));
        assertEquals(blend.getPath(), AppFolders.blendFile(new AppFolders.Window("blender.exe", "C:\\b\\blender.exe",
                "\"C:\\b\\blender.exe\" \"" + blend.getPath() + "\"", "Blender"), List.of()));

        // Unity: the Hub's -projectPath, quoted or not
        assertEquals(unity.getPath(), AppFolders.unityProject("Unity.exe -projectpath \"" + unity.getPath() + "\" -useHub"));
        assertEquals(List.of("-projectPath", unity.getPath()), AppFolders.openArgs("Unity.exe", unity.getPath()));

        // Explorer: Shell.Application's url; a bare window name is not a path
        assertEquals(work.getPath(), AppFolders.explorerFolder(work.toPath().toUri().toString()));
        assertNull(AppFolders.explorerFolder("Downloads"));

        // an Explorer folder joins the Files row, so the card shows what resume opens
        HashMap<String, HashSet<String>> apps = new HashMap<>();
        ColdstartController.addFolderApps(apps, Map.of("explorer.exe", new ArrayList<>(List.of(work.getPath()))));
        assertEquals(Set.of("explorer.exe"), apps.get("Files"));
        // the Files note is short; its hover label has every full path, one per line
        Project p = new Project("files", null, apps, new ArrayList<>());
        p.folders = new HashMap<>(Map.of("explorer.exe", new ArrayList<>(List.of(work.getPath(), unity.getPath()))));
        assertEquals("…\\My Work (2026) +1", ColdstartController.trailingNote("Files", p));
        assertEquals(work.getPath() + "\n" + unity.getPath(), ColdstartController.trailingTip("Files", p));
        assertEquals("File Explorer · My Work (2026)",
                ResumePlanner.folderStep("explorer.exe", work.getPath(), null).label);
    }

    @Test
    void eachIdeKeepsItsOwnFolderThroughResume() throws IOException {
        File coldStart = new File(home, "Projects\\ColdStart");
        File extension = new File(home, "Projects\\ColdStartExtension");
        assertTrue(coldStart.mkdirs() && extension.mkdirs());

        HashMap<String, ArrayList<String>> folders = captureTwoIdes(new File(home, "AppData"), coldStart, extension);
        assertEquals(Set.of("idea64.exe", "Code.exe"), folders.keySet());
        assertEquals(coldStart.getPath(), folders.get("idea64.exe").get(0));
        assertTrue(extension.getPath().equalsIgnoreCase(folders.get("Code.exe").get(0)));

        HashMap<String, HashSet<String>> apps = new HashMap<>();
        apps.put("IDE", new HashSet<>(Set.of("Code.exe", "idea64.exe")));
        Project p = new Project("both", coldStart.getPath(), apps, new ArrayList<>());
        p.folders = folders;

        // both running already, on other folders: each still opens its own
        var plan = new ColdstartController().planResume(p, Set.of("Code.exe", "idea64.exe"), Map.of(), new ArrayList<>());
        assertEquals(List.of("VS Code · ColdStartExtension", "IntelliJ IDEA · ColdStart"),
                plan.steps().stream().map(s -> s.label).sorted(Comparator.reverseOrder()).toList());
        assertTrue(ColdstartController.trailingNote("IDE", p).matches("\u2026\\\\ColdStart(Extension)? \\+1"));

        // IntelliJ is already on ColdStart: only VS Code comes back
        plan = new ColdstartController().planResume(p, Set.of("idea64.exe"),
                Map.of("idea64.exe", new ArrayList<>(List.of(coldStart.getPath()))), new ArrayList<>());
        assertEquals(List.of("VS Code · ColdStartExtension"), plan.steps().stream().map(s -> s.label).toList());

        // add mode keeps the saved folder and adds the new one once
        HashMap<String, ArrayList<String>> saved = new HashMap<>(Map.of("Code.exe", new ArrayList<>(List.of("C:\\old"))));
        ColdstartController.mergeFolders(saved, Map.of("Code.exe", new ArrayList<>(List.of("c:\\OLD", "C:\\new"))));
        assertEquals(List.of("C:\\old", "C:\\new"), saved.get("Code.exe"));
    }
}
