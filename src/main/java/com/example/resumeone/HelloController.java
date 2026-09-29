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
import javafx.geometry.Side;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;      // explicit: java.awt.* also exports Menu
import javafx.scene.control.MenuItem;  // and MenuItem
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import java.util.prefs.Preferences;
import javafx.scene.effect.BlurType;
import javafx.scene.effect.DropShadow;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.css.PseudoClass;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;


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

    // one extension per browser, each with its own socket: a single field meant the second
    // browser to connect silently replaced the first, and only it ever got the capture push
    private final Set<WsContext> wsSessions = ConcurrentHashMap.newKeySet();

    @FXML
    public VBox projectList;

    // ── HUD v2 (hud-view.fxml). All null when the old hello-view.fxml is loaded,
    //    so every use is guarded by isHud().
    @FXML public VBox detailPane, emptyPane, categoryRows, tabsList;
    @FXML public VBox deletePane, newProjectPane, confirmPane;
    @FXML public Label confirmTitle, confirmSub, confirmBody;
    private final Region ckFlash = new Region();   // the block lighting up on save
    private LaunchStep lastLaunching;   // the row whose beat already played
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
    @FXML public TextField npName;
    @FXML public Label npFolder;
    @FXML public CheckBox npCapture;
    @FXML public Button btnNpBrowse, btnNpCancel, btnNpCreate;
    @FXML public ScrollPane settingsPane;
    @FXML public StackPane btnSettings, btnNewProjectRail;   // the rail's + ; hello-view keeps its own Button
    // the rail opens to 200px (.rail-wide): a PROJECTS header, named rows, a New project button
    @FXML public VBox rail;
    @FXML public Label railTitle, railCount;
    @FXML public Region railHeadGap;
    @FXML public StackPane btnRailToggle;
    @FXML public Button btnNewProjectWide;
    private Tooltip railToggleTip;
    /** Same node Motion keeps "motion" in; projects.json stays projects only. */
    private static final Preferences PREFS = Preferences.userNodeForPackage(HelloController.class);
    @FXML public VBox settingsRows, captureRows, resumeRows;
    @FXML public Label settingsFoot;
    @FXML public CheckBox motionToggle;
    @FXML public Label deleteBody, deletePath;
    @FXML public Button btnDeleteOk, btnDeleteCancel, btnSettingsBack;
    @FXML public HBox btnResume, btnCheckpoint, statusBox;
    @FXML public StackPane btnCheckpointMenu;
    @FXML public StackPane btnDelete;
    @FXML public SVGPath resumeGlyph, checkpointGlyph;   // the 1b artwork; Resume nudges on press, the bookmark seats on save
    @FXML public Region statusDot;
    @FXML public Label projectNameLabel, projectPathLabel, resumeSpec, checkpointSub,
            tabsCount, statusLabel;

    /** Hover-only: names the browsers on the socket, so the dot itself stays uncluttered. */
    // built in initialize(), not here: a Tooltip needs the JavaFX toolkit, which the unit tests
    // (new HelloController()) never start
    private Tooltip statusTip;

    /** Sidebar row per project name, so deleteProject() can be handed the row to remove. */
    private final Map<String, StackPane> rowsByProject = new LinkedHashMap<>();

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
        installCheckpointMenu();
        ckFlash.getStyleClass().add("ck-flash");
        ckFlash.setManaged(false);
        ckFlash.setMouseTransparent(true);
        ckFlash.setOpacity(0);
        btnCheckpoint.getChildren().add(0, ckFlash);   // first child: under the word and the glyph
        btnCheckpoint.layoutBoundsProperty().addListener((o, was, b) -> ckFlash.resize(b.getWidth(), b.getHeight()));
        clickable(btnDelete, this::showDeleteView);
        // the gear is a toggle: the lit gear is the natural "close" in a rail
        clickable(btnSettings, () -> { if (settingsPane.isVisible()) backToProjects(); else showSettings(); });
        Tooltip.install(btnSettingsBack, tip("Esc"));
        // what the app does, as plain check + label: these were disabled checkboxes, which read
        // as "locked" and rendered at 2:1. The copy states each behaviour as a fact.
        captureRows.getChildren().setAll(
                behaviourRow("Browser tabs, per browser, via the extension"),
                behaviourRow("Running apps, grouped by kind"));
        resumeRows.getChildren().setAll(
                behaviourRow("Browser tabs reopen in one window per browser"),
                behaviourRow("Claude continues its last session (--continue)"),
                behaviourRow("Apps already running are left alone"),
                behaviourRow("coldstart stays on top while resuming"));
        clickable(btnNewProjectRail, this::onNewProjectClick);
        Tooltip.install(btnNewProjectRail, tip("New project"));
        btnNewProjectWide.setOnAction(e -> onNewProjectClick());
        railToggleTip = tip("");
        Tooltip.install(btnRailToggle, railToggleTip);
        clickable(btnRailToggle, () -> setRailWide(!rail.getStyleClass().contains("rail-wide")));
        setRailWide(PREFS.getBoolean("railWide", true));
        motionToggle.setSelected(!Motion.REDUCED);
        motionToggle.selectedProperty().addListener((o, was, on) -> {
            Motion.setEnabled(on);
            // the one loop answers at once; everything else simply plays, or not, next time
            Motion.pulse(statusDot, statusDot.getStyleClass().contains("dot-live"));
        });
        installGlyphs();
        // the scene arrives after initialize(); accelerators and Escape hang off it once
        detailPane.sceneProperty().addListener((o, was, scene) -> {
            if (scene != null && was == null) installKeys(scene);
            // the live pulse is the one loop; it stops where nobody can see it
            if (scene != null) scene.windowProperty().addListener((o2, w0, w) -> {
                if (w instanceof Stage st) st.iconifiedProperty().addListener((o3, a, hidden) -> Motion.pause(statusDot, hidden));
            });
        });
        btnDeleteCancel.setOnAction(e -> backToProjects());
        btnDeleteOk.setOnAction(e -> deleteActive());
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
        btnResumeStop.setGraphic(Icons.node(Icons.CLOSE, 16, TX2));
        Icons.animate(btnResumeStop, btnResumeStop.getGraphic());
        btnResumeStop.setOnAction(e -> stopRequested = true);
        btnStayHere.setOnAction(e -> stopCountdown());
        btnOpenProject.setGraphic(Icons.node(Icons.CHEVRON, 16, TX));
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
        if (!npCapture.isSelected()) projectObj.lastCheckpoint = null;   // "no checkpoint yet" is true
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

        // a numbered 32x32 rail button; the name and age ride on its tooltip. The wide rail
        // shows the second face instead - hud.css picks one, both sit in the same StackPane.
        StackPane row = new StackPane(new Label("00"));
        if (isHud()) row.getChildren().add(sideFace(projectObj.projectName));
        row.getStyleClass().addAll("rail-btn", "side-row");
        row.setPickOnBounds(true);
        Tooltip t = tip("");
        Tooltip.install(row, t);
        row.getProperties().put("tip", t);
        clickable(row, () -> selectProject(projectObj));

        rowsByProject.put(projectObj.projectName, row);
        projectList.getChildren().add(row);
        railTip(row, projectObj);
        // motion-transitions, row insert - new projects only. A fade and 4px, not a height
        // tween: animating height would push the rail's + down frame by frame.
        if (isNew && Motion.showing(projectList)) Motion.enter(row, 0, -4, 1, Motion.BASE, Motion.STANDARD);

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
    //     overlays    (confirmPane | deletePane | newProjectPane) - at most one, drawn on top
    //   Nothing is created or destroyed; panes are toggled with visible+managed.

    // == keyboard ============================================================
    //   Every HBox/StackPane that acts as a button goes through clickable(): mouse, Enter and
    //   Space all land on the same action, and the node is in the Tab order. Focus is shown
    //   only when it came from the keyboard (:focus-visible), the way the web does it.

    /** The slide's action glyphs on the chrome that FXML only shapes; hover/press plays each one. */
    private void installGlyphs() {
        for (var e : Map.of(btnNewProjectRail, Icons.NEW, btnSettings, Icons.SETTINGS, btnDelete, Icons.DELETE).entrySet()) {
            Pane g = Icons.node(e.getValue(), 16, TX3);
            e.getKey().getChildren().add(g);
            Icons.animate(e.getKey(), g);
        }
        btnDelete.getStyleClass().add("danger");
        btnRailToggle.getChildren().add(Icons.node(Icons.CHEVRON, 16, TX3));
        btnNewProjectWide.setGraphic(Icons.node(Icons.NEW, 16, TX));
        btnNewProjectWide.setGraphicTextGap(8);
        Icons.animate(btnNewProjectWide, btnNewProjectWide.getGraphic());
        btnNpBrowse.setGraphic(Icons.node(Icons.BROWSE, 16, TX));
        btnNpBrowse.setGraphicTextGap(8);
        Icons.animate(btnNpBrowse, btnNpBrowse.getGraphic());
        Icons.tag(resumeGlyph, "nudge");
        Icons.animate(btnResume, resumeGlyph);
    }

    private void clickable(Node node, Runnable action) {
        node.setFocusTraversable(true);
        node.setOnMouseClicked(e -> {
            // primary only: a right click on Checkpoint opens its menu, it must not also save
            if (e.getButton() != MouseButton.PRIMARY) return;
            node.requestFocus();
            action.run();
        });
        node.setOnKeyPressed(e -> {
            if (overlayOpen()) return;
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
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.B, KeyCombination.SHORTCUT_DOWN),
                () -> setRailWide(!rail.getStyleClass().contains("rail-wide")));
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
        return confirmPane.isVisible() || newProjectPane.isVisible() || deletePane.isVisible();
    }

    /** 1h: behaviour rows are fixed copy; the STATUS column is live and re-renders on socket events. */
    private void showSettings() {
        renderSettingsRows();
        showBase(settingsPane);
        // one ring at a time: orange means "where you are", and right now that is the gear
        rowsByProject.values().forEach(r -> r.getStyleClass().remove("rail-active"));
        btnSettings.getStyleClass().add("rail-active");
    }

    private void renderSettingsRows() {
        List<String> browsers = wsSessions.stream().map(HelloController::wsBrowser).distinct().sorted().toList();
        long kb = Math.max(1, projectsFile().length() / 1024);
        // the resolved path is ~70 mono chars and clips even in the wide column; %APPDATA%
        // is where it actually lives and what a windows user would type. hover shows it resolved.
        String path = projectsFile().getPath();
        String appData = System.getenv("APPDATA");
        String shown = appData != null && path.startsWith(appData) ? "%APPDATA%" + path.substring(appData.length()) : path;
        String size = projectsFile().length() < 1024 ? "<1 KB" : kb + " KB";
        String storage = projects.size() + (projects.size() == 1 ? " project" : " projects") + " \u00b7 " + size + " \u00b7 ";
        List<String> ignored = IGNORED_WINDOWS.stream().sorted().toList();
        HBox storageRow = statRow(Icons.TERMINAL, "Storage", storage + shown, storage + path);
        // the one row that acts: open the data folder. Chevron + hand say so; the other two stay flat.
        storageRow.getChildren().add(Icons.node(Icons.CHEVRON, 14, TX3));
        storageRow.getStyleClass().add("stat-link");
        clickable(storageRow, () -> {
            try {
                Desktop.getDesktop().open(projectsFile().getParentFile());
            } catch (IOException | IllegalArgumentException ex) {
                System.err.println("open data folder: " + ex.getMessage());
            }
        });
        settingsRows.getChildren().setAll(
                statRow(Icons.BROWSER, "Browser extensions",
                        browsers.isEmpty() ? "none connected \u00b7 install it, it connects on :8765"
                                           : String.join(" \u00b7 ", browsers) + " connected", null),
                statRow(Icons.APP_WINDOW, "Ignored processes", names(ignored), String.join("\n", ignored)),
                storageRow);
    }

    /** detailPane / emptyPane / settingsPane are the base views: exactly one shows. */
    private void showBase(Node target) {
        boolean arriving = !target.isVisible();
        for (Node pane : List.of(detailPane, emptyPane, settingsPane, resumingPane, restoredPane)) {
            pane.setVisible(pane == target);
            pane.setManaged(pane == target);
        }
        if (arriving && Motion.showing(target)) panelIn(target);
        // Pinned on top only while Resuming shows, so launched apps cannot bury progress and
        // Stop. Derived here, from the view, so no path out of a resume can leave it pinned.
        if (target.getScene() != null && target.getScene().getWindow() instanceof Stage st) {
            st.setAlwaysOnTop(target == resumingPane);
        }
        showOverlay(null);
        if (target != resumingPane && target != restoredPane) titleState(null, null, false);
        if (target != settingsPane) btnSettings.getStyleClass().remove("rail-active");
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

    /** confirmPane / deletePane / newProjectPane are scrims stacked over whichever base is showing.
        Focus follows the scrim onto its safe button, so Enter cannot re-fire whatever opened it. */
    private void showOverlay(Node target) {
        for (Node pane : List.of(deletePane, newProjectPane, confirmPane)) {
            boolean opening = pane == target && !pane.isVisible();
            pane.setVisible(pane == target);
            pane.setManaged(pane == target);
            // motion-transitions: the scrim fades up, the dialog settles from .97; the exit is instant
            if (opening && Motion.showing(pane)) {
                Motion.enter(pane, 0, 0, 1, Motion.BASE, Motion.OUT);
                Motion.enter(((Pane) pane).getChildren().get(0), 0, 0, .97, Motion.BASE, Motion.OUT);
            }
        }
        if (target == deletePane) btnDeleteCancel.requestFocus();
        else if (target == confirmPane) btnConfirmCancel.requestFocus();
    }

    /** motion-transitions, detail panel: 10px in from the right with a fade; width never animates. */
    private static void panelIn(Node pane) {
        Motion.enter(pane, 10, 0, 1, Motion.SETTLE, Motion.GLIDE);
    }

    private void backToProjects() {
        showEmpty(projects.isEmpty());
        // settings took the ring off the active project; hand it back
        StackPane row = activeProject == null ? null : rowsByProject.get(activeProject.projectName);
        if (row != null && !row.getStyleClass().contains("rail-active")) row.getStyleClass().add("rail-active");
    }

    private void showEmpty(boolean empty) {
        showBase(empty ? emptyPane : detailPane);
    }

    private void showDeleteView() {
        if (activeProject == null) return;
        deleteBody.setText(activeProject.projectName + " and its saved environment are dropped "
                + "from projects.json. Your files stay exactly where they are.");
        deletePath.setText(activeProject.folderPath == null ? "no folder" : activeProject.folderPath);
        showOverlay(deletePane);
    }

    /** Numbers the rail 01, 02, 03 ... in list order; deletion shifts the numbers down. */
    private void refreshCount() {
        int n = 0;
        for (Node b : projectList.getChildren()) {
            ((Label) ((StackPane) b).getChildren().get(0)).setText(String.format("%02d", ++n));
        }
        if (railCount != null) railCount.setText(String.format("%02d", n));
    }

    /** 200px with names, or the 48px numbered rail. Instant: width never animates (it would
        reflow the card every frame). Remembered across launches. */
    private void setRailWide(boolean wide) {
        rail.getStyleClass().remove("rail-wide");
        if (wide) rail.getStyleClass().add("rail-wide");
        for (Node n : List.of(railTitle, railHeadGap, railCount, btnNewProjectWide)) {
            n.setVisible(wide);
            n.setManaged(wide);
        }
        btnNewProjectRail.setVisible(!wide);
        btnNewProjectRail.setManaged(!wide);
        btnRailToggle.setRotate(wide ? 180 : 0);   // the chevron points where the rail will go
        railToggleTip.setText((wide ? "Collapse" : "Expand") + " sidebar · Ctrl+B");
        PREFS.putBoolean("railWide", wide);
    }

    /** The wide rail's face of a row: a folder (open on the active row, via hud.css) and the name. */
    private static HBox sideFace(String name) {
        Pane shut = Icons.node(Icons.OTHER, 16, TX3), open = Icons.node(Icons.BROWSE, 16, TX);
        shut.getStyleClass().add("folder-shut");
        open.getStyleClass().add("folder-open");
        Label label = new Label(name);
        label.getStyleClass().add("side-name");
        HBox face = new HBox(12, new StackPane(shut, open), label);
        face.getStyleClass().add("side-face");
        face.setAlignment(Pos.CENTER_LEFT);
        return face;
    }

    private void railTip(StackPane row, Project projectObj) {
        String age = ago(projectObj.lastCheckpoint);
        Tooltip t = (Tooltip) row.getProperties().get("tip");
        t.setText(projectObj.projectName + " \u00b7 " + ("never".equals(age) ? "no checkpoint yet" : "checkpoint " + when(age)));
    }

    /** Highlights the sidebar row, makes it the active project and draws its card. */
    private void selectProject(Project projectObj) {
        // another project under an already-open card is a change of subject, so it arrives too
        boolean switching = activeProject != projectObj && detailPane.isVisible();
        activeProject = projectObj;
        rowsByProject.forEach((n, r) -> {
            r.getStyleClass().remove("rail-active");
            if (n.equals(projectObj.projectName)) {
                r.getStyleClass().add("rail-active");
            }
        });
        btnSettings.getStyleClass().remove("rail-active");
        showEmpty(false);
        renderDetail(projectObj);
        if (switching && Motion.showing(detailPane)) panelIn(detailPane);
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
                + (projectObj.folderPath == null ? "NO FOLDER" : "1 FOLDER"));
        btnResume.setDisable(apps == 0 && tabs == 0);
        String age = ago(projectObj.lastCheckpoint);
        // "now", not "just now": the block's text column holds ~18 mono characters and
        // "overwrite · just now" is 20 - it truncated to "just..." right after every save
        checkpointSub.setText("never".equals(age) ? "no checkpoint yet"
                : "overwrite \u00b7 " + ("just now".equals(age) ? "now" : when(age)));
        tabsCount.setText(String.valueOf(tabs));

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

            HBox labelBox = new HBox(8);
            labelBox.setAlignment(Pos.CENTER_LEFT);
            labelBox.setMinWidth(96);
            labelBox.setPrefWidth(96);
            labelBox.setMaxWidth(96);
            labelBox.getChildren().addAll(categoryIcon(category),
                    styled(new Label(categoryLabel(category)), "cat-label"));

            HBox chips = new HBox(8);
            chips.setAlignment(Pos.CENTER_LEFT);
            for (String app : new TreeSet<>(bucket)) {
                chips.getChildren().add(chip(projectObj, category, app));
            }

            HBox row = new HBox(8);
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
        if ("AI".equals(category) && hasClaudeSession(projectObj.folderPath)) {
            return "--continue";
        }
        return null;
    }

    /** The column scrolls; every header and tab row is always in it. */
    private void renderTabs(Project projectObj) {
        List<Node> tabRows = new ArrayList<>();
        projectObj.urlsByBrowser.forEach((browser, urls) -> {
            HBox head = new HBox(8);
            head.setAlignment(Pos.CENTER_LEFT);
            Region dot = new Region();
            dot.getStyleClass().add("dot-live");
            dot.setMinSize(5, 5);
            dot.setPrefSize(5, 5);
            dot.setMaxSize(5, 5);
            Label name = styled(new Label(browserLabel(browser)), "browser-name");
            Tooltip.install(name, tip("one window per browser on resume"));
            head.getChildren().addAll(dot, name, styled(new Label(String.valueOf(urls.size())), "side-count"));
            tabRows.add(head);

            for (String url : urls) {
                // the site is what you scan by; the whole url is one hover away
                Label item = styled(new Label(site(url)), "tab-title");
                item.setMaxWidth(Double.MAX_VALUE);
                item.setTextOverrun(OverrunStyle.ELLIPSIS);
                HBox.setHgrow(item, Priority.ALWAYS);
                Tooltip.install(item, tip(url));
                HBox row = new HBox(2, item, remover(() -> removeUrl(projectObj, browser, url), projectObj));
                row.getStyleClass().add("tab-row");
                row.setAlignment(Pos.CENTER_LEFT);
                row.setMaxWidth(256);
                tabRows.add(row);
            }
        });
        tabsList.getChildren().setAll(tabRows);
    }

    /** "brave" -> "Brave": extension brands arrive in whatever case the browser reports. */
    static String browserLabel(String browser) {
        return browser.isEmpty() ? browser : Character.toUpperCase(browser.charAt(0)) + browser.substring(1);
    }

    /** A page someone might want back. chrome://newtab and friends are the browser's own. */
    static boolean isPageUrl(String url) {
        return url != null && !url.matches("(?i)^(chrome|edge|brave|opera|about|chrome-extension|devtools|view-source):.*");
    }

    /** "github.com" from "https://www.github.com/atlas-api/pull/42". */
    static String site(String url) {
        String s = url.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "").replaceFirst("^www\\.", "");
        int cut = s.indexOf('/');
        return cut < 0 ? s : s.substring(0, cut);
    }

    private HBox chip(Project projectObj, String category, String app) {
        HBox chip = new HBox(2);
        chip.getStyleClass().add("chip");
        chip.setAlignment(Pos.CENTER_LEFT);
        chip.setMinHeight(24);
        chip.setPrefHeight(24);
        chip.setPadding(new Insets(0, 3, 0, 8));   // the x's own box is the right edge
        chip.getChildren().addAll(new Label(app), remover(() -> removeApp(projectObj, category, app), projectObj));
        return chip;
    }

    /** The hover-only x on chips and tab rows. Opacity, not managed, so nothing shifts on hover.
        No undo: the next checkpoint brings anything back. */
    private StackPane remover(Runnable edit, Project projectObj) {
        StackPane x = new StackPane();
        x.getStyleClass().add("rm");
        Pane glyph = Icons.node(Icons.CLOSE, 12, TX3);
        glyph.getStyleClass().add("rm-glyph");
        x.getChildren().add(glyph);
        Icons.animate(x, glyph);
        x.setOnMouseClicked(e -> {
            e.consume();
            if (e.getButton() != MouseButton.PRIMARY) return;
            edit.run();
            renderDetail(projectObj);
            try {
                saveProject(projectObj);
            } catch (IOException ex) {
                // the card already shows the removal; say the disk does not
                confirm("Change not written", projectsFile().getPath(),
                        "Nothing changed on disk. " + ex.getMessage(),
                        "Retry", () -> { try { saveProject(projectObj); } catch (IOException ignored) { } });
            }
        });
        return x;
    }

    static void removeApp(Project projectObj, String category, String app) {
        HashSet<String> bucket = projectObj.apps.get(category);
        if (bucket == null) return;
        bucket.remove(app);
        if (bucket.isEmpty()) projectObj.apps.remove(category);   // no dead keys in the json
    }

    static void removeUrl(Project projectObj, String browser, String url) {
        List<String> urls = projectObj.urlsByBrowser.get(browser);
        if (urls == null) return;
        urls.remove(url);
        if (urls.isEmpty()) projectObj.urlsByBrowser.remove(browser);   // else a "Brave 0" header
    }

    private static Pane categoryIcon(String category) {
        return Icons.node(Icons.CATEGORY.getOrDefault(category, Icons.OTHER), 14, HUD);
    }

    private static Tooltip tip(String text) {
        Tooltip t = new Tooltip(text);
        t.setShowDelay(Duration.millis(150));
        return t;
    }

    /** "just now" or "3d ago": ago() as a phrase, for the copy that follows a verb. */
    static String when(String age) {
        return "just now".equals(age) || "never".equals(age) ? age : age + " ago";
    }

    /** How long since an ISO timestamp; older files hold a bare date, read as that day's start. */
    static java.time.Duration since(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return java.time.Duration.between(Instant.parse(iso), Instant.now());
        } catch (RuntimeException notAnInstant) {
            try {
                Instant day = LocalDate.parse(iso).atStartOfDay(ZoneId.systemDefault()).toInstant();
                return java.time.Duration.between(day, Instant.now());
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /** "just now", "4m", "2h", "3d", "2w", "5mo" - the sidebar and the checkpoint block both read this. */
    static String ago(String iso) {
        java.time.Duration d = since(iso);
        if (d == null) return iso == null || iso.isBlank() ? "never" : iso;
        long m = Math.max(0, d.toMinutes());
        if (m < 1) return "just now";
        if (m < 60) return m + "m";
        long h = m / 60;
        if (h < 24) return h + "h";
        long days = h / 24;
        if (days < 7) return days + "d";
        if (days < 30) return (days / 7) + "w";
        return (days / 30) + "mo";
    }

    // == HUD actions =======================================================
    //   Resume     -> onResume     -> resumeProject
    //   Checkpoint -> onCheckpoint -> re-capture apps, ask extensions for tabs, save
    //   Trash      -> showDeleteView (confirm scrim) -> deleteActive

    // == resume: 1e in progress -> 1l done ==================================
    //   onResume runs planResume() on a worker thread, one LaunchStep per LAUNCH_GAP_MS. Every
    //   state change re-renders resumingPane (1e). The run finishes into restoredPane (1l),
    //   which keeps failures visible with a Retry and auto-returns to the project in 3s.

    // ponytail: 750ms between spawns is a first guess at what stops ten processes landing
    // on the machine at once; the design paces its prototype at 220ms for looks. Tune by feel.
    static final long LAUNCH_GAP_MS = 750;

    private void onResume() {
        if (activeProject == null || resuming || btnResume.isDisabled()) return;
        resuming = true;
        stopRequested = false;
        ResumeRun r = new ResumeRun(activeProject);
        lastLaunching = null;
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
        new Timeline(new KeyFrame(Duration.millis(220), new KeyValue(resumingFrac, frac, Motion.OUT))).play();

        resumingRows.getChildren().clear();
        // done steps fold into one line so the list is bounded: launched, the one launching,
        // any failures, queued, log - and Stop stays on screen however many apps there are
        List<String> done = new ArrayList<>();
        List<String> queued = new ArrayList<>();
        for (LaunchStep step : r.plan.steps()) {
            if (step.state == LaunchStep.State.DONE) done.add(step.label);
        }
        if (!done.isEmpty()) {
            resumingRows.getChildren().add(foldRow(Icons.CHECK, done.size() + " launched", names(done)));
        }
        for (LaunchStep step : r.plan.steps()) {
            switch (step.state) {
                case QUEUED -> queued.add(step.label);
                case DONE -> { }
                default -> {
                    HBox row = stepRow(step);
                    resumingRows.getChildren().add(row);
                    // rows are rebuilt on every change, so the beat plays only for a step new to launching
                    if (step.state == LaunchStep.State.LAUNCHING && step != lastLaunching) {
                        lastLaunching = step;
                        launchBeat(row);
                    }
                }
            }
        }
        if (!queued.isEmpty()) {
            resumingRows.getChildren().add(foldRow(Icons.TIMESTAMP, queued.size() + " queued", names(queued)));
        }
        resumingRows.getChildren().add(foldRow(Icons.TERMINAL, "Resume log", r.log.size() + " lines"));

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
        restoredRows.getChildren().add(foldRow(Icons.APP_WINDOW, r.appsDone() + " apps launched", "on the project card"));
        restoredRows.getChildren().add(foldRow(Icons.BROWSER, countTabs(r.project) + " tabs handed back",
                browsers.isEmpty() ? "no browser connected" : String.join(" · ", browsers)));
        long failed = r.failed();
        restoredRows.getChildren().add(foldRow(Icons.TERMINAL, "Resume log", r.log.size() + " lines"
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
        Pane mark = switch (step.state) {
            case LAUNCHING -> Icons.node(Icons.RESUME, 14, GO);
            case FAILED -> Icons.node(Icons.FAILED, 14, BAD);
            default -> Icons.node(Icons.CHECK, 14, TX3);
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

    /** motion-resume: the row that starts launching arrives on --ease-out and its orange bloom
        flashes for one beat, then settles to the ring alone - DESIGN.md's restraint for the bloom. */
    private static void launchBeat(HBox row) {
        Motion.enter(row, 0, 6, 1, Motion.BASE, Motion.OUT);
        if (Motion.REDUCED) return;
        DropShadow bloom = new DropShadow(BlurType.GAUSSIAN, Color.web(GO), 0, 0, 0, 0);
        row.setEffect(bloom);
        new Timeline(
                new KeyFrame(Duration.millis(120), new KeyValue(bloom.radiusProperty(), 18, Motion.OUT)),
                new KeyFrame(Motion.CAPTURE, new KeyValue(bloom.radiusProperty(), 0, Motion.STANDARD))).play();
    }

    private HBox foldRow(Icons.Glyph iconPath, String title, String meta) {
        HBox row = rowShell("fold-row");
        Label t = styled(new Label(title), "fold-title");
        t.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(t, Priority.ALWAYS);
        row.getChildren().addAll(Icons.node(iconPath, 14, TX3), t, styled(new Label(meta), "fold-meta"));
        return row;
    }

    /** A fold row for the 288px settings column: meta under the title instead of beside it,
        so a storage path ellipsises rather than pushing the title off the row. */
    /** tipText null = no tooltip: one that repeats the label trains people to stop hovering. */
    private HBox statRow(Icons.Glyph iconPath, String title, String meta, String tipText) {
        HBox row = rowShell("fold-row");
        row.setMinHeight(56);
        row.setPrefHeight(56);
        Label m = styled(new Label(meta), "fold-meta");
        m.setMaxWidth(Double.MAX_VALUE);
        if (tipText != null) Tooltip.install(m, tip(tipText));
        VBox text = new VBox(2, styled(new Label(title), "fold-title"), m);
        text.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(text, Priority.ALWAYS);
        row.getChildren().addAll(Icons.node(iconPath, 14, TX3), text);
        return row;
    }

    /** Settings: one behaviour the app has, stated as a fact. Check glyph + label, no control. */
    private HBox behaviourRow(String text) {
        HBox row = new HBox(12, Icons.node(Icons.CHECK, 14, TX3), styled(new Label(text), "behaviour"));
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private HBox failRow(ResumeRun r, LaunchStep step) {
        HBox row = rowShell("fail-row");
        row.setMinHeight(56);
        row.setPrefHeight(56);
        Label why = styled(new Label("failed to launch"), "fail-why");
        why.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(why, Priority.ALWAYS);
        Button retry = styled(new Button("Retry"), "btn-ghost");
        retry.setGraphic(Icons.node(Icons.RECAPTURE, 16, TX));
        Icons.animate(retry, retry.getGraphic());
        retry.setGraphicTextGap(8);
        retry.setOnAction(e -> retry(r, step));
        row.getChildren().addAll(Icons.node(Icons.FAILED, 14, BAD), styled(new Label(step.label), "step-label"), why, retry);
        return row;
    }

    private static HBox rowShell(String styleClass) {
        HBox row = new HBox(8);
        row.getStyleClass().add(styleClass);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMinHeight(48);
        row.setPrefHeight(48);
        row.setPadding(new Insets(0, 16, 0, 16));
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

    private static final String TX = "#E8D8C9", TX2 = "#A99B8D", TX3 = "#6F655C",
            GO = "#F3701E", BAD = "#D96C5E", HUD = "#7A94BC";

    static int countApps(Project projectObj) {
        if (projectObj == null || projectObj.apps == null) return 0;
        return projectObj.apps.values().stream().mapToInt(Set::size).sum();
    }

    static int countTabs(Project projectObj) {
        if (projectObj == null) return 0;
        return projectObj.urlsByBrowser.values().stream().mapToInt(List::size).sum();
    }

    // == checkpoint modes ====================================================
    //   a plain press overwrites, as it always has. The chevron on the button (or a right
    //   click anywhere on it) offers the non-destructive kind: keep what is saved and add only
    //   what is open now and missing. Per press, never a sticky setting.
    enum Checkpoint {
        OVERWRITE, ADD_TABS, ADD_APPS, ADD_BOTH;
        boolean tabs() { return this == OVERWRITE || this == ADD_TABS || this == ADD_BOTH; }
        boolean apps() { return this == OVERWRITE || this == ADD_APPS || this == ADD_BOTH; }
        boolean merge() { return this != OVERWRITE; }
    }

    // tabs arrive on the javalin thread after the button has returned, so POST /tabs reads the
    // mode from here. A manual send from the extension popup reuses the last mode too.
    private volatile Checkpoint tabMode = Checkpoint.OVERWRITE;

    private void installCheckpointMenu() {
        ContextMenu menu = new ContextMenu();
        MenuItem overwrite = new MenuItem("Overwrite");
        overwrite.setOnAction(e -> onCheckpoint());
        Menu add = new Menu("Add what\u2019s missing");
        for (Checkpoint mode : List.of(Checkpoint.ADD_TABS, Checkpoint.ADD_APPS, Checkpoint.ADD_BOTH)) {
            MenuItem item = new MenuItem(switch (mode) {
                case ADD_TABS -> "Tabs";
                case ADD_APPS -> "Apps";
                default -> "Tabs and apps";
            });
            item.setOnAction(e -> { if (activeProject != null) writeCheckpoint(activeProject, mode); });
            add.getItems().add(item);
        }
        menu.getItems().addAll(overwrite, add);
        for (MenuItem item : menu.getItems()) item.disableProperty().bind(btnCheckpoint.disabledProperty());
        // consume, or the whole-button click handler fires an overwrite underneath the menu
        btnCheckpointMenu.setOnMouseClicked(e -> { e.consume(); menu.show(btnCheckpointMenu, Side.BOTTOM, 0, 4); });
        btnCheckpoint.setOnContextMenuRequested(e -> { e.consume(); menu.show(btnCheckpoint, e.getScreenX(), e.getScreenY()); });
    }

    private void onCheckpoint() {
        if (activeProject == null) return;
        Project projectObj = activeProject;
        // A checkpoint from another day with something in it gets one beat before it is
        // replaced; today's, or an empty one, just overwrites.
        String age = ago(projectObj.lastCheckpoint);
        int apps = countApps(projectObj), tabs = countTabs(projectObj);
        java.time.Duration d = since(projectObj.lastCheckpoint);
        boolean worthAsking = d != null && d.toHours() >= 24 && (apps > 0 || tabs > 0);
        if (!worthAsking) {
            writeCheckpoint(projectObj, Checkpoint.OVERWRITE);
            return;
        }
        confirm("Checkpoint again?",
                "checkpoint " + when(age) + " \u00b7 " + apps + " apps \u00b7 " + tabs + " tabs",
                "What is open right now replaces the checkpoint from " + when(age)
                        + ". Tabs from browsers that are not connected are kept.",
                "Overwrite", () -> writeCheckpoint(projectObj, Checkpoint.OVERWRITE));
    }

    private void writeCheckpoint(Project projectObj, Checkpoint mode) {
        // No bucket is ever dropped here: a browser that answers the capture push overwrites its
        // own bucket in POST /tabs, and one that does not answer (asleep worker, dead socket,
        // closed) keeps what it had. Stale beats gone.
        tabMode = mode;
        if (mode.tabs()) wsSessions.forEach(session -> session.send("capture"));
        try {
            if (mode.apps()) {
                HashMap<String, HashSet<String>> now = segregateApps(captureRunningApps());
                if (mode.merge()) mergeApps(projectObj.apps, now);
                else projectObj.apps = now;
            }
            projectObj.lastCheckpoint = Instant.now().toString();
            saveProject(projectObj);
        } catch (IOException ex) {
            // the card must not claim "saved" over a write that did not happen
            confirm("Checkpoint not written", projectsFile().getPath(),
                    "Nothing changed on disk. " + ex.getMessage(),
                    "Retry", () -> writeCheckpoint(projectObj, mode));
            return;
        }
        StackPane row = rowsByProject.get(projectObj.projectName);
        if (row != null) railTip(row, projectObj);

        renderDetail(projectObj);
        checkpointSaved();
    }

    /** Add mode: every exe open now joins its category; nothing saved is dropped. */
    static void mergeApps(Map<String, HashSet<String>> into, Map<String, HashSet<String>> from) {
        from.forEach((category, exes) -> into.computeIfAbsent(category, k -> new HashSet<>()).addAll(exes));
    }

    /** Add mode: saved order first, then the incoming urls that were not already there. */
    static ArrayList<String> mergeUrls(List<String> saved, List<String> incoming) {
        LinkedHashSet<String> out = new LinkedHashSet<>(saved);
        out.addAll(incoming);
        return new ArrayList<>(out);
    }

    /** The save, on the Checkpoint block itself - no stamp, the block already says "now".
        One slate breath: the block lights and slowly exhales, the bookmark settles in with a
        soft overshoot, the sub-line's new age fades in. Fires once per written save; never on
        the press, never on the menu. Motion off: light and fade only.
        Slower than --dur-capture and on a soft-start curve on purpose: the user found the
        --ease-out start a snap. No plateau anywhere; every segment eases into the next. */
    private void checkpointSaved() {
        if (!Motion.showing(btnCheckpoint)) return;
        Motion.stop(ckFlash);
        Motion.run(ckFlash, new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(ckFlash.opacityProperty(), 0)),
                new KeyFrame(Duration.millis(800), new KeyValue(ckFlash.opacityProperty(), 1, SOFT)),
                new KeyFrame(Duration.millis(2500), new KeyValue(ckFlash.opacityProperty(), 0, Interpolator.EASE_BOTH))));
        Motion.enter(checkpointSub, 0, 3, 1, Duration.millis(650), SOFT);
        if (Motion.REDUCED) return;
        // --ease-snap overshoots, which Interpolator.SPLINE cannot: the overshoot is its own key
        Motion.run(checkpointGlyph, new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(checkpointGlyph.translateYProperty(), -6)),
                new KeyFrame(Duration.millis(800), new KeyValue(checkpointGlyph.translateYProperty(), .8, SOFT)),
                new KeyFrame(Duration.millis(1200), new KeyValue(checkpointGlyph.translateYProperty(), 0, Interpolator.EASE_BOTH))));
    }

    /** Starts gently and lands gently - the checkpoint's own curve, where --ease-out snapped. */
    private static final Interpolator SOFT = Interpolator.SPLINE(.4, 0, .2, 1);

    /** The browsers whose saved tabs will survive this checkpoint untouched, because nobody on
        the socket is about to re-post them. Read-only: nothing is dropped. Only
        CheckpointKeepsTabsTest calls it since the saved stamp and its "tabs kept" note went. */
    static List<String> unconnectedWithTabs(Map<String, ArrayList<String>> urlsByBrowser, Set<String> connected) {
        List<String> kept = new ArrayList<>();
        urlsByBrowser.forEach((browser, urls) -> {
            if (!connected.contains(browser) && !urls.isEmpty()) kept.add(browser);
        });
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

    private void deleteActive() {
        if (activeProject == null) return;
        Project projectObj = activeProject;
        try {
            deleteProject(projectObj, rowsByProject.remove(projectObj.projectName));
        } catch (IOException ex) {
            confirm("Project not deleted", projectsFile().getPath(),
                    "Nothing changed on disk. " + ex.getMessage(), "Retry", this::deleteActive);
            return;
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
            boolean wasLive = statusDot.getStyleClass().contains("dot-live");
            statusDot.getStyleClass().setAll(live ? "dot-live" : "dot-idle");
            if (live != wasLive) Motion.pulse(statusDot, live);
            statusLabel.setText(!live ? ":8765 \u00b7 no extension"
                    : browsers.size() == 1 ? "connected \u00b7 " + browsers.get(0)
                    : "connected \u00b7 " + browsers.size() + " browsers");
            statusLabel.getStyleClass().setAll(live ? "port-live" : "port");
            statusTip.setText(live ? String.join(", ", browsers) : "no extension connected");
            // someone watching Settings to see the extension land must see it land
            if (settingsPane.isVisible()) renderSettingsRows();
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
                    "Typora.exe", "Joplin.exe", "anytype.exe", "notepad.exe")),
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

    public void deleteProject(Project projectObj, Node projectRow) throws IOException {
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
    //                    2. open each browser with its saved urls as arguments - unless its
    //                       extension is connected, then push {"open":[urls]} over the socket
    //                       and let it open only the tabs that are not already there
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

    // every running claude writes ~/.claude/sessions/<pid>.json with its cwd. a second
    // `claude --continue` in the same folder just prints "already running in the background"
    // and exits 1, so a live one for this folder means: leave it alone.
    boolean claudeRunningIn(String folderPath) {
        return claudeRunningIn(folderPath, new File(System.getProperty("user.home"), ".claude/sessions"));
    }

    boolean claudeRunningIn(String folderPath, File sessionsDir) {
        File[] files = sessionsDir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) return false;
        for (File f : files) {
            try (Reader r = new FileReader(f)) {
                Map<String, Object> session = gson.fromJson(r, Map.class);
                Object cwd = session.get("cwd");
                Object pid = session.get("pid");
                // the file outlives a crash, so the pid has to be checked too
                if (cwd != null && pid != null && folderPath.equalsIgnoreCase(cwd.toString())
                        && ProcessHandle.of(((Number) pid).longValue()).map(ProcessHandle::isAlive).orElse(false)) {
                    return true;
                }
            } catch (IOException | RuntimeException ex) {
                // a half-written or odd file is not a reason to skip the launch
            }
        }
        return false;
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
        // files saved before the filter existed still hold chrome://newtab and the like
        projectObj.urlsByBrowser.values().forEach(urls -> urls.removeIf(u -> !isPageUrl(u)));
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
            // check for open urls and skip them
            WsContext live = wsSessions.stream()
                    .filter(s -> wsBrowser(s).equalsIgnoreCase(browser)).findFirst().orElse(null);
            if (live != null) {
                steps.add(new LaunchStep(browser, urls.size() + " urls · via extension, open tabs skipped",
                        true, () -> live.send(gson.toJson(Map.of("open", urls)))));
                continue;
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
                    if (claudeRunningIn(folder)) {
                        skipped.add(app);
                        log.add("skipping claude: already running in " + folder);
                        continue;
                    }
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
                String key = browser != null ? browser.toString() : DEFAULT_BROWSER;
                List<String> pages = incoming.stream().filter(HelloController::isPageUrl).toList();
                activeProject.urlsByBrowser.put(key, tabMode.merge()
                        ? mergeUrls(activeProject.urlsByBrowser.getOrDefault(key, new ArrayList<>()), pages)
                        : new ArrayList<>(pages));
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
