package com.example.resumeone;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

@Tag("priority-high")
class ProjectTest {

    @Test
    void constructorStoresAllFieldsFromSampleData() {
        HashMap<String, HashSet<String>> apps = new HashMap<>();
        apps.put("Browser", new HashSet<>(java.util.List.of("chrome.exe")));
        ArrayList<String> urls = new ArrayList<>(java.util.List.of("https://github.com", "https://claude.ai"));

        Project project = new Project("Cold Start", "C:\\dev\\cold-start", apps, urls);

        assertEquals("Cold Start", project.getProjectName());
        assertEquals("C:\\dev\\cold-start", project.getFolderPath());
        assertEquals(apps, project.getApps());
        assertEquals(urls, project.getUrls());
    }

    @Test
    void newProjectStartsInactiveWithACheckpointStampOfNow() {
        Project project = new Project("New Project", null, new HashMap<>(), new ArrayList<>());

        assertFalse(project.getActive());
        // an ISO instant, not a bare date, so the UI can say "2h" rather than "today"
        long secondsAgo = java.time.Duration.between(
                java.time.Instant.parse(project.getLocalDate()), java.time.Instant.now()).toSeconds();
        assertTrue(secondsAgo >= 0 && secondsAgo < 60);
    }

    @Test
    void allowsNullFolderPathForProjectsWithNoFolderSelected() {
        Project project = new Project("No Folder Project", null, new HashMap<>(), new ArrayList<>());

        assertNull(project.getFolderPath());
    }

    @Test
    void preservesEmptyAppsAndUrlsForFreshlyCreatedProject() {
        Project project = new Project("Fresh Project", "C:\\dev", new HashMap<>(), new ArrayList<>());

        assertEquals(0, project.getApps().size());
        assertEquals(0, project.getUrls().size());
    }
}
