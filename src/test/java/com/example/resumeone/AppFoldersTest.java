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
    private HashMap<String, ArrayList<String>> captureTwoIdes(File appData, File resumeOne, File extension) throws IOException {
        // VS Code: storage.json lists folders as file:///c%3A/... uris
        String uri = "file:///" + extension.getPath().replace(":", "%3A").replace('\\', '/');
        File storage = new File(appData, "Code\\User\\globalStorage\\storage.json");
        storage.getParentFile().mkdirs();
        Files.writeString(storage.toPath(), "{\"profileAssociations\":{\"workspaces\":{\"" + uri + "\":\"__default__profile__\"}}}");
        // IntelliJ: recentProjects.xml keys by $USER_HOME$ path and keeps the last frame title
        File options = new File(appData, "JetBrains\\IdeaIC2025.2\\options");
        options.mkdirs();
        String key = "$USER_HOME$/" + home.toPath().relativize(resumeOne.toPath()).toString().replace('\\', '/');
        Files.writeString(new File(options, "recentProjects.xml").toPath(),
                "<application><component name=\"RecentProjectsManager\"><option name=\"additionalInfo\"><map>"
                        + "<entry key=\"" + key + "\"><value><RecentProjectMetaInfo frameTitle=\"ResumeOne \u2013 Main.java\">"
                        + "</RecentProjectMetaInfo></value></entry></map></option></component></application>");
        return AppFolders.resolve(List.of(
                new AppFolders.Window("idea64.exe", "ResumeOne \u2013 HelloApplication.java"),
                new AppFolders.Window("Code.exe", "\u25cf background.js - ResumeOneExtension - Visual Studio Code"),
                new AppFolders.Window("Code.exe", "Welcome - Visual Studio Code"),   // no folder: left out
                new AppFolders.Window("chrome.exe", "ResumeOne - Google Chrome")),   // not an IDE
                appData, home.getPath()).folders();
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
        HelloController.addFolderApps(apps, Map.of("explorer.exe", new ArrayList<>(List.of(work.getPath()))));
        assertEquals(Set.of("explorer.exe"), apps.get("Files"));
        assertEquals("File Explorer · My Work (2026)",
                HelloController.folderStep("explorer.exe", work.getPath(), null).label);
    }

    @Test
    void eachIdeKeepsItsOwnFolderThroughResume() throws IOException {
        File resumeOne = new File(home, "Projects\\ResumeOne");
        File extension = new File(home, "Projects\\ResumeOneExtension");
        assertTrue(resumeOne.mkdirs() && extension.mkdirs());

        HashMap<String, ArrayList<String>> folders = captureTwoIdes(new File(home, "AppData"), resumeOne, extension);
        assertEquals(Set.of("idea64.exe", "Code.exe"), folders.keySet());
        assertEquals(resumeOne.getPath(), folders.get("idea64.exe").get(0));
        assertTrue(extension.getPath().equalsIgnoreCase(folders.get("Code.exe").get(0)));

        HashMap<String, HashSet<String>> apps = new HashMap<>();
        apps.put("IDE", new HashSet<>(Set.of("Code.exe", "idea64.exe")));
        Project p = new Project("both", resumeOne.getPath(), apps, new ArrayList<>());
        p.folders = folders;

        // both running already, on other folders: each still opens its own
        var plan = new HelloController().planResume(p, Set.of("Code.exe", "idea64.exe"), Map.of(), new ArrayList<>());
        assertEquals(List.of("VS Code · ResumeOneExtension", "IntelliJ IDEA · ResumeOne"),
                plan.steps().stream().map(s -> s.label).sorted(Comparator.reverseOrder()).toList());
        assertTrue(HelloController.trailingNote("IDE", p).matches("\u2026\\\\ResumeOne(Extension)? \\+1"));

        // IntelliJ is already on ResumeOne: only VS Code comes back
        plan = new HelloController().planResume(p, Set.of("idea64.exe"),
                Map.of("idea64.exe", new ArrayList<>(List.of(resumeOne.getPath()))), new ArrayList<>());
        assertEquals(List.of("VS Code · ResumeOneExtension"), plan.steps().stream().map(s -> s.label).toList());

        // add mode keeps the saved folder and adds the new one once
        HashMap<String, ArrayList<String>> saved = new HashMap<>(Map.of("Code.exe", new ArrayList<>(List.of("C:\\old"))));
        HelloController.mergeFolders(saved, Map.of("Code.exe", new ArrayList<>(List.of("c:\\OLD", "C:\\new"))));
        assertEquals(List.of("C:\\old", "C:\\new"), saved.get("Code.exe"));
    }
}
