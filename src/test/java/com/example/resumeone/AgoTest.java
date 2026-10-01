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
        assertEquals("just now", HelloController.ago(Instant.now().toString()));
        assertEquals("4m", HelloController.ago(minus(4, ChronoUnit.MINUTES)));
        assertEquals("2h", HelloController.ago(minus(2, ChronoUnit.HOURS)));
        assertEquals("3d", HelloController.ago(minus(3, ChronoUnit.DAYS)));
        assertEquals("2w", HelloController.ago(minus(15, ChronoUnit.DAYS)));
        assertEquals("2mo", HelloController.ago(minus(65, ChronoUnit.DAYS)));
    }

    @Test
    void bareDatesFromOlderFilesStillRead() {
        assertEquals("3d", HelloController.ago(LocalDate.now().minusDays(3).toString()));
        assertEquals("never", HelloController.ago(null));
        assertEquals("never", HelloController.ago(""));
    }

    @Test
    void siteIsTheHostWithoutSchemeOrWww() {
        assertEquals("github.com", HelloController.site("https://www.github.com/atlas-api/pull/42"));
        assertEquals("javalin.io", HelloController.site("https://javalin.io/documentation#websockets"));
        assertEquals("localhost:8765", HelloController.site("http://localhost:8765/tabs"));
        assertEquals("settings", HelloController.site("chrome://settings/"));
        assertEquals("about:blank", HelloController.site("about:blank"));
    }

    @Test
    void browserInternalPagesAreNotTabsWorthKeeping() {
        assertEquals(true, HelloController.isPageUrl("https://github.com/x"));
        assertEquals(true, HelloController.isPageUrl("http://localhost:8765/"));
        assertEquals(false, HelloController.isPageUrl("chrome://newtab/"));
        assertEquals(false, HelloController.isPageUrl("edge://extensions"));
        assertEquals(false, HelloController.isPageUrl("about:blank"));
        assertEquals(false, HelloController.isPageUrl(null));
    }

    @Test
    void whenTurnsAnAgeIntoAPhrase() {
        assertEquals("just now", HelloController.when("just now"));
        assertEquals("3d ago", HelloController.when("3d"));
        assertEquals("never", HelloController.when("never"));
    }

    @Test
    void appNameDropsOnlyTheExeSuffix() {
        assertEquals("Code", HelloController.appName("Code.exe"));
        assertEquals("idea64", HelloController.appName("IDEA64.EXE".toLowerCase()));
        assertEquals("Notepad", HelloController.appName("Notepad.EXE"));
        assertEquals("exe.tool", HelloController.appName("exe.tool"));
    }
}
