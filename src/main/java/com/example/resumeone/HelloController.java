package com.example.resumeone;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane; // explicit: java.awt.* also exports ScrollPane
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;
import javafx.util.Duration;
import kotlin.reflect.KTypeProjection;

import java.net.URI;
import java.awt.*;
import java.io.File;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.net.URISyntaxException;
import java.util.*;
import java.util.List; // explicit: java.awt.* also exports List
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;


public class HelloController {
    @FXML
    public Button btnNewProject;

    // one extension per browser, each with its own socket: a single field meant the second
    // browser to connect silently replaced the first, and only it ever got the capture push
    private final Set<WsContext> wsSessions = ConcurrentHashMap.newKeySet();

    @FXML
    public VBox projectList;

    // ── HUD v2 (hud-view.fxml). All null when the old hello-view.fxml is loaded,
    //    so every use is guarded by isHud().
    @FXML public VBox detailPane, emptyPane, categoryRows, tabsList;
    @FXML public VBox savingPane, deletePane, newProjectPane;
    @FXML public TextField npName;
    @FXML public Label npFolder;
    @FXML public CheckBox npCapture;
    @FXML public Button btnNpBrowse, btnNpCancel, btnNpCreate;
    @FXML public ScrollPane settingsPane;
    @FXML public StackPane btnSettings;
    @FXML public Label savingSpec, deleteBody, deletePath;
    @FXML public Button btnSavingDone, btnDeleteCancel, btnSettingsBack;
    @FXML public HBox btnResume, btnCheckpoint, statusBox;
    @FXML public StackPane btnDelete;
    @FXML public Region statusDot;
    @FXML public Label projectNameLabel, projectPathLabel, resumeSpec, checkpointSub,
            capturedNote, projectCount, tabsCount, statusLabel;

    /** Hover-only: names the browsers on the socket, so the dot itself stays uncluttered. */
    // built in initialize(), not here: a Tooltip needs the JavaFX toolkit, which the unit tests
    // (new HelloController()) never start
    private Tooltip statusTip;

    /** Sidebar row per project name, so deleteProject() can be handed the row to remove. */
    private final Map<String, HBox> rowsByProject = new LinkedHashMap<>();

    public String projectFolder;

    public Gson gson = new Gson();

    private Project activeProject;

    public ArrayList<Project> projects = new ArrayList<>();

    @FXML
    public void initialize() throws IOException {

        for (Project project : getProjectsJson()) {
            createProjectUI(project, false);
        }
        segregateApps(captureRunningApps());
        startServer();

        if (!isHud()) return;
        btnResume.setOnMouseClicked(e -> onResume());
        btnCheckpoint.setOnMouseClicked(e -> onCheckpoint());
        // The delete confirmation is a view now, not an Alert. Its Delete button is
        // deliberately inert - onDeleteActive() below still holds the working delete and
        // one setOnAction re-arms it.
        btnDelete.setOnMouseClicked(e -> showDeleteView());
        btnSettings.setOnMouseClicked(e -> showBase(settingsPane));
        btnSavingDone.setOnAction(e -> backToProjects());
        btnDeleteCancel.setOnAction(e -> backToProjects());
        btnSettingsBack.setOnAction(e -> backToProjects());
        btnNpCancel.setOnAction(e -> backToProjects());
        btnNpBrowse.setOnAction(e -> {
            String chosen = openFolder(e);          // returns the "NULL" sentinel on cancel
            if (!"NULL".equals(chosen)) npFolder.setText(chosen);
        });
        btnNpCreate.disableProperty().bind(Bindings.createBooleanBinding(
                () -> npName.getText().trim().isEmpty(), npName.textProperty()));
        btnNpCreate.setOnAction(e -> createFromDialog());
        statusTip = new Tooltip();
        statusTip.setShowDelay(Duration.millis(150));
        Tooltip.install(statusBox, statusTip);
        refreshStatusDot();
        refreshCount();
        if (projects.isEmpty()) {
            showEmpty(true);
        } else {
            selectProject(projects.get(0));
        }
    }

    /** True when the loaded view is hud-view.fxml rather than the older hello-view.fxml. */
    private boolean isHud() {
        return detailPane != null;
    }
    @FXML
    static File projectsFile() {
        File dir = new File(System.getenv("APPDATA"), "ResumeWork");
        dir.mkdirs();
        return new File(dir, "projects.json");
    }

