package com.example.resumeone;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("priority-medium")
class ClaudeSessionTest {

    private final ColdstartController controller = new ColdstartController();

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

        // a background session is alive but windowless: resume still has to open claude
        Files.writeString(sessions.resolve("live.json"), "{\"pid\":%d,\"cwd\":\"C:\\\\Users\\\\me\\\\proj\",\"kind\":\"bg\"}"
                .formatted(ProcessHandle.current().pid()));
        assertFalse(controller.claudeRunningIn(folder, sessions.toFile()), "background session ignored");

        Files.writeString(sessions.resolve("live.json"), "{not json");
        assertFalse(controller.claudeRunningIn(folder, sessions.toFile()), "garbage file ignored");
        assertFalse(controller.claudeRunningIn(folder, new File(sessions.toFile(), "missing")));
    }
}
