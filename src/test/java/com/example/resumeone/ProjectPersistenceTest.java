package com.example.resumeone;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@Tag("priority-medium")
class ProjectPersistenceTest {

    private final Gson gson = new Gson();

    @Test
    void gsonRoundTripPreservesAllFieldsForSampleProject() {
        HashMap<String, HashSet<String>> apps = new HashMap<>();
        apps.put("Browser", new HashSet<>(java.util.List.of("chrome.exe", "brave.exe")));
        apps.put("IDE", new HashSet<>(java.util.List.of("Code.exe")));
        ArrayList<String> urls = new ArrayList<>(java.util.List.of("https://github.com/Ysweb3/ResumeOne"));
        Project original = new Project("Sample Project", "C:\\dev\\sample", apps, urls);

        String json = gson.toJson(original);
        Project restored = gson.fromJson(json, Project.class);

        assertEquals(original.getProjectName(), restored.getProjectName());
        assertEquals(original.getFolderPath(), restored.getFolderPath());
        assertEquals(original.getApps(), restored.getApps());
        assertEquals(original.getUrls(), restored.getUrls());
        assertEquals(original.getActive(), restored.getActive());
        assertEquals(original.getLocalDate(), restored.getLocalDate());
    }

    @Test
    void gsonRoundTripHandlesEmptyAppsAndUrls() {
        Project original = new Project("Empty Project", null, new HashMap<>(), new ArrayList<>());

        Project restored = gson.fromJson(gson.toJson(original), Project.class);

        assertEquals(0, restored.getApps().size());
        assertEquals(0, restored.getUrls().size());
        assertNull(restored.getFolderPath());
    }

    @Test
    void gsonRoundTripPreservesUrlsPerBrowser() {
        Project original = new Project("Multi Browser", "C:\\dev\\x", new HashMap<>(), new ArrayList<>());
        original.urlsByBrowser.put("Chrome", new ArrayList<>(List.of("https://github.com")));
        original.urlsByBrowser.put("Edge", new ArrayList<>(List.of("https://claude.ai", "https://a.example?q=1&r=2")));

        Project restored = gson.fromJson(gson.toJson(original), Project.class);

        assertEquals(original.getUrlsByBrowser(), restored.getUrlsByBrowser());
        assertEquals(List.of("https://github.com"), restored.getUrlsByBrowser().get("Chrome"));
    }

    @Test
    void projectsSavedBeforeBrowsersWereTrackedKeepTheirUrls() {
        // a real pre-migration entry: a flat urls array and no urlsByBrowser key at all
        Project legacy = gson.fromJson(
                "{\"projectName\":\"Old\",\"urls\":[\"https://github.com\",\"https://claude.ai\"]}",
                Project.class);
        assertNull(legacy.urlsByBrowser, "gson leaves an absent field null, field initialisers do not run");

        ColdstartController.migrateLegacyUrls(legacy);

        assertEquals(List.of("https://github.com", "https://claude.ai"),
                legacy.getUrlsByBrowser().get(Browsers.DEFAULT_BROWSER));
    }

    @Test
    void migrationLeavesAlreadyMigratedProjectsAlone() {
        Project project = new Project("New", null, new HashMap<>(), new ArrayList<>(List.of("https://stale.example")));
        project.urlsByBrowser.put("Chrome", new ArrayList<>(List.of("https://fresh.example")));

        ColdstartController.migrateLegacyUrls(project);

        assertEquals(1, project.getUrlsByBrowser().size());
        assertEquals(List.of("https://fresh.example"), project.getUrlsByBrowser().get("Chrome"));
    }

    @Test
    void deletingAProjectRemovesOnlyThatEntry() {
        ArrayList<Project> onDisk = new ArrayList<>(List.of(
                new Project("Keep", null, new HashMap<>(), new ArrayList<>()),
                new Project("Drop", null, new HashMap<>(), new ArrayList<>()),
                new Project("Keep Too", null, new HashMap<>(), new ArrayList<>())));

        ColdstartController.removeByName(onDisk, "Drop");

        assertEquals(List.of("Keep", "Keep Too"),
                onDisk.stream().map(Project::getProjectName).toList());
    }

    @Test
    void writingThenReadingProjectsJsonFileMatchesControllerFormat(@TempDir Path tempDir) throws IOException {
        // Mirrors ColdstartController.saveProject/getProjectsJson: the whole projects list is
        // (re)written as a single JSON array, not appended object-by-object.
        HashMap<String, HashSet<String>> apps = new HashMap<>();
        apps.put("Music", new HashSet<>(java.util.List.of("spotify.exe")));
        ArrayList<String> urls = new ArrayList<>(java.util.List.of("https://open.spotify.com"));
        Project first = new Project("File Round Trip", "C:\\dev\\project", apps, urls);
        Project second = new Project("Second Project", "C:\\dev\\other", new HashMap<>(), new ArrayList<>());

        Path projectsJson = tempDir.resolve("projects.json");
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(projectsJson.toFile()))) {
            gson.toJson(List.of(first, second), writer);
        }

        List<Project> loaded;
        try (Reader reader = new FileReader(projectsJson.toFile())) {
            loaded = gson.fromJson(reader, new TypeToken<ArrayList<Project>>(){}.getType());
        }

        assertEquals(2, loaded.size());
        assertEquals(first.getProjectName(), loaded.get(0).getProjectName());
        assertEquals(first.getApps(), loaded.get(0).getApps());
        assertEquals(first.getUrls(), loaded.get(0).getUrls());
        assertEquals(second.getProjectName(), loaded.get(1).getProjectName());
    }

    @Test
    void savingAProjectAgainUpdatesItInPlaceInsteadOfDuplicating(@TempDir Path tempDir) throws IOException {
        // Mirrors ColdstartController.saveProject's upsert-by-name behavior: re-saving a project
        // with the same name replaces its entry rather than appending a duplicate.
        Project original = new Project("X", "C:\\dev\\x", new HashMap<>(), new ArrayList<>(java.util.List.of("https://a.example")));
        Path projectsJson = tempDir.resolve("projects.json");
        try (Writer writer = new FileWriter(projectsJson.toFile())) {
            gson.toJson(List.of(original), writer);
        }

        ArrayList<Project> onDisk;
        try (Reader reader = new FileReader(projectsJson.toFile())) {
            onDisk = gson.fromJson(reader, new TypeToken<ArrayList<Project>>(){}.getType());
        }
        Project updated = new Project("X", "C:\\dev\\x", new HashMap<>(), new ArrayList<>(java.util.List.of("https://b.example")));
        for (int i = 0; i < onDisk.size(); i++) {
            if (onDisk.get(i).getProjectName().equals(updated.getProjectName())) {
                onDisk.set(i, updated);
            }
        }
        try (Writer writer = new FileWriter(projectsJson.toFile(), false)) {
            gson.toJson(onDisk, writer);
        }

        List<Project> reloaded;
        try (Reader reader = new FileReader(projectsJson.toFile())) {
            reloaded = gson.fromJson(reader, new TypeToken<ArrayList<Project>>(){}.getType());
        }

        assertEquals(1, reloaded.size());
        assertEquals(java.util.List.of("https://b.example"), reloaded.get(0).getUrls());
    }
}
