package com.example.resumeone;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** ago() reads hours before days, and still understands the bare dates older files hold. */
@Tag("priority-medium")
class AgoTest {

    private static String minus(long amount, ChronoUnit unit) {
        return Instant.now().minus(amount, unit).toString();
    }

    @Test
    void hoursBeforeDays() {
        assertEquals("just now", ColdstartController.ago(Instant.now().toString()));
        assertEquals("4m", ColdstartController.ago(minus(4, ChronoUnit.MINUTES)));
        assertEquals("2h", ColdstartController.ago(minus(2, ChronoUnit.HOURS)));
        assertEquals("3d", ColdstartController.ago(minus(3, ChronoUnit.DAYS)));
        assertEquals("2w", ColdstartController.ago(minus(15, ChronoUnit.DAYS)));
        assertEquals("2mo", ColdstartController.ago(minus(65, ChronoUnit.DAYS)));
    }

    @Test
    void bareDatesFromOlderFilesStillRead() {
        assertEquals("3d", ColdstartController.ago(LocalDate.now().minusDays(3).toString()));
        assertEquals("never", ColdstartController.ago(null));
        assertEquals("never", ColdstartController.ago(""));
    }

    @Test
    void siteIsTheHostWithoutSchemeOrWww() {
        assertEquals("github.com", ColdstartController.site("https://www.github.com/atlas-api/pull/42"));
        assertEquals("javalin.io", ColdstartController.site("https://javalin.io/documentation#websockets"));
        assertEquals("localhost:8765", ColdstartController.site("http://localhost:8765/tabs"));
        assertEquals("settings", ColdstartController.site("chrome://settings/"));
        assertEquals("about:blank", ColdstartController.site("about:blank"));
    }

    @Test
    void browserInternalPagesAreNotTabsWorthKeeping() {
        assertEquals(true, ColdstartController.isPageUrl("https://github.com/x"));
        assertEquals(true, ColdstartController.isPageUrl("http://localhost:8765/"));
        assertEquals(false, ColdstartController.isPageUrl("chrome://newtab/"));
        assertEquals(false, ColdstartController.isPageUrl("edge://extensions"));
        assertEquals(false, ColdstartController.isPageUrl("about:blank"));
        assertEquals(false, ColdstartController.isPageUrl(null));
    }

    @Test
    void whenTurnsAnAgeIntoAPhrase() {
        assertEquals("just now", ColdstartController.when("just now"));
        assertEquals("3d ago", ColdstartController.when("3d"));
        assertEquals("never", ColdstartController.when("never"));
    }
}
