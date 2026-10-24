package com.example.resumeone;

import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The app's half of the extension contract over real sockets: the Javalin server coldstart
    runs, a fake extension that answers the way background.js does, and projects.json on disk.
    Maven points APPDATA at target/ and the port at 18765 (pom.xml), so this runs beside the
    user's own coldstart without touching its projects. */
@Tag("e2e")
class ServerE2ETest {

    static final String HOST = "127.0.0.1:" + ColdstartController.PORT;
    static final String EXT = "chrome-extension://coldstart-e2e";
    static final String EMAIL = "e2e@coldstart.test";
    static final HttpClient http = HttpClient.newHttpClient();
    static final Gson gson = new Gson();
    static ColdstartController app;

    @BeforeAll
    static void start() {
        // outside Maven (an IDE run) APPDATA is the user's own: their projects stay out of this
        assumeTrue(ColdstartController.projectsFile().getPath().contains("test-appdata"),
                "run through mvnw so APPDATA points at target/test-appdata");
        app = new ColdstartController();
        app.startServer();
    }

    /** background.js in miniature: "capture" posts these tabs, {"open":[...]} answers how many it opened. */
    static final class FakeExtension implements WebSocket.Listener {
        final LinkedBlockingQueue<String> got = new LinkedBlockingQueue<>();
        final List<String> tabs;
        final int opens;
        WebSocket ws;

        FakeExtension(List<String> tabs, int opens) {
            this.tabs = tabs;
            this.opens = opens;
        }

        static FakeExtension connect(List<String> tabs, int opens) throws Exception {
            FakeExtension ext = new FakeExtension(tabs, opens);
            ext.ws = http.newWebSocketBuilder().header("Origin", EXT)
                    .buildAsync(URI.create("ws://" + HOST + "/ws?browser=Edge&profile=" + EMAIL), ext)
                    .get(5, TimeUnit.SECONDS);
            // the server registers a socket before it reads from it, so an echo means it is listed
            ext.ws.sendText("Heartbeat", true);
            assertEquals("Heartbeat", ext.next());
            return ext;
        }

        String next() throws InterruptedException {
            String msg = got.poll(5, TimeUnit.SECONDS);
            assertNotNull(msg, "the app sent nothing");
            return msg;
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            String msg = data.toString();
            got.add(msg);
            if (msg.equals("capture")) {
                post("/tabs", gson.toJson(Map.of("browser", "Edge", "profile", EMAIL, "urls", tabs)), EXT);
            } else if (msg.startsWith("{\"open\"")) {
                ws.sendText("{\"opened\":" + opens + "}", true);
            }
            ws.request(1);
            return null;
        }
    }

    static HttpResponse<String> post(String path, String body, String origin) {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://" + HOST + path))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (origin != null) req.header("Origin", origin);
        return http.sendAsync(req.build(), HttpResponse.BodyHandlers.ofString()).join();
    }

    static Project project(String name) {
        return new Project(name, null, new HashMap<>(), new ArrayList<>());
    }

    @Test
    void aWebPageCannotReachTheServer() {
        HttpResponse<String> page = http.sendAsync(HttpRequest.newBuilder(URI.create("http://" + HOST + "/project"))
                .header("Origin", "https://evil.example").build(), HttpResponse.BodyHandlers.ofString()).join();
        assertEquals(403, page.statusCode());
        assertEquals(403, post("/tabs", "{\"urls\":[\"https://evil.example\"]}", "https://evil.example").statusCode());

        CompletionException refused = assertThrows(CompletionException.class, () -> http.newWebSocketBuilder()
                .header("Origin", "https://evil.example")
                .buildAsync(URI.create("ws://" + HOST + "/ws?browser=Edge"), new WebSocket.Listener() { }).join());
        assertNotNull(refused.getCause());
    }

    @Test
    void checkpointAsksTheExtensionAndItsTabsReachTheDisk() throws Exception {
        Project p = project("e2e-checkpoint-" + System.nanoTime());
        FakeExtension ext = FakeExtension.connect(
                List.of("https://github.com/Ysweb3/ResumeOne", "edge://newtab/", "https://claude.ai"), 0);
        try {
            CountDownLatch replies = app.askForTabs(p);

            assertEquals("capture", ext.next());
            assertTrue(replies.await(5, TimeUnit.SECONDS), "the checkpoint never heard back");
            Project saved = app.getProjectsJson().stream()
                    .filter(s -> s.projectName.equals(p.projectName)).findFirst().orElseThrow();
            // the browser's own pages are dropped, the rest kept under that profile's bucket
            assertEquals(Map.of(Browsers.browserKey("Edge", EMAIL),
                            List.of("https://github.com/Ysweb3/ResumeOne", "https://claude.ai")),
                    saved.urlsByBrowser);
        } finally {
            ext.ws.abort();
        }
    }

    @Test
    void resumeHandsTheTabsToTheExtensionAndCountsWhatItOpened() throws Exception {
        Project p = project("e2e-resume-" + System.nanoTime());
        p.urlsByBrowser.put(Browsers.browserKey("Edge", EMAIL),
                new ArrayList<>(List.of("https://github.com", "https://claude.ai")));
        FakeExtension ext = FakeExtension.connect(List.of(), 1);
        try {
            ResumePlanner.LaunchStep step = app.planResume(p, java.util.Set.of(), new ArrayList<>()).steps().get(0);
            step.run();

            assertEquals(Map.of("open", List.of("https://github.com", "https://claude.ai")),
                    gson.fromJson(ext.next(), Map.class));
            assertEquals(ResumePlanner.LaunchStep.State.DONE, step.state);
            // one was already open: the card says 1 opened, not 2
            assertEquals(1, step.opened);
        } finally {
            ext.ws.abort();
        }
    }
}
