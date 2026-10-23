package com.example.resumeone;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StartupTest {

    @Test
    void readsTheLastLinePowerShellPrinted() {
        assertEquals("Enabled", ColdstartController.startupState("Enabled"));
        assertEquals("DisabledByUser", ColdstartController.startupState("noise\r\nDisabledByUser\r\n"));
    }

    @Test
    void anythingElseMeansNoPackage() {
        assertEquals("", ColdstartController.startupState(""));
        assertEquals("", ColdstartController.startupState("Exception calling \"GetAsync\": no package identity"));
        assertEquals("Only in the installed app", ColdstartController.startupHint(""));
        assertEquals("", ColdstartController.startupHint("Enabled"));
    }
}