    public ArrayList<Project> getProjectsJson() {
        File file = projectsFile();
        if (!file.exists()) {
            return new ArrayList<>();
        }

        try (Reader reader = new FileReader(file)) {
            ArrayList<Project> loaded = gson.fromJson(reader, new TypeToken<ArrayList<Project>>(){}.getType());
            if (loaded == null) {
                return new ArrayList<>();
            }
            for (Project p : loaded) {
                migrateLegacyUrls(p);
            }
            return loaded;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    private static final String NO_FOLDER = "no folder chosen";

    @FXML
    public void onNewProjectClick() {
        if (!isHud()) {
            legacyNewProjectDialog();
            return;
        }
        npName.clear();
        npFolder.setText(NO_FOLDER);
        npCapture.setSelected(true);
        showOverlay(newProjectPane);
        npName.requestFocus();
    }

    /** Create + checkpoint: same work the old dialog's OK branch did. */
    private void createFromDialog() {
        String name = npName.getText().trim();
        if (name.isEmpty()) return;
        String folder = NO_FOLDER.equals(npFolder.getText()) ? null : npFolder.getText();
        Project projectObj = new Project(name, folder, new HashMap<>(), new ArrayList<>());
        // createProjectUI -> selectProject -> showBase() clears the overlay for us
        createProjectUI(projectObj, true);
        if (npCapture.isSelected()) {
            wsSessions.forEach(session -> session.send("capture"));
        }
    }

    /** Only reachable from the older hello-view.fxml, which has no scrim to show. */
    private void legacyNewProjectDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("New project");

       TextField nameField = new TextField();
       TextField folderField = new TextField();

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        grid.add(new Label("Project Name"),0,0);
        grid.add(nameField,1,0);

        grid.add(new Label("Project Folder"),0,1);

        Button selectFolder = new Button();
        selectFolder.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-weight:bold;");
        selectFolder.setText("Select Folder");
        selectFolder.setOnAction(e -> {
            projectFolder = (openFolder(e));
            selectFolder.setText(projectFolder);
                });
        grid.add(selectFolder,1,1);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {

            String name = nameField.getText();
            Project projectObj = new Project(name, projectFolder, new HashMap<>(), new ArrayList<>());
            createProjectUI(projectObj, true);
            // A new project captures apps in createProjectUI but tabs only arrive by asking:
            // same push checkpoint uses. It has to run after createProjectUI, because the
            // POST /tabs reply lands on whichever project selectProject() left active.
            wsSessions.forEach(session -> session.send("capture"));

        };

    }
    //TODO:IDEA:Also add a CLI to start this thing
    public void createProjectUI(Project projectObj, boolean isNew) {

        if (isNew) {
            // initial capture when a new project is made; saveProject() upserts into projects
            try {
                projectObj.apps = segregateApps(captureRunningApps());
                saveProject(projectObj);
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        } else {
            projects.add(projectObj);
        }

        HBox row = new HBox(12);
        row.getStyleClass().add("side-item");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMinHeight(34);
        row.setPrefHeight(34);
        row.setPadding(new Insets(0, 10, 0, 10));

        SVGPath folder = new SVGPath();
        folder.setContent("M1 3 L6 3 L7.5 4.8 L14 4.8 L14 12.5 L1 12.5 Z");
        folder.setFill(Color.web("#A99B8D"));

        Label name = new Label(projectObj.projectName);
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(name, Priority.ALWAYS);

        Label age = new Label(ago(projectObj.lastCheckpoint));
        age.getStyleClass().add("side-count");

        row.getChildren().addAll(folder, name, age);
        row.setOnMouseClicked(e -> selectProject(projectObj));

        rowsByProject.put(projectObj.projectName, row);
        projectList.getChildren().add(row);

        if (isHud()) {
            refreshCount();
            if (isNew) {
                selectProject(projectObj);
            }
        }
    }

    // == HUD rendering =====================================================

    /** detailPane / emptyPane / settingsPane are the base views: exactly one shows. */
    private void showBase(Node target) {
        for (Node pane : List.of(detailPane, emptyPane, settingsPane)) {
            pane.setVisible(pane == target);
            pane.setManaged(pane == target);
        }
        showOverlay(null);
    }

    /** savingPane / deletePane / newProjectPane are scrims stacked over whichever base is showing. */
    private void showOverlay(Node target) {
        for (Node pane : List.of(savingPane, deletePane, newProjectPane)) {
            pane.setVisible(pane == target);
            pane.setManaged(pane == target);
        }
    }

    private void backToProjects() {
        showEmpty(projects.isEmpty());
    }

    private void showEmpty(boolean empty) {
        showBase(empty ? emptyPane : detailPane);
    }

    private void showDeleteView() {
        if (activeProject == null) return;
        deletePath.setText(activeProject.folderPath == null ? "no folder" : activeProject.folderPath);
        showOverlay(deletePane);
    }

    private void refreshCount() {
        projectCount.setText(String.format("%02d", projects.size()));
    }

    private void selectProject(Project projectObj) {
        activeProject = projectObj;
        rowsByProject.forEach((n, r) -> {
            r.getStyleClass().remove("side-item-active");
            if (n.equals(projectObj.projectName)) {
                r.getStyleClass().add("side-item-active");
            }
        });
        showEmpty(false);
        renderDetail(projectObj);
    }

    private void renderDetail(Project projectObj) {
        migrateLegacyUrls(projectObj);

        projectNameLabel.setText(projectObj.projectName);
        projectPathLabel.setText(projectObj.folderPath == null ? "no folder" : projectObj.folderPath);

        int apps = 0;
        if (projectObj.apps != null) {
            for (HashSet<String> bucket : projectObj.apps.values()) apps += bucket.size();
        }
        int tabs = 0;
        for (ArrayList<String> urls : projectObj.urlsByBrowser.values()) tabs += urls.size();

        resumeSpec.setText(apps + " APPS \u00b7 " + tabs + " TABS \u00b7 "
                + (projectObj.folderPath == null ? 0 : 1) + " FOLDER");
        String age = ago(projectObj.lastCheckpoint);
        checkpointSub.setText("today".equals(age)
                ? "overwrite \u00b7 saved today" : "overwrite \u00b7 " + age + " old");
        capturedNote.setText("today".equals(age) ? "captured today" : "captured " + age + " ago");
        tabsCount.setText(String.valueOf(tabs));
        // tabs land after the capture push returns, so the saving stamp is refreshed here
        // rather than read once at checkpoint time
        savingSpec.setText(apps + " APPS \u00b7 " + tabs + " TABS");

        renderCategories(projectObj);
        renderTabs(projectObj);
    }

    // the design folds at four categories and links to an inspector; that artboard is not
    // built, so every non-empty category is listed rather than hidden behind a dead link
    private static final List<String> CATEGORY_ORDER = List.of(
            "IDE", "AI", "Terminal", "Browser", "Database", "API", "Containers",
            "VersionControl", "Design", "Communication", "Notes", "Music");

    private void renderCategories(Project projectObj) {
        categoryRows.getChildren().clear();
        if (projectObj.apps == null) return;

        for (String category : CATEGORY_ORDER) {
            HashSet<String> bucket = projectObj.apps.get(category);
            if (bucket == null || bucket.isEmpty()) continue;

            HBox labelBox = new HBox(6);
            labelBox.setAlignment(Pos.CENTER_LEFT);
            labelBox.setMinWidth(96);
            labelBox.setPrefWidth(96);
            labelBox.setMaxWidth(96);
            labelBox.getChildren().addAll(categoryIcon(category),
                    styled(new Label(category.toUpperCase()), "cat-label"));

            HBox chips = new HBox(5);
            chips.setAlignment(Pos.CENTER_LEFT);
            for (String app : new TreeSet<>(bucket)) {
                chips.getChildren().add(chip(app));
            }

            HBox row = new HBox(10);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getChildren().addAll(labelBox, chips);

            String note = trailingNote(category, projectObj);
            if (note != null) row.getChildren().add(styled(new Label(note), "cat-arg"));

            categoryRows.getChildren().add(row);
        }

        // "Other" is captured but never relaunched, which the design calls out by name
        HashSet<String> other = projectObj.apps.get(OTHER);
        if (other != null && !other.isEmpty()) {
            String first = new TreeSet<>(other).first();
            String rest = other.size() > 1 ? " (+" + (other.size() - 1) + ")" : "";
            categoryRows.getChildren().add(styled(
                    new Label(first + rest + " under Other \u2014 captured, not relaunched"), "cat-foot"));
        }
    }

    /** The extra argument resumeProject() will actually use for this category. */
    private String trailingNote(String category, Project projectObj) {
        if (projectObj.folderPath == null) return null;
        if ("IDE".equals(category)) {
            return "\u2026\\" + new File(projectObj.folderPath).getName();
        }
        if ("AI".equals(category) && hasClaudeSession(projectObj.folderPath)) {
            return "--continue";
        }
        return null;
    }

    private static final int TABS_SHOWN = 3;

    private void renderTabs(Project projectObj) {
        tabsList.getChildren().clear();
        projectObj.urlsByBrowser.forEach((browser, urls) -> {
            HBox head = new HBox(8);
            head.setAlignment(Pos.CENTER_LEFT);
            Region dot = new Region();
            dot.getStyleClass().add("dot-live");
            dot.setMinSize(5, 5);
            dot.setPrefSize(5, 5);
            dot.setMaxSize(5, 5);
            head.getChildren().addAll(dot, styled(new Label(browser), "browser-name"),
                    styled(new Label(String.valueOf(urls.size())), "side-count"));
            tabsList.getChildren().add(head);

            urls.stream().limit(TABS_SHOWN).forEach(url -> {
                Label item = styled(new Label(tidy(url)), "tab-title");
                item.setMaxWidth(256);
                item.setTextOverrun(OverrunStyle.ELLIPSIS);
                tabsList.getChildren().add(item);
            });
            if (urls.size() > TABS_SHOWN) {
                tabsList.getChildren().add(styled(
                        new Label((urls.size() - TABS_SHOWN) + " more"), "cat-more"));
            }
        });
    }

    private static String tidy(String url) {
        return url.replaceFirst("^https?://", "");
    }

    private static Label styled(Label label, String styleClass) {
        label.getStyleClass().add(styleClass);
        return label;
    }

    private static HBox chip(String app) {
        HBox chip = new HBox();
        chip.getStyleClass().add("chip");
        chip.setAlignment(Pos.CENTER_LEFT);
        chip.setMinHeight(26);
        chip.setPrefHeight(26);
        chip.setPadding(new Insets(0, 8, 0, 8));
        chip.getChildren().add(new Label(app));
        return chip;
    }

    // lucide-ish 12px glyphs; anything without its own mark gets a generic window
    private static final Map<String, String> CATEGORY_ICONS = Map.of(
            "IDE", "M8 3 L11 6 L8 9 M4 3 L1 6 L4 9",
            "AI", "M6 1.2 L7.1 4.7 L10.8 6 L7.1 7.3 L6 10.8 L4.9 7.3 L1.2 6 L4.9 4.7 Z",
            "Terminal", "M2 3.2 L4.8 6 L2 8.8 M6.4 9 H10.2",
            "Browser", "M1 6 A5 5 0 1 0 11 6 A5 5 0 1 0 1 6 M1 6 H11 M6 1 A3.2 5 0 0 0 6 11 A3.2 5 0 0 0 6 1");

    private static SVGPath categoryIcon(String category) {
        SVGPath icon = new SVGPath();
        icon.setContent(CATEGORY_ICONS.getOrDefault(category, "M1 2 H11 V10 H1 Z M1 4.4 H11"));
        icon.setFill(Color.TRANSPARENT);
        icon.setStroke(Color.web("#7A94BC"));
        icon.setStrokeWidth(1.3);
        return icon;
    }

    /** "today", "3d", "2w", "5mo" - the sidebar and the checkpoint block both read this. */
    static String ago(String isoDate) {
        if (isoDate == null || isoDate.isBlank()) return "never";
        try {
            long days = ChronoUnit.DAYS.between(LocalDate.parse(isoDate), LocalDate.now());
            if (days <= 0) return "today";
            if (days < 7) return days + "d";
            if (days < 30) return (days / 7) + "w";
            return (days / 30) + "mo";
        } catch (RuntimeException e) {
            return isoDate;
        }
    }

    // == HUD actions =======================================================

    private void onResume() {
        if (activeProject == null) return;
        try {
            resumeProject(activeProject);
        } catch (IOException | URISyntaxException ex) {
            throw new RuntimeException(ex);
        }
    }

    private void onCheckpoint() {
        if (activeProject == null) return;
        Project projectObj = activeProject;
        // clear first so a browser closed since the last checkpoint drops out: only the
        // extensions still connected answer, and only they put their bucket back
        projectObj.urlsByBrowser.clear();
        wsSessions.forEach(session -> session.send("capture"));
        try {
            projectObj.apps = segregateApps(captureRunningApps());
            projectObj.lastCheckpoint = LocalDate.now().toString();
            saveProject(projectObj);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        HBox row = rowsByProject.get(projectObj.projectName);
        if (row != null) ((Label) row.getChildren().get(2)).setText(ago(projectObj.lastCheckpoint));
        showOverlay(savingPane);
        renderDetail(projectObj);
    }

    private void onDeleteActive() {
        if (activeProject == null) return;
        Project projectObj = activeProject;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete " + projectObj.projectName + "? The folder and its files are not touched.",
                ButtonType.CANCEL, ButtonType.OK);
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        try {
            deleteProject(projectObj, rowsByProject.remove(projectObj.projectName));
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        refreshCount();
        if (projects.isEmpty()) {
            showEmpty(true);
        } else {
            selectProject(projects.get(0));
        }
    }

    /** Title-bar dot: sage while any extension holds the socket, grey otherwise. */
    private void refreshStatusDot() {
        if (!isHud()) return;
        // one name per browser: every window of a browser shares one extension worker, so a
        // reconnect racing its own retry would otherwise list the same brand twice
        List<String> browsers = wsSessions.stream()
                .map(HelloController::wsBrowser).distinct().sorted().toList();
        Platform.runLater(() -> {
            boolean live = !browsers.isEmpty();
            statusDot.getStyleClass().setAll(live ? "dot-live" : "dot-idle");
            statusLabel.setText(live ? "connected" : ":8765");
            statusLabel.getStyleClass().setAll(live ? "port-live" : "port");
            statusTip.setText(live ? String.join(", ", browsers) : "no extension connected");
        });
    }

    // shell/system windows that are never a user's "app" (PowerToys filters the same set)
    private static final Set<String> IGNORED_WINDOWS = Set.of(
            "applicationframehost.exe", "textinputhost.exe", "systemsettings.exe",
            "shellexperiencehost.exe", "searchhost.exe", "lockapp.exe",
            "nvidia overlay.exe", "rtkuwp.exe"
    );

    static boolean isIgnoredWindow(String name) {
        String lower = name.toLowerCase();
        // every real value is ProcessName + '.exe'; anything else means the shell command
        // misfired and we must not persist its output as an app
        if (!lower.endsWith(".exe")) return true;
        // PowerToys surfaces (QuickAccess, WorkspacesEditor, ...) are transient UI, not apps to resume
        return IGNORED_WINDOWS.contains(lower) || lower.startsWith("powertoys.");
    }

    public HashSet<String> captureRunningApps() throws IOException {
        // only processes that own a visible main window count as apps the user opened.
        // .NET's MainWindowTitle is EnumWindows + GetWindowThreadProcessId under the hood,
        // single quotes only: a double quote inside this argument breaks ProcessBuilder's
        // windows quoting and powershell ends up echoing the rest of the script back
        // claude runs as a console app inside a terminal window (Windows Terminal, conhost, ...),
        // so the OS attributes MainWindowTitle to the terminal host, never to claude.exe itself -
        // it must be matched by process name too or it's invisible to capture/resume entirely
        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command",
                "Get-Process | Where-Object { $_.MainWindowTitle -ne '' -or $_.ProcessName -ieq 'claude' } | " +
                        "ForEach-Object { $_.Id.ToString() + '|' + $_.ProcessName + '.exe' }");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
        );
        String line;
        HashSet<String> appset = new HashSet<>();
        long ownPid = ProcessHandle.current().pid();
        while ((line = reader.readLine()) != null) {
            String[] parts = line.split("\\|", 2);
            if (parts.length != 2) continue;
            // our own JavaFX window is owned by java.exe and would otherwise relaunch itself
            if (parts[0].trim().equals(String.valueOf(ownPid))) continue;
            String name = parts[1].trim();
            if (name.isBlank() || isIgnoredWindow(name)) continue;
            appset.add(name);
        }
        return appset;
    }

    static final String OTHER = "Other";

    // purely for labelling: an app missing from this map is still captured, under OTHER
    private static final Map<String, List<String>> KNOWN_APPS = Map.ofEntries(
            Map.entry("Browser", List.of("chrome.exe", "brave.exe", "firefox.exe", "msedge.exe",
                    "opera.exe", "vivaldi.exe", "arc.exe", "zen.exe", "librewolf.exe", "chromium.exe")),
            Map.entry("IDE", List.of("Code.exe", "Code - Insiders.exe", "cursor.exe", "windsurf.exe",
                    "zed.exe", "trae.exe", "fleet.exe", "idea64.exe", "devenv.exe", "eclipse.exe",
                    "sublime_text.exe", "notepad++.exe", "pycharm64.exe", "webstorm64.exe",
                    "phpstorm64.exe", "clion64.exe", "rider64.exe", "goland64.exe", "rubymine64.exe",
                    "studio64.exe", "netbeans64.exe", "qtcreator.exe", "gvim.exe", "emacs.exe")),
            Map.entry("Terminal", List.of("WindowsTerminal.exe", "alacritty.exe", "wezterm-gui.exe",
                    "Hyper.exe", "ConEmu64.exe", "mintty.exe", "Tabby.exe", "warp.exe")),
            Map.entry("AI", List.of("claude.exe", "ChatGPT.exe", "Perplexity.exe", "LM Studio.exe",
                    "ollama app.exe", "Copilot.exe")),
            Map.entry("Database", List.of("dbeaver.exe", "datagrip64.exe", "pgAdmin4.exe", "Ssms.exe",
                    "MySQLWorkbench.exe", "TablePlus.exe", "MongoDBCompass.exe", "redisinsight.exe",
                    "HeidiSQL.exe")),
            Map.entry("API", List.of("Postman.exe", "Insomnia.exe", "Bruno.exe", "Hoppscotch.exe")),
            Map.entry("Containers", List.of("Docker Desktop.exe", "Rancher Desktop.exe",
                    "podman-desktop.exe", "VirtualBox.exe", "vmware.exe")),
            Map.entry("VersionControl", List.of("GitHubDesktop.exe", "SourceTree.exe", "gitkraken.exe",
                    "Fork.exe", "smartgit.exe", "sublime_merge.exe")),
            Map.entry("Design", List.of("Figma.exe", "Photoshop.exe", "Illustrator.exe", "blender.exe",
                    "inkscape.exe", "krita.exe", "Canva.exe")),
            Map.entry("Communication", List.of("discord.exe", "slack.exe", "teams.exe", "ms-teams.exe",
                    "Zoom.exe", "Telegram.exe", "WhatsApp.exe", "Signal.exe", "Skype.exe", "Element.exe")),
            Map.entry("Notes", List.of("notion.exe", "obsidian.exe", "onenote.exe", "Logseq.exe",
                    "Typora.exe", "Joplin.exe", "anytype.exe")),
            Map.entry("Music", List.of("spotify.exe", "amazonmusic.exe", "AppleMusic.exe", "Tidal.exe",
                    "MusicBee.exe", "foobar2000.exe", "vlc.exe"))
    );

    public HashMap<String, HashSet<String>> segregateApps(HashSet<String> appset){
        // KNOWN_APPS only labels an app's category; capture no longer depends on it, so anything
        // unrecognised still gets kept under "Other" instead of being dropped
        HashMap<String, HashSet<String>> result = new HashMap<>();
        for (String category : KNOWN_APPS.keySet()) {
            result.put(category, new HashSet<>());
        }
        result.put(OTHER, new HashSet<>());

        for (String app : appset) {
            result.get(categoryOf(app)).add(app);
        }
        for (String item : result.keySet()) {
            System.out.println(item + " -> " + result.get(item));
        }
        return result;
    }

    // windows reports real executable casing (WindowsTerminal.exe, Spotify.exe), so match lowercased
    static String categoryOf(String app) {
        for (Map.Entry<String, List<String>> entry : KNOWN_APPS.entrySet()) {
            for (String known : entry.getValue()) {
                if (known.equalsIgnoreCase(app)) {
                    return entry.getKey();
                }
            }
        }
        return OTHER;
    }
    public void saveProject(Project projectObj) throws IOException {

        activeProject = projectObj;
        System.out.println("urls: "+projectObj.urls);

        upsertByName(projects, projectObj);

        ArrayList<Project> onDisk = getProjectsJson();
        upsertByName(onDisk, projectObj);
        writeProjects(onDisk);
    }

    // the whole list is always rewritten, never appended to - saveProject and deleteProject
    // both go through here so the file only has one writer
    private void writeProjects(ArrayList<Project> list) throws IOException {
        // Write a sibling file and swap it in. FileWriter(file, false) truncates the real
        // file the moment it opens, so anything that killed the app mid-write - a crash, a
        // force-quit - left projects.json empty and took every project with it.
        File target = projectsFile();
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (Writer writer = new FileWriter(temp, false)) {
            gson.toJson(list, writer);
        }
        Files.move(temp.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public void deleteProject(Project projectObj, HBox projectRow) throws IOException {
        removeByName(projects, projectObj.projectName);

        ArrayList<Project> onDisk = getProjectsJson();
        removeByName(onDisk, projectObj.projectName);
        writeProjects(onDisk);

        // a deleted project must not stay the target of an incoming /tabs post
        if (activeProject != null && activeProject.projectName.equals(projectObj.projectName)) {
            activeProject = null;
        }
        projectList.getChildren().remove(projectRow);
    }

    static void removeByName(ArrayList<Project> list, String projectName) {
        list.removeIf(p -> p.projectName.equals(projectName));
    }

    private void upsertByName(ArrayList<Project> list, Project projectObj) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).projectName.equals(projectObj.projectName)) {
                list.set(i, projectObj);
                return;
            }
        }
        list.add(projectObj);
    }
    public String getAppPath(String appName) throws IOException {
        ProcessBuilder pb = new  ProcessBuilder("cmd.exe","/c", "where", appName);
        Process process = pb.start();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
        );
        String path = reader.readLine();
        return path != null ? path.trim() : null;
    }
    // claude keeps transcripts at ~/.claude/projects/<folder with : and \ swapped for ->/<uuid>.jsonl
    static String claudeSessionSlug(String folderPath) {
        return folderPath.replace(":", "-").replace("\\", "-");
    }

    boolean hasClaudeSession(String folderPath) {
        File dir = new File(System.getProperty("user.home"),
                ".claude/projects/" + claudeSessionSlug(folderPath));
        String[] transcripts = dir.list((d, name) -> name.endsWith(".jsonl"));
        return transcripts != null && transcripts.length > 0;
    }

    // the extension puts its brand in the socket url, so the browser is known at connect time
    // rather than only after its first /tabs post - the User-Agent cannot be used instead
    // because brave reports itself as chrome there
    static String wsBrowser(WsContext ctx) {
        String browser = ctx.queryParam("browser");
        return browser == null || browser.isEmpty() ? "unknown browser" : browser;
    }

    // the bucket for urls whose browser we could not identify - opened with the system default
    static final String DEFAULT_BROWSER = "default";

    // browser names as the extension's getBrowserFromBrands() reports them; it sends "brave"
    // lowercased and the rest capitalised, so every lookup here goes through toLowerCase()
    static final Map<String, String> BROWSER_EXE = Map.of(
            "chrome", "chrome.exe",
            "edge", "msedge.exe",
            "brave", "brave.exe",
            "opera", "opera.exe",
            "firefox", "firefox.exe");

    // projects saved before capture recorded the browser keep their urls in the legacy flat list
    static void migrateLegacyUrls(Project projectObj) {
        if (projectObj.urlsByBrowser == null) {
            projectObj.urlsByBrowser = new HashMap<>();
        }
        if (projectObj.urlsByBrowser.isEmpty() && projectObj.urls != null && !projectObj.urls.isEmpty()) {
            projectObj.urlsByBrowser.put(DEFAULT_BROWSER, new ArrayList<>(projectObj.urls));
        }
    }

    // reg prints "    (Default)    REG_SZ    C:\Program Files\...\chrome.exe" - the path holds
    // spaces, so only the trailing side can be trimmed
    static String parseRegPath(String regOutput) {
        for (String line : regOutput.split("\r?\n")) {
            int marker = line.indexOf("REG_SZ");
            if (marker >= 0) {
                String value = line.substring(marker + "REG_SZ".length()).trim();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }

    // browsers are not on PATH, so `where chrome` finds nothing - windows resolves them through
    // the App Paths registry keys instead. per-user installs (common for chrome and brave) land
    // in HKCU, machine-wide ones in HKLM.
    String browserPath(String browser) throws IOException {
        String exe = browser == null ? null : BROWSER_EXE.get(browser.toLowerCase());
        if (exe == null) {
            return null;
        }
        for (String hive : List.of("HKCU", "HKLM")) {
            ProcessBuilder pb = new ProcessBuilder("reg", "query",
                    hive + "\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\" + exe, "/ve");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            String path = parseRegPath(output.toString());
            if (path != null && new File(path).exists()) {
                return path;
            }
        }
        return null;
    }

    // an app that is already open should be left alone rather than opened a second time.
    // a browser we are about to hand urls to is skipped as well - resumeProject launches it with
    // those tabs as arguments, so launching it here too is what creates the duplicate window.
    // browsers with no saved urls are not skipped: nothing else would bring their window back.
    static boolean shouldLaunch(String app, Set<String> running, Set<String> handledBrowsers) {
        // claude sessions are per-project-folder, not a singleton: a claude window open for
        // some other project must never suppress opening this project's own session.
        if (app.replace(".exe", "").equalsIgnoreCase("claude")) {
            return true;
        }
        for (String open : running) {
            if (open.equalsIgnoreCase(app)) {
                return false;
            }
        }
        return !handledBrowsers.contains(app.toLowerCase());
    }

    // claude was captured next to WindowsTerminal.exe because it ran inside a terminal tab.
    // launched separately, `start wt` only brings back an empty PowerShell window beside the
    // one claude's console opens - so claude is launched *through* wt and wt itself is skipped.
    static List<String> claudeCommand(String folder, boolean hasSession, boolean inTerminal) {
        List<String> command = new ArrayList<>(List.of("cmd.exe", "/c", "start"));
        if (inTerminal) {
            command.addAll(List.of("wt", "-d", folder));
        }
        command.add("claude");
        // --continue reopens the most recent conversation, but errors out with no history yet
        if (hasSession) {
            command.add("--continue");
        }
        return command;
    }

    static boolean hasApp(Project projectObj, String exe) {
        return projectObj.apps.values().stream()
                .flatMap(Set::stream)
                .anyMatch(exe::equalsIgnoreCase);
    }

    public void resumeProject(Project projectObj) throws IOException, URISyntaxException {
        //TODO:add multiple apps checking for path list
        System.out.println("projectName:"+projectObj.projectName);
        HashSet<String> running = captureRunningApps();
        migrateLegacyUrls(projectObj);
        boolean claudeInTerminal = hasApp(projectObj, "claude.exe") && hasApp(projectObj, "WindowsTerminal.exe");

        Set<String> handledBrowsers = new HashSet<>();
        for (Map.Entry<String, ArrayList<String>> entry : projectObj.urlsByBrowser.entrySet()) {
            //TODO:fix chrome settings urls not working
            System.out.println(entry.getKey() + " -> " + entry.getValue());
            String exePath = null;
            try {
                exePath = browserPath(entry.getKey());
                if (exePath == null) {
                    // unknown brand, browser not installed, or urls saved before we tracked the
                    // browser: fall back to whatever windows considers the default
                    for (String url : entry.getValue()) {
                        Desktop.getDesktop().browse(new URI(url));
                    }
                } else {
                    // every url as its own argument, launched with no shell in between: cmd would
                    // split a query string on its & (java only quotes args containing spaces).
                    // one invocation also means one window of tabs rather than one window each.
                    List<String> command = new ArrayList<>();
                    command.add(exePath);
                    command.addAll(entry.getValue());
                    new ProcessBuilder(command).start();
                }
            } catch (IOException | URISyntaxException ex) {
                System.err.println("failed to open urls for " + entry.getKey() + ": " + ex.getMessage());
            }
            if (exePath != null) {
                handledBrowsers.add(new File(exePath).getName().toLowerCase());
            }
        }

            for (String category : projectObj.apps.keySet()) {
                for (String app : projectObj.apps.get(category)) {
                    String appName = app.replace(".exe", "");

                    if (!shouldLaunch(app, running, handledBrowsers)) {
                        System.out.println("skipping (already open or handled): " + app);
                        continue;
                    }

                    if (projectObj.folderPath != null) {
                        try {
                            if (appName.equalsIgnoreCase("claude")) {
                                // open claude CLI in project folder
                                ProcessBuilder pb = new ProcessBuilder(claudeCommand(projectObj.folderPath,
                                        hasClaudeSession(projectObj.folderPath), claudeInTerminal));
                                pb.directory(new File(projectObj.folderPath));
                                pb.start();

                            } else if (appName.equalsIgnoreCase("Code")) {
                                // open VS Code in project folder
                                System.out.println("opening VS Code in: " + projectObj.folderPath);
                                new ProcessBuilder("cmd.exe", "/c", "code", projectObj.folderPath).start();

                            } else if (appName.equalsIgnoreCase("WindowsTerminal")) {
                                if (claudeInTerminal) {
                                    System.out.println("skipping wt: claude launches inside it");
                                    continue;
                                }
                                // WindowsTerminal.exe is the process name, but "wt" is the actual
                                // launch command - "start WindowsTerminal" can't resolve it
                                new ProcessBuilder("cmd.exe", "/c", "start", "wt").start();

                            } else {
                                new ProcessBuilder("cmd.exe", "/c", "start", appName).start();
                            }
                        } catch (IOException ex) {
                            // one app failing to launch (bad folder, missing exe, ...) must not
                            // silently abort every app still left in the loop
                            System.err.println("failed to launch " + app + ": " + ex.getMessage());
                        }
                    }
                }
            }
    }

    public String openFolder(ActionEvent event) {

        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Select Application Folder");

        // Get the Window reference from the action event
        Window ownerWindow = ((Node) event.getSource()).getScene().getWindow();


        File selectedDirectory = directoryChooser.showDialog(ownerWindow);

        if (selectedDirectory != null) {
           return selectedDirectory.getAbsolutePath() ;
        } else {
            System.out.println("No folder chosen.");
        }
        return "NULL";
    }
    public void startServer(){
        System.out.println("server starting...");
        Javalin app = Javalin.create(config -> {
            config.bundledPlugins.enableCors(cors -> {
                cors.addRule(rule -> {
                    rule.anyHost();
                });
            });
        }).start(8765);
        app.ws("/ws", ws -> {
            System.out.println("WEB starting...");

            ws.onConnect(ctx -> {
                wsSessions.add(ctx);
                System.out.println("extension connected: " + wsBrowser(ctx)
                        + " (" + wsSessions.size() + " connected)");
                refreshStatusDot();
                });
            ws.onClose(ctx -> {
                wsSessions.remove(ctx);
                System.out.println("extension disconnected: " + wsBrowser(ctx)
                        + " (" + wsSessions.size() + " connected)");
                refreshStatusDot();
            });
        });
        // the extension popup reads this to name the project its tabs will land on, and
        // treats any reply at all as "the desktop app is running"
        app.get("/project", ctx -> ctx.result(activeProject == null ? "" : activeProject.projectName));
        app.post("/tabs", ctx -> {
            System.out.println(ctx.body());
            // gson gives back Object values here - browser is a String, urls a List, so the
            // map cannot be typed as Map<String, ArrayList<String>> without a bad cast
            Map<String, Object> data = gson.fromJson(ctx.body(), Map.class);
            @SuppressWarnings("unchecked")
            List<String> incoming = (List<String>) data.get("urls");
            Object browser = data.get("browser");
            if (activeProject != null && incoming != null) {
                // browser is absent when getBrowserFromBrands() recognises no brand
                activeProject.urlsByBrowser.put(
                        browser != null ? browser.toString() : DEFAULT_BROWSER,
                        new ArrayList<>(incoming));
                saveProject(activeProject);
                // tabs arrive after the checkpoint button has already returned, so the
                // card has to be redrawn off the javalin thread
                if (isHud()) {
                    Project posted = activeProject;
                    Platform.runLater(() -> renderDetail(posted));
                }
            }
        });
    }

}
