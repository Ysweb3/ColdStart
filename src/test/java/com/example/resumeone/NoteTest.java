package com.example.resumeone;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NoteTest {

    @Test
    void theFieldIsStoredTrimmedAndBlankMeansNoNote() {
        assertEquals("fix the export bug", HelloController.noteOf("  fix the export bug \t"));
        assertNull(HelloController.noteOf("   "), "a cleared field removes the note, not a blank one");
        assertNull(HelloController.noteOf(""));
    }

    @Test
    void aProjectsFileFromBeforeNotesLoadsWithNone() {
        Project old = new Gson().fromJson("{\"projectName\":\"atlas\",\"apps\":{}}", Project.class);
        assertNull(old.note);

        old.note = "test with Premiere";
        assertEquals("test with Premiere", new Gson().fromJson(new Gson().toJson(old), Project.class).note);
    }
}
