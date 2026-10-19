package com.example.resumeone;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StartupTest {

    @Test
    void readsTheLastLinePowerShellPrinted() {
        assertEquals("Enabled", HelloController.startupState("Enabled"));
        assertEquals("DisabledByUser", HelloController.startupState("noise\r\nDisabledByUser\r\n"));
    }

    @Test
    void anythingElseMeansNoPackage() {
        assertEquals("", HelloController.startupState(""));
        assertEquals("", HelloController.startupState("Exception calling \"GetAsync\": no package identity"));
        assertEquals("Only in the installed app", HelloController.startupHint(""));
        assertEquals("", HelloController.startupHint("Enabled"));
    }
}
