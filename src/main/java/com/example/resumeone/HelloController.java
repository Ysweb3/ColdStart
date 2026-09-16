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
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.css.PseudoClass;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import java.util.concurrent.CopyOnWriteArrayList;

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
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;


/**
 * The whole app in one class. It wears four hats, in this order down the file:
 *
 * <ol>
 *   <li><b>UI controller</b> — bound to hud-view.fxml via fx:controller. Every @FXML field is a
 *       node from that file; the "HUD rendering" / "HUD actions" sections draw the project card
 *       and react to its buttons.</li>
 *   <li><b>Persistence</b> — projects live in one Gson-serialised list at
 *       %APPDATA%\ResumeWork\projects.json (see {@link #projectsFile()}, {@link #saveProject}).</li>
 *   <li><b>Capture / resume</b> — {@link #captureRunningApps()} asks PowerShell which processes
 *       own a window, {@link #segregateApps} labels them, {@link #resumeProject} relaunches them
 *       and reopens the saved browser tabs.</li>
 *   <li><b>Embedded server</b> — {@link #startServer()} runs Javalin on :8765; the browser
 *       extension holds a WebSocket at /ws and POSTs its tabs to /tabs.</li>
 * </ol>
 *
 * Threading: JavaFX owns the UI thread; Javalin callbacks run on its own threads, so anything
 * they touch in the UI goes through {@code Platform.runLater}.
 * Windows-only: cmd.exe, powershell.exe, reg.exe and %APPDATA% are all assumed.
 */
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
    @FXML public VBox savingPane, deletePane, newProjectPane, confirmPane;
    @FXML public Label savingNote, confirmTitle, confirmSub, confirmBody;
    @FXML public Button btnConfirmCancel, btnConfirmOk;
    // 1e / 1l (resume in progress, resume done) - see the resume section below
    @FXML public VBox resumingPane, restoredPane, resumingRows, restoredRows;
    @FXML public HBox titleState, resumingMeter, restoredMeter, countdownTrack;
    @FXML public Region titleStateDot, resumingFill, restoredFill, restoredFailFill, countdownFill;
    @FXML public Label titleStateVerb, titleStateName, resumingCount, resumingPct, resumingName,
            resumingSkipped, restoredCount, restoredTook, restoredSpec, restoredNote;
    @FXML public Button btnResumeStop, btnStayHere, btnOpenProject;
    private final DoubleProperty resumingFrac = new SimpleDoubleProperty();
    private final DoubleProperty restoredFrac = new SimpleDoubleProperty();
    private final DoubleProperty restoredFailFrac = new SimpleDoubleProperty();
    private final DoubleProperty countdownFrac = new SimpleDoubleProperty();
    private volatile boolean resuming, stopRequested;
    private Timeline countdown;
    private static final PseudoClass PC_LAUNCHING = PseudoClass.getPseudoClass("launching");
    private static final PseudoClass PC_FAILED    = PseudoClass.getPseudoClass("failed");
    /** the design's --ease-out */
    private static final Interpolator EASE_OUT = Interpolator.SPLINE(0.16, 1, 0.3, 1);
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

    /** Folder picked in the legacy dialog; the HUD dialog reads npFolder instead. */
    public String projectFolder;

    public Gson gson = new Gson();

    /** The project the card shows and that an incoming POST /tabs lands on. Null when none. */
    private Project activeProject;

    /** In-memory copy of projects.json, in sidebar order. */
    public ArrayList<Project> projects = new ArrayList<>();

    /** Called by FXMLLoader once every @FXML field is injected: load projects, snapshot the
        running apps (only logged - proves capture works), start the server, wire buttons. */
    @FXML
    public void initialize() throws IOException {

        for (Project project : getProjectsJson()) {
            createProjectUI(project, false);
        }
        segregateApps(captureRunningApps());
        startServer();

        if (!isHud()) return;
        clickable(btnResume, this::onResume);
        clickable(btnCheckpoint, this::onCheckpoint);
        // The delete confirmation is a view now, not an Alert. Its Delete button is
        // deliberately inert - onDeleteActive() below still holds the working delete and
        // one setOnAction re-arms it.
        clickable(btnDelete, this::showDeleteView);
        clickable(btnSettings, () -> showBase(settingsPane));
        // the scene arrives after initialize(); accelerators and Escape hang off it once
        detailPane.sceneProperty().addListener((o, was, scene) -> {
            if (scene != null && was == null) installKeys(scene);
        });
        btnSavingDone.setOnAction(e -> backToProjects());
        btnDeleteCancel.setOnAction(e -> backToProjects());
        btnSettingsBack.setOnAction(e -> backToProjects());
        btnNpCancel.setOnAction(e -> backToProjects());
        btnConfirmCancel.setOnAction(e -> showOverlay(null));
        btnNpBrowse.setOnAction(e -> {
            String chosen = openFolder(e);          // returns the "NULL" sentinel on cancel
            if (!"NULL".equals(chosen)) npFolder.setText(chosen);
        });
        btnNpCreate.disableProperty().bind(Bindings.createBooleanBinding(
                () -> npName.getText().trim().isEmpty(), npName.textProperty()));
        btnNpCreate.setOnAction(e -> createFromDialog());
        btnResumeStop.setGraphic(icon(ICON_X, 13, TX2));
        btnResumeStop.setOnAction(e -> stopRequested = true);
        btnStayHere.setOnAction(e -> stopCountdown());
        btnOpenProject.setGraphic(icon(ICON_CHEVRON, 13, TX));
        btnOpenProject.setOnAction(e -> { stopCountdown(); backToProjects(); });
        // meters are fractions bound to their track, so they are right before first layout
        resumingFill.prefWidthProperty().bind(resumingMeter.widthProperty().multiply(resumingFrac));
        restoredFill.prefWidthProperty().bind(restoredMeter.widthProperty().multiply(restoredFrac));
        restoredFailFill.prefWidthProperty().bind(restoredMeter.widthProperty().multiply(restoredFailFrac));
        countdownFill.prefWidthProperty().bind(countdownTrack.widthProperty().multiply(countdownFrac));
        statusTip = new Tooltip();
        statusTip.setShowDelay(Duration.millis(150));
        Tooltip.install(statusBox, statusTip);
        Tooltip.install(btnSettings, tip("Settings"));
        Tooltip.install(btnDelete, tip("Delete project"));
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
    // == Persistence =======================================================

    /** %APPDATA%\ResumeWork\projects.json - the single file every project is stored in. */
    @FXML
    static File projectsFile() {
        File dir = new File(System.getenv("APPDATA"), "ResumeWork");
        dir.mkdirs();
        return new File(dir, "projects.json");
    }

    /** Reads projects.json from disk (empty list if missing/blank) and upgrades old entries. */
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


    // == New project flow ==================================================
    //   New Project button -> onNewProjectClick (shows newProjectPane scrim)
    //   Create + checkpoint -> createFromDialog -> createProjectUI(isNew=true)
    //                          -> captures apps, saves, adds sidebar row, selects it
    //                          -> then asks every extension for its tabs

    private static final String NO_FOLDER = "no folder chosen";

    /** Sidebar "+ New Project" and the empty-state button both land here (onAction in fxml). */
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
    /**
     * Adds a project to the sidebar. isNew=true means "just created": capture the running apps,
     * persist, and select it. isNew=false means "loaded from disk at startup": just list it.
     */
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
        clickable(row, () -> selectProject(projectObj));

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
    //   The centre of the window is a StackPane with two layers:
    //     base views  (detailPane | emptyPane | settingsPane) - exactly one visible
    //     overlays    (savingPane | deletePane | newProjectPane) - at most one, drawn on top
    //   Nothing is created or destroyed; panes are toggled with visible+managed.

    // == keyboard ============================================================
    //   Every HBox/StackPane that acts as a button goes through clickable(): mouse, Enter and
    //   Space all land on the same action, and the node is in the Tab order. Focus is shown
    //   only when it came from the keyboard (:focus-visible), the way the web does it.

    private static void clickable(Node node, Runnable action) {
        node.setFocusTraversable(true);
        node.setOnMouseClicked(e -> {
            node.requestFocus();
            action.run();
        });
        node.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) {
                action.run();
                e.consume();
            }
        });
    }

    /** Ctrl+R resume, Ctrl+S checkpoint, Ctrl+N new project; Escape backs out of anything. */
    private void installKeys(javafx.scene.Scene scene) {
        // accelerators are scene-wide, so they stay quiet while a dialog is up
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.R, KeyCombination.SHORTCUT_DOWN),
                () -> { if (!overlayOpen()) onResume(); });
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN),
                () -> { if (!overlayOpen()) onCheckpoint(); });
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN),
                () -> { if (!overlayOpen()) onNewProjectClick(); });
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() != KeyCode.ESCAPE) return;
            if (overlayOpen()) {
                showOverlay(null);
                e.consume();
                return;
            }
            if (settingsPane.isVisible() || restoredPane.isVisible()) {
                stopCountdown();
                backToProjects();
                e.consume();
            }
        });
    }

    private boolean overlayOpen() {
        return confirmPane.isVisible() || newProjectPane.isVisible()
                || deletePane.isVisible() || savingPane.isVisible();
    }

    /** detailPane / emptyPane / settingsPane are the base views: exactly one shows. */
    private void showBase(Node target) {
        for (Node pane : List.of(detailPane, emptyPane, settingsPane, resumingPane, restoredPane)) {
            pane.setVisible(pane == target);
            pane.setManaged(pane == target);
        }
        showOverlay(null);
        if (target != resumingPane && target != restoredPane) titleState(null, null, false);
    }

    /** "RESUMING atlas-api" in the title bar; null verb puts the port cluster back. */
    private void titleState(String verb, String name, boolean dot) {
        boolean on = verb != null;
        titleState.setVisible(on);
        titleState.setManaged(on);
        statusBox.setVisible(!on);
        statusBox.setManaged(!on);
        titleStateDot.setVisible(dot);
        titleStateDot.setManaged(dot);
        if (on) {
            titleStateVerb.setText(verb);
            titleStateName.setText(name);
        }
    }

    /** savingPane / deletePane / newProjectPane are scrims stacked over whichever base is showing. */
    private void showOverlay(Node target) {
        for (Node pane : List.of(savingPane, deletePane, newProjectPane, confirmPane)) {
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

    /** Highlights the sidebar row, makes it the active project and draws its card. */
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

    /** Fills every label on the project card from the Project; safe to call repeatedly
        (checkpoint and the /tabs handler both re-run it). */
    private void renderDetail(Project projectObj) {
        migrateLegacyUrls(projectObj);

        projectNameLabel.setText(projectObj.projectName);
        projectPathLabel.setText(projectObj.folderPath == null ? "no folder" : projectObj.folderPath);

        int apps = countApps(projectObj);
        int tabs = countTabs(projectObj);

        resumeSpec.setText(apps + " APPS \u00b7 " + tabs + " TABS \u00b7 "
                + (projectObj.folderPath == null ? 0 : 1) + " FOLDER");
        String age = ago(projectObj.lastCheckpoint);
        checkpointSub.setText("never".equals(age) ? "no checkpoint yet" : "overwrite \u00b7 " + when(age));
        capturedNote.setText("never".equals(age) ? "" : "captured " + when(age));
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
                    styled(new Label(categoryLabel(category)), "cat-label"));

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

    /** "VersionControl" -> "VERSION CONTROL": the map key, spaced for reading. */
    static String categoryLabel(String category) {
        return category.replaceAll("([a-z])([A-Z])", "$1 $2").toUpperCase();
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

    private static Tooltip tip(String text) {
        Tooltip t = new Tooltip(text);
        t.setShowDelay(Duration.millis(150));
        return t;
    }

    /** "today" or "3d ago": ago() as a phrase, for the copy that follows a verb. */
    static String when(String age) {
        return "today".equals(age) ? "today" : age + " ago";
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
    //   Resume     -> onResume     -> resumeProject
    //   Checkpoint -> onCheckpoint -> re-capture apps, ask extensions for tabs, save
    //   Trash      -> showDeleteView (confirm scrim); onDeleteActive holds the actual delete

    // == resume: 1e in progress -> 1l done ==================================
    //   onResume runs planResume() on a worker thread, one LaunchStep per LAUNCH_GAP_MS. Every
    //   state change re-renders resumingPane (1e). The run finishes into restoredPane (1l),
    //   which keeps failures visible with a Retry and auto-returns to the project in 3s.

    // ponytail: 750ms between spawns is a first guess at what stops ten processes landing
    // on the machine at once; the design paces its prototype at 220ms for looks. Tune by feel.
    static final long LAUNCH_GAP_MS = 750;

    private void onResume() {
        if (activeProject == null || resuming) return;
        resuming = true;
        stopRequested = false;
        ResumeRun r = new ResumeRun(activeProject);
        titleState("RESUMING", r.project.projectName, false);
        resumingName.setText(r.project.projectName);
        showBase(resumingPane);
        renderResuming(r);

        Thread worker = new Thread(() -> {
            try {
                // the PowerShell capture takes seconds: off the FX thread it no longer freezes the HUD
                r.plan = planResume(r.project, captureRunningApps(), r.log);
                Platform.runLater(() -> renderResuming(r));
                for (LaunchStep step : r.plan.steps()) {
                    if (stopRequested) {
                        r.log.add("stopped by user");
                        break;
                    }
                    step.state = LaunchStep.State.LAUNCHING;
                    Platform.runLater(() -> renderResuming(r));
                    step.run();
                    r.log.add(step.state == LaunchStep.State.DONE
                            ? step.label + " → " + step.command
                            : step.label + " failed: " + step.error);
                    Platform.runLater(() -> renderResuming(r));
                    Thread.sleep(LAUNCH_GAP_MS);
                }
            } catch (IOException ex) {
                r.log.add("resume failed: " + ex.getMessage());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                r.tookMs = (System.nanoTime() - r.startNanos) / 1_000_000;
                resuming = false;
                Platform.runLater(() -> showRestored(r));
            }
        }, "resume");
        worker.setDaemon(true);
        worker.start();
    }

    /** 1e. Rebuilt whole on every change - a run is a dozen rows at most. */
    private void renderResuming(ResumeRun r) {
        int total = r.total();
        long started = r.started();
        resumingCount.setText(started + " / " + (total == 0 ? "?" : String.valueOf(total)));
        double frac = total == 0 ? 0 : (double) started / total;
        resumingPct.setText(Math.round(frac * 100) + "%");
        new Timeline(new KeyFrame(Duration.millis(220), new KeyValue(resumingFrac, frac, EASE_OUT))).play();

        resumingRows.getChildren().clear();
        List<String> queued = new ArrayList<>();
        for (LaunchStep step : r.plan.steps()) {
            if (step.state == LaunchStep.State.QUEUED) {
                queued.add(step.label);
            } else {
                resumingRows.getChildren().add(stepRow(step));
            }
        }
        if (!queued.isEmpty()) {
            resumingRows.getChildren().add(foldRow(ICON_CLOCK, queued.size() + " queued", names(queued)));
        }
        resumingRows.getChildren().add(foldRow(ICON_TERMINAL, "Resume log", r.log.size() + " lines"));

        List<String> skipped = r.plan.skipped();
        resumingSkipped.setText(skipped.isEmpty() ? "" : "skipping " + skipped.size()
                + " already open · " + String.join(" · ", skipped));
    }

    /** 1l. Orange closes the verb; failures stay on screen; three seconds later, back to 1b. */
    private void showRestored(ResumeRun r) {
        titleState("RESUMED", r.project.projectName, true);
        showBase(restoredPane);
        renderRestored(r);
        startCountdown(r);
    }

    private void renderRestored(ResumeRun r) {
        int total = r.total();
        restoredCount.setText(r.done() + " / " + total);
        restoredTook.setText(String.format("took %.1fs", r.tookMs / 1000.0));
        restoredFrac.set(total == 0 ? 0 : (double) r.done() / total);
        restoredFailFrac.set(total == 0 ? 0 : (double) r.failed() / total);
        String folder = r.project.folderPath == null ? "no folder" : r.project.folderPath;
        restoredSpec.setText(r.appsDone() + " apps · " + countTabs(r.project) + " tabs · " + folder);

        restoredRows.getChildren().clear();
        List<String> browsers = new ArrayList<>();
        for (LaunchStep step : r.plan.steps()) {
            if (step.state == LaunchStep.State.FAILED) restoredRows.getChildren().add(failRow(r, step));
            if (step.state == LaunchStep.State.DONE && step.browser) browsers.add(step.label);
        }
        restoredRows.getChildren().add(foldRow(ICON_APP_WINDOW, r.appsDone() + " apps launched", "on the project card"));
        restoredRows.getChildren().add(foldRow(ICON_GLOBE, countTabs(r.project) + " tabs handed back",
                browsers.isEmpty() ? "no browser connected" : String.join(" · ", browsers)));
        long failed = r.failed();
        restoredRows.getChildren().add(foldRow(ICON_TERMINAL, "Resume log", r.log.size() + " lines"
                + (failed == 0 ? "" : " · " + failed + (failed == 1 ? " failure" : " failures"))));
    }

    private void startCountdown(ResumeRun r) {
        String name = r.project.projectName;
        countdownFrac.set(0);
        countdown = new Timeline(
                new KeyFrame(Duration.ZERO,       e -> restoredNote.setText("returning to " + name + " in 3s")),
                new KeyFrame(Duration.seconds(1), e -> restoredNote.setText("returning to " + name + " in 2s")),
                new KeyFrame(Duration.seconds(2), e -> restoredNote.setText("returning to " + name + " in 1s")),
                new KeyFrame(Duration.seconds(3), e -> backToProjects(),
                        new KeyValue(countdownFrac, 1, Interpolator.LINEAR)));
        countdown.play();
    }

    private void stopCountdown() {
        if (countdown != null) {
            countdown.stop();
            countdown = null;
        }
        restoredNote.setText("");
        countdownFrac.set(0);
    }

    private void retry(ResumeRun r, LaunchStep step) {
        stopCountdown();
        Thread t = new Thread(() -> {
            step.run();
            r.log.add(step.state == LaunchStep.State.DONE
                    ? step.label + " → " + step.command + " (retry)"
                    : step.label + " failed again: " + step.error);
            Platform.runLater(() -> renderRestored(r));
        }, "retry");
        t.setDaemon(true);
        t.start();
    }

    // ── rows ────────────────────────────────────────────────────────────

    private HBox stepRow(LaunchStep step) {
        HBox row = rowShell("step-row");
        row.pseudoClassStateChanged(PC_LAUNCHING, step.state == LaunchStep.State.LAUNCHING);
        row.pseudoClassStateChanged(PC_FAILED, step.state == LaunchStep.State.FAILED);
        SVGPath mark = switch (step.state) {
            case LAUNCHING -> icon(ICON_PLAY, 14, GO);
            case FAILED -> icon(ICON_ALERT, 14, BAD);
            default -> icon(ICON_CHECK, 14, TX3);
        };
        String cmd = switch (step.state) {
            case LAUNCHING -> "launching · " + step.command;
            case FAILED -> "failed to launch";
            default -> step.command;
        };
        Label name = styled(new Label(step.label), "step-label");
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(name, Priority.ALWAYS);
        row.getChildren().addAll(mark, name, styled(new Label(cmd), "step-cmd"));
        return row;
    }

    private HBox foldRow(String iconPath, String title, String meta) {
        HBox row = rowShell("fold-row");
        Label t = styled(new Label(title), "fold-title");
        t.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(t, Priority.ALWAYS);
        row.getChildren().addAll(icon(ICON_CHEVRON, 13, HUD), icon(iconPath, 14, TX3), t,
                styled(new Label(meta), "fold-meta"));
        return row;
    }

    private HBox failRow(ResumeRun r, LaunchStep step) {
        HBox row = rowShell("fail-row");
        row.setMinHeight(54);
        row.setPrefHeight(54);
        Label why = styled(new Label("failed to launch"), "fail-why");
        why.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(why, Priority.ALWAYS);
        Button retry = styled(new Button("Retry"), "btn-ghost");
        retry.setGraphic(icon(ICON_REFRESH, 13, TX));
        retry.setGraphicTextGap(8);
        retry.setOnAction(e -> retry(r, step));
        row.getChildren().addAll(icon(ICON_ALERT, 14, BAD), styled(new Label(step.label), "step-label"), why, retry);
        return row;
    }

    private static HBox rowShell(String styleClass) {
        HBox row = new HBox(10);
        row.getStyleClass().add(styleClass);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMinHeight(46);
        row.setPrefHeight(46);
        row.setPadding(new Insets(0, 16, 0, 12));
        return row;
    }

    private static <T extends Labeled> T styled(T node, String styleClass) {
        node.getStyleClass().add(styleClass);
        return node;
    }

    /** "GitHubDesktop · obsidian · discord", three names then a count. */
    private static String names(List<String> labels) {
        List<String> shown = new ArrayList<>();
        for (String l : labels.subList(0, Math.min(3, labels.size()))) shown.add(l.replace(".exe", ""));
        String out = String.join(" · ", shown);
        return labels.size() > 3 ? out + " +" + (labels.size() - 3) : out;
    }

    // lucide, native 24 box, scaled to size; stroke 1.75 per brand-iconography
    private static final String
            ICON_CHECK = "M20 6 L9 17 L4 12",
            ICON_PLAY = "M6 3 L20 12 L6 21 Z",
            ICON_ALERT = "M12 2 A10 10 0 1 0 12 22 A10 10 0 1 0 12 2 Z M12 8 V12 M12 16 V16.01",
            ICON_CHEVRON = "M9 18 L15 12 L9 6",
            ICON_CLOCK = "M12 2 A10 10 0 1 0 12 22 A10 10 0 1 0 12 2 Z M12 6 V12 L16 14",
            ICON_TERMINAL = "M4 17 L10 11 L4 5 M12 19 H20",
            ICON_APP_WINDOW = "M2 6 A2 2 0 0 1 4 4 H20 A2 2 0 0 1 22 6 V18 A2 2 0 0 1 20 20 H4 A2 2 0 0 1 2 18 Z M10 4 V8 M2 8 H22 M6 4 V8",
            ICON_GLOBE = "M12 2 A10 10 0 1 0 12 22 A10 10 0 1 0 12 2 Z M2 12 H22 M12 2 A14.5 14.5 0 0 0 12 22 A14.5 14.5 0 0 0 12 2",
            ICON_REFRESH = "M3 12 A9 9 0 0 1 12 3 A9.75 9.75 0 0 1 18.74 5.74 L21 8 M21 3 V8 H16 M21 12 A9 9 0 0 1 12 21 A9.75 9.75 0 0 1 5.26 18.26 L3 16 M8 16 H3 V21",
            ICON_X = "M18 6 L6 18 M6 6 L18 18";
    private static final String TX = "#E8D8C9", TX2 = "#A99B8D", TX3 = "#6F655C",
            GO = "#F3701E", BAD = "#D96C5E", HUD = "#7A94BC";

    private static SVGPath icon(String path, double size, String color) {
        SVGPath p = new SVGPath();
        p.setContent(path);
        p.setFill(Color.TRANSPARENT);
        p.setStroke(Color.web(color));
        p.setStrokeWidth(1.75);
        p.setStrokeLineCap(StrokeLineCap.ROUND);
        p.setStrokeLineJoin(StrokeLineJoin.ROUND);
        p.setScaleX(size / 24);
        p.setScaleY(size / 24);
        return p;
    }

    static int countApps(Project projectObj) {
        if (projectObj == null || projectObj.apps == null) return 0;
        return projectObj.apps.values().stream().mapToInt(Set::size).sum();
    }

    static int countTabs(Project projectObj) {
        if (projectObj == null) return 0;
        return projectObj.urlsByBrowser.values().stream().mapToInt(List::size).sum();
    }

    private void onCheckpoint() {
        if (activeProject == null) return;
        Project projectObj = activeProject;
        // A checkpoint from another day with something in it gets one beat before it is
        // replaced; today's, or an empty one, just overwrites.
        String age = ago(projectObj.lastCheckpoint);
        int apps = countApps(projectObj), tabs = countTabs(projectObj);
        boolean worthAsking = !"today".equals(age) && !"never".equals(age) && (apps > 0 || tabs > 0);
        if (!worthAsking) {
            writeCheckpoint(projectObj);
            return;
        }
        confirm("Checkpoint again?",
                "checkpoint " + when(age) + " \u00b7 " + apps + " apps \u00b7 " + tabs + " tabs",
                "What is open right now replaces the checkpoint from " + age
                        + " ago. Tabs from browsers that are not connected are kept.",
                "Overwrite", () -> writeCheckpoint(projectObj));
    }

    private void writeCheckpoint(Project projectObj) {
        // Only a browser holding the socket can answer the capture push, so only its bucket is
        // dropped for re-posting. Anything else keeps its saved tabs: a wiped bucket nobody
        // could refill is data loss, a stale one from a closed browser is merely stale.
        Set<String> connected = new HashSet<>();
        for (WsContext session : wsSessions) {
            String b = wsBrowser(session);
            connected.add("unknown browser".equals(b) ? DEFAULT_BROWSER : b);
        }
        List<String> kept = keepUnconnectedTabs(projectObj.urlsByBrowser, connected);
        wsSessions.forEach(session -> session.send("capture"));
        try {
            projectObj.apps = segregateApps(captureRunningApps());
            projectObj.lastCheckpoint = LocalDate.now().toString();
            saveProject(projectObj);
        } catch (IOException ex) {
            // the card must not claim "saved" over a write that did not happen
            confirm("Checkpoint not written", projectsFile().getPath(),
                    "Nothing changed on disk. " + ex.getMessage(),
                    "Retry", () -> writeCheckpoint(projectObj));
            return;
        }
        HBox row = rowsByProject.get(projectObj.projectName);
        if (row != null) ((Label) row.getChildren().get(2)).setText(ago(projectObj.lastCheckpoint));

        String note = kept.isEmpty() ? ""
                : connected.isEmpty() ? "tabs kept from last checkpoint \u00b7 no extension connected"
                : "tabs kept for " + String.join(" \u00b7 ", kept) + " \u00b7 not connected";
        savingNote.setText(note);
        savingNote.setVisible(!note.isEmpty());
        savingNote.setManaged(!note.isEmpty());
        showOverlay(savingPane);
        renderDetail(projectObj);
    }

    /** Drops the buckets a connected browser is about to re-post; returns the browsers whose
        saved tabs were kept because nobody on the socket could refill them. */
    static List<String> keepUnconnectedTabs(Map<String, ArrayList<String>> urlsByBrowser, Set<String> connected) {
        List<String> kept = new ArrayList<>();
        urlsByBrowser.forEach((browser, urls) -> {
            if (!connected.contains(browser) && !urls.isEmpty()) kept.add(browser);
        });
        urlsByBrowser.keySet().removeIf(connected::contains);
        return kept;
    }

    /** The generic confirm scrim. The verb button carries the caller's word and action. */
    private void confirm(String title, String sub, String body, String verb, Runnable onOk) {
        confirmTitle.setText(title);
        confirmSub.setText(sub);
        confirmBody.setText(body);
        btnConfirmOk.setText(verb);
        btnConfirmOk.setOnAction(e -> {
            showOverlay(null);
            onOk.run();
        });
        showOverlay(confirmPane);
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
            statusLabel.setText(!live ? ":8765 \u00b7 no extension"
                    : browsers.size() == 1 ? "connected \u00b7 " + browsers.get(0)
                    : "connected \u00b7 " + browsers.size() + " browsers");
            statusLabel.getStyleClass().setAll(live ? "port-live" : "port");
            statusTip.setText(live ? String.join(", ", browsers) : "no extension connected");
        });
    }

    // == Capture ===========================================================
    //   captureRunningApps() -> set of "name.exe"  -> segregateApps() -> {category -> set}
    //   The result is what gets stored in Project.apps and shown as chips on the card.

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

    /**
     * Every process that owns a visible window, as "name.exe" (e.g. "Code.exe", "chrome.exe").
     * Runs a PowerShell one-liner that prints "pid|name.exe" per line and parses it.
     */
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

    /** Buckets captured exe names by category: {"IDE": [Code.exe], "Browser": [chrome.exe], ...}.
        Every category key is always present (possibly empty) so the UI can iterate blindly. */
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
    /** Makes the project active and writes it to projects.json (replacing any entry with the
        same name). Both the in-memory list and the on-disk list are updated. */
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
    // == Resume ============================================================
    //   resumeProject(): 1. capture what is running now (so we don't open it twice)
    //                    2. open each browser with its saved urls as arguments
    //                    3. `start` every other captured app, with special cases for
    //                       claude (project folder, --continue), VS Code (project folder), wt

    /** `where <name>` - first PATH hit or null. Currently unused; browsers go via browserPath(). */
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

    @FunctionalInterface
    interface Launch { void start() throws IOException, URISyntaxException; }

    /** One process launch: the row's label, the command it shows, and how it went. */
    static final class LaunchStep {
        enum State { QUEUED, LAUNCHING, DONE, FAILED }
        final String label, command;
        final boolean browser;
        final Launch launch;
        volatile State state = State.QUEUED;
        volatile String error;

        LaunchStep(String label, String command, boolean browser, Launch launch) {
            this.label = label;
            this.command = command;
            this.browser = browser;
            this.launch = launch;
        }

        /** one app failing to launch (bad folder, missing exe, ...) must not abort the rest */
        void run() {
            state = State.LAUNCHING;
            try {
                launch.start();
                state = State.DONE;
            } catch (IOException | URISyntaxException ex) {
                error = ex.getMessage();
                state = State.FAILED;
                System.err.println("failed to launch " + label + ": " + error);
            }
        }
    }

    record ResumePlan(List<LaunchStep> steps, List<String> skipped) {}

    /** Everything one press of Resume produced; 1e and 1l render from it. */
    static final class ResumeRun {
        final Project project;
        final long startNanos = System.nanoTime();
        final List<String> log = new CopyOnWriteArrayList<>();
        volatile ResumePlan plan = new ResumePlan(List.of(), List.of());
        long tookMs;

        ResumeRun(Project project) { this.project = project; }

        int total()     { return plan.steps().size(); }
        long started()  { return plan.steps().stream().filter(s -> s.state != LaunchStep.State.QUEUED).count(); }
        long done()     { return plan.steps().stream().filter(s -> s.state == LaunchStep.State.DONE).count(); }
        long failed()   { return plan.steps().stream().filter(s -> s.state == LaunchStep.State.FAILED).count(); }
        long appsDone() { return plan.steps().stream().filter(s -> s.state == LaunchStep.State.DONE && !s.browser).count(); }
    }

    /** Brings a saved project back: browsers with their tabs, then every other captured app.
        Synchronous - the HUD's Resume runs the same plan staggered on its own thread. */
    public void resumeProject(Project projectObj) throws IOException {
        for (LaunchStep step : planResume(projectObj, captureRunningApps(), new ArrayList<>()).steps()) {
            step.run();
        }
    }

    /** Decides what to launch, in order, without starting anything. */
    ResumePlan planResume(Project projectObj, Set<String> running, List<String> log) {
        //TODO:add multiple apps checking for path list
        System.out.println("projectName:" + projectObj.projectName);
        migrateLegacyUrls(projectObj);
        boolean claudeInTerminal = hasApp(projectObj, "claude.exe") && hasApp(projectObj, "WindowsTerminal.exe");
        List<LaunchStep> steps = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        Set<String> handledBrowsers = new HashSet<>();
        for (Map.Entry<String, ArrayList<String>> entry : projectObj.urlsByBrowser.entrySet()) {
            //TODO:fix chrome settings urls not working
            String browser = entry.getKey();
            List<String> urls = entry.getValue();
            System.out.println(browser + " -> " + urls);
            String exePath;
            try {
                exePath = browserPath(browser);
            } catch (IOException ex) {
                log.add("failed to open urls for " + browser + ": " + ex.getMessage());
                System.err.println(log.get(log.size() - 1));
                continue;
            }
            if (exePath != null) {
                handledBrowsers.add(new File(exePath).getName().toLowerCase());
            }
            String command = urls.size() + " urls · " + (exePath == null ? "default browser" : "one window");
            steps.add(new LaunchStep(browser, command, true, () -> {
                if (exePath == null) {
                    // unknown brand, browser not installed, or urls saved before we tracked the
                    // browser: fall back to whatever windows considers the default
                    for (String url : urls) {
                        Desktop.getDesktop().browse(new URI(url));
                    }
                } else {
                    // every url as its own argument, launched with no shell in between: cmd would
                    // split a query string on its & (java only quotes args containing spaces).
                    // one invocation also means one window of tabs rather than one window each.
                    List<String> cmd = new ArrayList<>();
                    cmd.add(exePath);
                    cmd.addAll(urls);
                    new ProcessBuilder(cmd).start();
                }
            }));
        }

        // CATEGORY_ORDER only, so the rows walk the card top to bottom. Other is captured and
        // shown but never relaunched - the card says so, and now it is true.
        String folder = projectObj.folderPath;
        for (String category : CATEGORY_ORDER) {
            HashSet<String> bucket = projectObj.apps.get(category);
            if (bucket == null) continue;
            for (String app : new TreeSet<>(bucket)) {
                if (!shouldLaunch(app, running, handledBrowsers)) {
                    skipped.add(app);
                    log.add("skipping (already open or handled): " + app);
                    continue;
                }
                String appName = app.replace(".exe", "");

                if (appName.equalsIgnoreCase("claude")) {
                    if (folder == null) continue;     // a session is per folder; nowhere to open it
                    List<String> cmd = claudeCommand(folder, hasClaudeSession(folder), claudeInTerminal);
                    // open claude CLI in project folder
                    steps.add(new LaunchStep(app, String.join(" ", cmd.subList(2, cmd.size())), false, () -> {
                        ProcessBuilder pb = new ProcessBuilder(cmd);
                        pb.directory(new File(folder));
                        pb.start();
                    }));

                } else if (appName.equalsIgnoreCase("Code")) {
                    if (folder == null) continue;
                    // open VS Code in project folder
                    steps.add(new LaunchStep(app, "code " + folder, false, () ->
                            new ProcessBuilder("cmd.exe", "/c", "code", folder).start()));

                } else if (appName.equalsIgnoreCase("WindowsTerminal")) {
                    if (claudeInTerminal) {
                        log.add("skipping wt: claude launches inside it");
                        continue;
                    }
                    // WindowsTerminal.exe is the process name, but "wt" is the actual
                    // launch command - "start WindowsTerminal" can't resolve it
                    steps.add(new LaunchStep(app, "start wt", false, () ->
                            new ProcessBuilder("cmd.exe", "/c", "start", "wt").start()));

                } else {
                    // no folder needed: this used to sit under the folderPath guard too, so a
                    // project without a folder relaunched nothing at all
                    steps.add(new LaunchStep(app, "start " + appName, false, () ->
                            new ProcessBuilder("cmd.exe", "/c", "start", appName).start()));
                }
            }
        }
        return new ResumePlan(steps, skipped);
    }

    /** Native folder picker. Returns the absolute path, or the string "NULL" on cancel -
        callers compare against that sentinel. */
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
    // == Embedded server (the contract with ResumeOneExtension) ============
    //   WS  /ws?browser=X   extension keeps this open; app pushes "capture" to request tabs
    //   GET /project        active project name ("" if none) - the popup's "is the app up?"
    //   POST /tabs          {browser, urls[]} -> stored on the active project and saved
    //   Port 8765, CORS open, because the extension's fetch comes from a chrome-extension:// origin.

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
