package com.example.resumeone;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** First run's browser step: which rows it lists, what its title says, which browser is default. */
class OnboardingTest {

    @Test
    void listsRunningSupportedBrowsersAndAnyAlreadyConnected() {
        // firefox has its own row, not one of these; vivaldi is not listed at all
        assertEquals(List.of("edge", "brave"),
                ColdstartController.obRows(Set.of("brave.exe", "msedge.exe", "firefox.exe", "vivaldi.exe"), Set.of()));
        // connected but missed by the capture still gets its row
        assertEquals(List.of("chrome"), ColdstartController.obRows(Set.of(), Set.of("chrome")));
        assertEquals(List.of(), ColdstartController.obRows(Set.of("code.exe"), Set.of()));
    }

    @Test
    void titleCountsConnectedBrowsers() {
        assertEquals("1 of 2 browsers connected", ColdstartController.connectedTitle(List.of("edge", "brave"), Set.of("brave")));
        assertEquals("2 browsers connected", ColdstartController.connectedTitle(List.of("edge", "brave"), Set.of("brave", "edge")));
        assertEquals("Brave connected", ColdstartController.connectedTitle(List.of("brave"), Set.of("brave")));
    }

    @Test
    void defaultBrowserComesFromTheHttpsProgId() {
        String reg = "\r\nHKEY_CURRENT_USER\\...\\UserChoice\r\n    ProgId    REG_SZ    %s\r\n\r\n";
        assertEquals("brave", ColdstartController.brandFromProgId(reg.formatted("BraveHTML")));
        assertEquals("edge", ColdstartController.brandFromProgId(reg.formatted("MSEdgeHTM")));
        assertEquals("chrome", ColdstartController.brandFromProgId(reg.formatted("ChromeHTML")));
        assertEquals("opera", ColdstartController.brandFromProgId(reg.formatted("OperaStable")));
        assertEquals("firefox", ColdstartController.brandFromProgId(reg.formatted("FirefoxURL-308046B0AF4A39CB")));
        assertNull(ColdstartController.brandFromProgId(reg.formatted("VivaldiHTM.ABC")));
        assertNull(ColdstartController.brandFromProgId("ERROR: The system was unable to find the specified registry key or value."));
    }
}
