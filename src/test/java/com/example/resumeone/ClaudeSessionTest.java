package com.example.resumeone;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("priority-medium")
class ClaudeSessionTest {

    private final HelloController controller = new HelloController();

    @Test
    void slugMatchesTheDirectoryNameClaudeActuallyUses() {
        // verified against the real ~/.claude/projects listing
        assertEquals("C--Users-yashs-OneDrive-Desktop-Projects",
                HelloController.claudeSessionSlug("C:\\Users\\yashs\\OneDrive\\Desktop\\Projects"));
        assertEquals("D--work-repo",
                HelloController.claudeSessionSlug("D:\\work\\repo"));
    }

    @Test
    void foldersWithoutTranscriptsFallBackToAFreshSession() {
        assertFalse(controller.hasClaudeSession("Z:\\no\\such\\folder\\anywhere"));
    }

    @Test
    void claudeCountsAsRunningOnlyWithALiveSessionFileForThatFolder(@TempDir Path sessions) throws Exception {
        String folder = "C:\\Users\\me\\proj";
        // json-escaped backslashes, as claude writes them
        String json = "{\"pid\":%d,\"cwd\":\"C:\\\\Users\\\\me\\\\proj\"}";
        // this test's own pid is certainly alive; 1 is an absurd pid on windows
        Files.writeString(sessions.resolve("live.json"), json.formatted(ProcessHandle.current().pid()));
        assertTrue(controller.claudeRunningIn(folder, sessions.toFile()));
        assertFalse(controller.claudeRunningIn("C:\\other", sessions.toFile()));

        Files.writeString(sessions.resolve("live.json"), json.formatted(1));
        assertFalse(controller.claudeRunningIn(folder, sessions.toFile()), "stale file: pid dead");

        Files.writeString(sessions.resolve("live.json"), "{not json");
        assertFalse(controller.claudeRunningIn(folder, sessions.toFile()), "garbage file ignored");
        assertFalse(controller.claudeRunningIn(folder, new File(sessions.toFile(), "missing")));
    }
}
