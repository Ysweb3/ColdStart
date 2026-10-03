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
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.event.ActionEvent;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;  // explicit: java.awt.* also exports MenuItem
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
import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.PauseTransition;
import javafx.animation.RotateTransition;
import javafx.scene.Group;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.StrokeLineCap;
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
import java.nio.file.LinkOption;
import java.nio.file.Path;
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
    private LaunchStep lastLaunching;   // the row whose beat already played
    @FXML public Button btnConfirmCancel, btnConfirmOk;
    // 1e / 1l (resume in progress, resume done) - see the resume section below
    @FXML public VBox resumingPane, restoredPane, resumingRows, restoredRows;
    @FXML public HBox titleState, restoredMeter, countdownTrack;
    @FXML public StackPane resumingMeter;
    @FXML public Region titleStateDot, resumingFill, resumingSheen, restoredFill, restoredFailFill, countdownFill;
    @FXML public Label titleStateVerb, titleStateName, resumingCount, resumingPct, resumingName,
            resumingSkipped, restoredCount, restoredTook, restoredSpec, restoredNote, restoredName,
            restoredWord;
    @FXML public Button btnResumeStop, btnStayHere, btnOpenProject, btnResumeRest;
    private final DoubleProperty resumingFrac = new SimpleDoubleProperty();
    private final DoubleProperty restoredFrac = new SimpleDoubleProperty();
    private final DoubleProperty restoredFailFrac = new SimpleDoubleProperty();
    private final DoubleProperty countdownFrac = new SimpleDoubleProperty();
    private volatile boolean resuming, stopRequested;
    /** The project whose checkpoint is capturing off the FX thread, or null when none is. */
    private volatile Project checkpointing;
    private Timeline countdown;
    private static final PseudoClass PC_LAUNCHING = PseudoClass.getPseudoClass("launching");
    private static final PseudoClass PC_FAILED    = PseudoClass.getPseudoClass("failed");
    /** the design's --ease-out */
    @FXML public TextField npName;
    @FXML public Label npFolder, npHint, npCaptureHint;
    @FXML public CheckBox npCapture;
    @FXML public Button btnNpBrowse, btnNpCancel, btnNpCreate;
    @FXML public ScrollPane settingsPane;
    @FXML public StackPane btnSettings, btnNewProjectRail;   // the sidebar's gear; the narrow rail's +
    // the rail opens to 200px (.rail-wide): a PROJECTS header, named rows, a New project button.
    // Switched from Settings (sidebarToggle) or Ctrl+B.
    @FXML public VBox rail;
    @FXML public HBox railHead;
    @FXML public Label railCount;
    @FXML public Button btnNewProjectWide, btnNewProjectEmpty;
    @FXML public CheckBox sidebarToggle;
    /** Same node Motion keeps "motion" in; projects.json stays projects only. */
    private static final Preferences PREFS = Preferences.userNodeForPackage(HelloController.class);
    @FXML public VBox settingsRows, captureRows, resumeRows;
    @FXML public Label settingsFoot;
    @FXML public CheckBox motionToggle;
    @FXML public Label deleteSub, deleteBody, deletePath;
    @FXML public Button btnDeleteOk, btnDeleteCancel, btnSettingsBack;
    @FXML public HBox btnResume, statusBox;
    // the split checkpoint button: ring, bar, idle content, done content, chevron segment
    @FXML public StackPane btnCheckpoint, btnCheckpointMenu;
    @FXML public HBox ckIdle, ckDone;
    @FXML public Region ckRing, ckBar;
    @FXML public StackPane btnDelete;
    // empty slots in the fxml that Icons draws into
    @FXML public StackPane resumeGlyphBox, ckDoneGlyphBox, emptyGlyphBox,
            deleteFolderGlyph, npFolderGlyph;
    @FXML public Region statusDot;
    @FXML public Label projectNameLabel, projectPathLabel, checkpointSub, ckDoneSub, tabsCount, statusLabel, resumeCounts;
    /** The full folder path, one hover away from the "…\\atlas-api" the header shows. */
    private Tooltip pathTip;
    /** The card lists four categories and folds the rest into a "+n more" row until asked. */
    private boolean showAllCategories;
    static final int CATEGORIES_SHOWN = 4;

    /** "connecting…": a 10px ring whose arc turns in place of the dot while no extension holds
        the socket yet but one is expected - at startup, and after the last one leaves. */
    private Group statusSpin;
    private RotateTransition spin;
    /** Until when (System.nanoTime) "no extension" still reads as "connecting". */
    private volatile long connectingUntil;
    private PauseTransition connectingEnds;
    /** A live extension retries every 3s; a suspended one is woken by chrome.alarms at most a
        minute later (ResumeOneExtension/background.js). Past this, nobody is coming. */
    static final Duration CONNECT_GRACE = Duration.seconds(65);

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
        clickable(btnResume, "Resume", this::onResume);
        clickable(btnCheckpoint, "Checkpoint", this::onCheckpoint);
        installCheckpointMenu();
        notch(btnResume, 18);
        // the block's -fx-shape draws it; this clip keeps the confirmation ring inside it too
        notch(btnCheckpoint, 14);
        clickable(btnDelete, "Delete project", this::showDeleteView);
        // the gear is a toggle: the lit gear is the natural "close" in a rail
        clickable(btnSettings, "Settings", () -> { if (settingsPane.isVisible()) backToProjects(); else showSettings(); });
        Tooltip.install(btnSettingsBack, tip("Esc"));
        for (Node b : List.of(btnNewProjectWide, btnNewProjectEmpty, btnSettingsBack, btnDeleteCancel, btnDeleteOk,
                btnNpBrowse, btnNpCancel, btnNpCreate, btnConfirmCancel, btnConfirmOk,
                btnResumeStop, btnStayHere, btnOpenProject, btnResumeRest)) {
            Motion.pressable(b);
        }
        // rwSweep: a quarter-width sheen crossing the fill, clipped to the fill
        resumingSheen.prefWidthProperty().bind(resumingMeter.widthProperty().multiply(.25));
        javafx.scene.shape.Rectangle sheenClip = new javafx.scene.shape.Rectangle();
        sheenClip.widthProperty().bind(resumingFill.widthProperty());
        sheenClip.heightProperty().bind(resumingMeter.heightProperty());
        resumingSheen.setClip(sheenClip);
        resumingSheen.translateXProperty().bind(resumingSheen.prefWidthProperty().multiply(sweepPhase.multiply(4).subtract(1)));
        sheenClip.xProperty().bind(resumingSheen.translateXProperty().negate());
        // what the app does, as plain check + label: these were disabled checkboxes, which read
        // as "locked" and rendered at 2:1. The copy states each behaviour as a fact.
        captureRows.getChildren().setAll(
                behaviourRow("Browser tabs, per browser, via the extension"),
                behaviourRow("Running apps, grouped by kind"));
        resumeRows.getChildren().setAll(
                behaviourRow("Browser tabs reopen in one window per browser"),
                behaviourRow("Claude reopens its conversations for the folder"),
                behaviourRow("Apps already running are left alone"),
                behaviourRow("coldstart stays on top while resuming"));
        clickable(btnNewProjectRail, "New project", this::onNewProjectClick);
        btnNewProjectWide.setOnAction(e -> onNewProjectClick());
        setRailWide(PREFS.getBoolean(SIDEBAR_WIDE, true));
        Tooltip.install(sidebarToggle, tip("Ctrl+B"));
        sidebarToggle.selectedProperty().addListener((o, was, on) -> setRailWide(on));
        motionToggle.setSelected(!Motion.REDUCED);
        // everything simply plays, or not, next time
        motionToggle.selectedProperty().addListener((o, was, on) -> Motion.setEnabled(on));
        installGlyphs();
        // the scene arrives after initialize(); accelerators and Escape hang off it once
        detailPane.sceneProperty().addListener((o, was, scene) -> {
            if (scene != null && was == null) {
                installKeys(scene);
                trapFocus(scene);
            }
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
        // the name is the project's key: a second "atlas-api" would replace the first one's
        // checkpoint, so a taken name cannot be created and the hint says why
        btnNpCreate.disableProperty().bind(Bindings.createBooleanBinding(
                () -> npName.getText().trim().isEmpty() || nameTaken(projects, npName.getText()), npName.textProperty()));
        npName.textProperty().addListener((o, was, now) -> {
            boolean taken = nameTaken(projects, now);
            npHint.setText(taken ? "a project named " + now.trim() + " already exists" : "shown in the sidebar");
            npHint.pseudoClassStateChanged(PC_FAILED, taken);
        });
        btnNpCreate.setOnAction(e -> createFromDialog());
        npName.setOnAction(e -> { if (!btnNpCreate.isDisabled()) createFromDialog(); });   // Enter creates
        npName.setAccessibleText("Project name");
        btnNpBrowse.setAccessibleText("Browse for the project folder");
        btnNpCreate.textProperty().bind(Bindings.when(npCapture.selectedProperty())
                .then("Create + checkpoint").otherwise("Create"));
        btnResumeStop.setGraphic(Icons.node(Icons.CLOSE, 16, TX2));
        Icons.animate(btnResumeStop, btnResumeStop.getGraphic());
        btnResumeStop.setOnAction(e -> stopRequested = true);
        btnStayHere.setOnAction(e -> stopCountdown());
        btnResumeRest.setGraphic(Icons.node(Icons.RESUME, 14, GO));
        btnResumeRest.setOnAction(e -> { if (shownRun != null) resumeRest(shownRun); });
        btnOpenProject.setGraphic(Icons.node(Icons.CHEVRON, 16, TX));
        btnOpenProject.setOnAction(e -> { stopCountdown(); backToProjects(); });
        // meters are fractions bound to their track, so they are right before first layout
        resumingFill.prefWidthProperty().bind(resumingMeter.widthProperty().multiply(resumingFrac));
        restoredFill.prefWidthProperty().bind(restoredMeter.widthProperty().multiply(restoredFrac));
        restoredFailFill.prefWidthProperty().bind(restoredMeter.widthProperty().multiply(restoredFailFrac));
        countdownFill.prefWidthProperty().bind(countdownTrack.widthProperty().multiply(countdownFrac));
        pathTip = tip("");
        Tooltip.install(projectPathLabel, pathTip);
        Circle track = new Circle(5, Color.TRANSPARENT);
        track.setStroke(Color.web("#464A52"));
        track.setStrokeWidth(1.6);
        Arc arc = new Arc(0, 0, 5, 5, 90, -110);
        arc.setType(ArcType.OPEN);
        arc.setFill(null);
        arc.setStroke(Color.web(TX2));
        arc.setStrokeWidth(1.6);
        arc.setStrokeLineCap(StrokeLineCap.ROUND);
        // a Group, so it turns about the ring's centre rather than the arc's own bounds
        statusSpin = new Group(track, arc);
        statusBox.getChildren().add(0, statusSpin);
        spin = new RotateTransition(Duration.millis(900), statusSpin);
        spin.setByAngle(360);
        spin.setCycleCount(Animation.INDEFINITE);
        spin.setInterpolator(Interpolator.LINEAR);
        startConnecting();
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
        // a new project's capture would land in the middle of a launch, or race the one running
        if (resuming || checkpointing != null) return;
        npName.clear();
        npFolder.setText(NO_FOLDER);
        npCapture.setSelected(true);
        // said before Create, not discovered afterwards as an empty TABS column
        npCaptureHint.setText(wsSessions.isEmpty()
                ? "records the apps open now · no browser extension is connected, so no tabs"
                : "records the apps open now, plus tabs from " + wsSessions.stream()
                        .map(s -> browserLabel(wsBrowser(s))).distinct().sorted()
                        .collect(java.util.stream.Collectors.joining(" and ")));
        showOverlay(newProjectPane);
        npName.requestFocus();
    }

    /** Create + checkpoint: same work the old dialog's OK branch did. */
    private void createFromDialog() {
        String name = npName.getText().trim();
        if (name.isEmpty() || nameTaken(projects, name)) return;
        String folder = NO_FOLDER.equals(npFolder.getText()) ? null : npFolder.getText();
        Project projectObj = new Project(name, folder, new HashMap<>(), new ArrayList<>());
        // "no checkpoint yet" until one lands: a null lastCheckpoint also keeps createProjectUI
        // from capturing on the FX thread
        projectObj.lastCheckpoint = null;
        // createProjectUI -> selectProject -> showBase() clears the overlay for us
        createProjectUI(projectObj, true);
        // the same off-thread checkpoint as the block: "checkpointing…", then "Saved"
        if (npCapture.isSelected()) writeCheckpoint(projectObj, Checkpoint.OVERWRITE);
    }

    /** Names are compared the way a person reads them: "Atlas-API" is "atlas-api" taken. */
    static boolean nameTaken(List<Project> projects, String name) {
        String n = name.trim();
        return projects.stream().anyMatch(p -> p.projectName.equalsIgnoreCase(n));
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
            // initial capture when a new project is made; saveProject() upserts into projects.
            // A null lastCheckpoint is the dialog's "Checkpoint immediately" left unticked.
            activeProject = projectObj;
            try {
                if (projectObj.lastCheckpoint != null) projectObj.apps = segregateApps(captureRunningApps());
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
        clickable(row, projectObj.projectName, () -> selectProject(projectObj));

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

    /** Every glyph on the HUD, drawn into the empty slots the fxml leaves. Action glyphs play
        their slide animation on press. Sizes and strokes are the handoff's. */
    private void installGlyphs() {
        Pane trash = Icons.node(Icons.DELETE, 16, TX2);
        btnDelete.getChildren().add(trash);
        Icons.animate(btnDelete, trash);
        btnDelete.getStyleClass().add("delete");
        for (var e : Map.of(btnNewProjectRail, Icons.NEW, btnSettings, Icons.SETTINGS).entrySet()) {
            Pane g = Icons.node(e.getValue(), 16, TX3);
            e.getKey().getChildren().add(g);
            Icons.animate(e.getKey(), g);
        }
        Tooltip.install(btnNewProjectRail, tip("New project"));
        graphic(btnNewProjectWide, Icons.NEW, TX);
        graphic(btnNewProjectEmpty, Icons.NEW, TX);
        graphic(btnNpBrowse, Icons.BROWSE, TX2);
        graphic(btnNpCreate, Icons.BOOKMARK_PLUS, TX);
        for (CheckBox cb : List.of(motionToggle, sidebarToggle, npCapture)) iconCheck(cb);

        Pane play = Icons.node(Icons.RESUME, 30, GOI, 2.4);
        resumeGlyphBox.getChildren().add(play);
        Icons.animate(btnResume, play);
        // the checkpoint block keeps its earlier slate; its "Saved" check matches that sub-line
        ckDoneGlyphBox.getChildren().add(Icons.node(Icons.CHECK, 22, "#7A94BC", 2.2));
        emptyGlyphBox.getChildren().add(Icons.node(Icons.BOOKMARK_PLUS, 40, CK));
        deleteFolderGlyph.getChildren().add(Icons.node(Icons.OTHER, 16, TX3));
        npFolderGlyph.getChildren().add(Icons.node(Icons.OTHER, 16, TX3));
    }

    /** A CheckBox drawn as the slide's CHECKBOX glyph: the box always, the tick only while
        selected, drawn in (the slide's rw-draw) each time it is ticked. hud.css colours it. */
    private static void iconCheck(CheckBox cb) {
        Pane glyph = Icons.node(Icons.CHECKBOX, SETTING_GLYPH, TX3);
        Node tick = glyph.lookupAll(".ico").stream()
                .filter(n -> "draw".equals(n.getProperties().get("anim"))).findFirst().orElseThrow();
        cb.setGraphic(glyph);
        tick.setVisible(cb.isSelected());
        cb.selectedProperty().addListener((o, was, on) -> {
            tick.setVisible(on);
            if (on) Icons.play(glyph);
        });
    }

    /** A Button's leading glyph, played on press. */
    private static void graphic(Button b, Icons.Glyph glyph, String color) {
        b.setGraphic(Icons.node(glyph, 16, color));
        Icons.animate(b, b.getGraphic());
    }

    /** clip-path: polygon(0 0, 100%-c 0, 100% c, 100% 100%, c 100%, 0 100%-c) - the top-right
        and bottom-left corners cut at 45°, re-cut on every resize so the cut stays c px. */
    static void notch(Region r, double c) {
        javafx.scene.shape.Polygon clip = new javafx.scene.shape.Polygon();
        r.layoutBoundsProperty().addListener((o, was, b) -> clip.getPoints().setAll(notchPoints(b.getWidth(), b.getHeight(), c)));
        r.setClip(clip);
    }

    static List<Double> notchPoints(double w, double h, double c) {
        return List.of(0.0, 0.0, w - c, 0.0, w, c, w, h, c, h, 0.0, h - c);
    }

    private void clickable(Node node, String name, Runnable action) {
        node.setFocusTraversable(true);
        // a StackPane/HBox reads to Narrator as nothing at all; these make it a named button
        node.setAccessibleRole(AccessibleRole.BUTTON);
        node.setAccessibleText(name);
        Motion.pressable(node);
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
        // Ctrl+1..9 selects the project the rail numbers 01..09, from any view
        for (int i = 1; i <= 9; i++) {
            int n = i;
            Runnable jump = () -> { if (!overlayOpen() && !resuming) jumpTo(n); };
            scene.getAccelerators().put(new KeyCodeCombination(KeyCode.valueOf("DIGIT" + i), KeyCombination.SHORTCUT_DOWN), jump);
            scene.getAccelerators().put(new KeyCodeCombination(KeyCode.valueOf("NUMPAD" + i), KeyCombination.SHORTCUT_DOWN), jump);
        }
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN),
                () -> { if (!overlayOpen() && detailPane.isVisible()) undoCheckpoint(); });
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.B, KeyCombination.SHORTCUT_DOWN),
                () -> { if (!overlayOpen()) setRailWide(!rail.getStyleClass().contains("rail-wide")); });
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() != KeyCode.ESCAPE) return;
            if (resumingPane.isVisible()) {
                stopRequested = true;
                e.consume();
                return;
            }
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

    /** The n-th project in rail order (1-based), or nothing past the end. */
    private void jumpTo(int n) {
        if (n > projects.size()) return;
        stopCountdown();   // from the resume-done screen, the jump wins over its timer
        selectProject(projects.get(n - 1));
    }

    private boolean overlayOpen() {
        return confirmPane.isVisible() || newProjectPane.isVisible() || deletePane.isVisible();
    }

    /** Keeps Tab inside an open dialog: focus that leaves it is put back where it was, or on
        the dialog's first control. Without this, Tab walked out to the rail behind the scrim. */
    private void trapFocus(javafx.scene.Scene scene) {
        scene.focusOwnerProperty().addListener((o, was, now) -> {
            Node open = deletePane.isVisible() ? deletePane : newProjectPane.isVisible() ? newProjectPane
                    : confirmPane.isVisible() ? confirmPane : null;
            if (open == null || now == null || inside(now, open)) return;
            Node back = was != null && inside(was, open) ? was
                    : open == deletePane ? btnDeleteCancel : open == newProjectPane ? npName : btnConfirmCancel;
            Platform.runLater(back::requestFocus);
        });
    }

    private static boolean inside(Node node, Node parent) {
        for (Node n = node; n != null; n = n.getParent()) if (n == parent) return true;
        return false;
    }

    /** 1h: behaviour rows are fixed copy; the STATUS column is live and re-renders on socket events. */
    private void showSettings() {
        renderSettingsRows();
        showBase(settingsPane);
        // one lit control at a time, and right now that is the gear
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
        HBox storageRow = statRow(Icons.DATABASE, "Storage", storage + shown, storage + path);
        // the one row that acts: open the data folder. Chevron + hand say so; the other two stay flat.
        storageRow.getChildren().add(Icons.node(Icons.CHEVRON, 14, TX3));
        storageRow.getStyleClass().add("stat-link");
        clickable(storageRow, "Open the data folder", () -> {
            try {
                Desktop.getDesktop().open(projectsFile().getParentFile());
            } catch (IOException | IllegalArgumentException ex) {
                System.err.println("open data folder: " + ex.getMessage());
            }
        });
        settingsRows.getChildren().setAll(
                statRow(Icons.BROWSER, "Browser extensions",
                        browsers.isEmpty() ? (System.nanoTime() < connectingUntil ? "connecting\u2026"
                                                                           : "none connected \u00b7 install the coldstart extension")
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
        sweep(target == resumingPane);
    }

    /** rwSweep: translateX(-100%) → (300%), 1400ms linear, while the launching strip is up.
        A 0..1 phase rather than pixels, so it is right before the strip's first layout. */
    private final DoubleProperty sweepPhase = new SimpleDoubleProperty();
    private Timeline sweep;
    private void sweep(boolean on) {
        if (sweep != null) sweep.stop();
        resumingSheen.setVisible(on && !Motion.REDUCED);
        if (!resumingSheen.isVisible()) return;
        sweep = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(sweepPhase, 0)),
                new KeyFrame(Duration.millis(1400), new KeyValue(sweepPhase, 1, Interpolator.LINEAR)));
        sweep.setCycleCount(Timeline.INDEFINITE);
        sweep.play();
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
        // the capture would write the project straight back after it was deleted
        if (activeProject == null || checkpointing == activeProject) return;
        String age = ago(activeProject.lastCheckpoint);
        deleteSub.setText(countApps(activeProject) + " apps \u00b7 " + countTabs(activeProject) + " tabs \u00b7 "
                + ("never".equals(age) ? "no checkpoint yet" : "checkpoint " + when(age)));
        deleteBody.setText("The checkpoint for " + activeProject.projectName
                + " is deleted. Your files and folder stay where they are.");
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
        // rows and projects share one order; a delete renumbers both, and the Ctrl+n hints
        for (Project p : projects) {
            StackPane row = rowsByProject.get(p.projectName);
            if (row != null) railTip(row, p);
        }
    }

    /** Wide is the default. A new key, not "railWide": that one still holds whatever the old
        chevron last left, and everyone starts wide once the chevron is gone. */
    private static final String SIDEBAR_WIDE = "sidebarWide";

    /** 200px with names, or the 48px numbered rail. Instant: width never animates (it would
        reflow the card every frame). Remembered across launches. */
    private void setRailWide(boolean wide) {
        rail.getStyleClass().remove("rail-wide");
        if (wide) rail.getStyleClass().add("rail-wide");
        // the narrow rail has no header at all, so its first number sits where PROJECTS would
        for (Node n : List.of(railHead, btnNewProjectWide)) {
            n.setVisible(wide);
            n.setManaged(wide);
        }
        btnNewProjectRail.setVisible(!wide);
        btnNewProjectRail.setManaged(!wide);
        sidebarToggle.setSelected(wide);   // Ctrl+B keeps the Settings box honest; no-op from the box
        PREFS.putBoolean(SIDEBAR_WIDE, wide);
    }

    /** The wide rail's face of a row, SidebarItem: a 15px folder (open on the active row, via
        hud.css), the name and the checkpoint's age. */
    private static HBox sideFace(String name) {
        Pane shut = Icons.node(Icons.OTHER, 15, TX2), open = Icons.node(Icons.BROWSE, 15, TX);
        shut.getStyleClass().add("folder-shut");
        open.getStyleClass().add("folder-open");
        Label label = new Label(name);
        label.getStyleClass().add("side-name");
        label.setMaxWidth(Double.MAX_VALUE);
        label.setTextOverrun(OverrunStyle.ELLIPSIS);
        HBox.setHgrow(label, Priority.ALWAYS);
        Label age = new Label();
        age.getStyleClass().add("side-count");
        HBox face = new HBox(12, new StackPane(shut, open), label, age);
        face.getStyleClass().add("side-face");
        face.setAlignment(Pos.CENTER_LEFT);
        return face;
    }

    /** The row's age ("2h") and its tooltip, refreshed whenever a checkpoint lands. */
    private void railTip(StackPane row, Project projectObj) {
        String age = ago(projectObj.lastCheckpoint);
        Tooltip t = (Tooltip) row.getProperties().get("tip");
        int n = projects.indexOf(projectObj) + 1;
        t.setText(projectObj.projectName + " \u00b7 " + ("never".equals(age) ? "no checkpoint yet" : "checkpoint " + when(age))
                + (n >= 1 && n <= 9 ? " \u00b7 Ctrl+" + n : ""));
        // the narrow rail shows only "01": the name is in the tooltip, which focus never opens
        row.setAccessibleText(t.getText());
        if (row.lookup(".side-count") instanceof Label count) {
            count.setText("never".equals(age) ? "" : "just now".equals(age) ? "now" : age);
        }
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
        showEmpty(false);
        renderDetail(projectObj);
        if (switching && Motion.showing(detailPane)) panelIn(detailPane);
    }

    /** Fills every label on the project card from the Project; safe to call repeatedly
        (checkpoint and the /tabs handler both re-run it). */
    private void renderDetail(Project projectObj) {
        migrateLegacyUrls(projectObj);

        projectNameLabel.setText(projectObj.projectName);
        projectPathLabel.setText(projectObj.folderPath == null ? "no folder" : "\u2026\\" + new File(projectObj.folderPath).getName());
        pathTip.setText(projectObj.folderPath == null ? "no folder chosen" : projectObj.folderPath);

        int apps = launchableApps(projectObj);
        int tabs = countTabs(projectObj);

        btnResume.setDisable(apps == 0 && tabs == 0);
        // the count is the proof: what one press brings back, on the button that does it - so it
        // counts what Resume launches, not everything captured (Other and handled browsers are not)
        resumeCounts.setText(apps > 0 || tabs > 0 ? resumeCountLine(apps, tabs)
                : countApps(projectObj) > 0 ? "NOTHING TO RELAUNCH" : "NOTHING SAVED YET");
        checkpointSub.setText(checkpointing == projectObj ? CHECKPOINTING : checkpointLine(projectObj.lastCheckpoint));
        tabsCount.setText(String.valueOf(tabs));

        renderCategories(projectObj);
        renderTabs(projectObj);
    }

    // the design folds at four categories and links to an inspector; that artboard is not
    // built, so every non-empty category is listed rather than hidden behind a dead link
    private static final List<String> CATEGORY_ORDER = List.of(
            "IDE", "AI", "Terminal", "Browser", "Database", "API", "Containers",
            "VersionControl", "Design", "Communication", "Notes", "Music");

    /** "2h ago" under Checkpoint: when the last one was taken. It used to read "overwrite \u00b7
        2h old", a warning on every press; a checkpoint is now one Ctrl+Z from undone. */
    static String checkpointLine(String lastCheckpoint) {
        String age = ago(lastCheckpoint);
        if ("never".equals(age)) return "no checkpoint yet";
        return when(age);
    }

    /** "10 APPS · 14 TABS" for the RESUME block. */
    static String resumeCountLine(int apps, int tabs) {
        return apps + (apps == 1 ? " APP" : " APPS") + " \u00b7 " + tabs + (tabs == 1 ? " TAB" : " TABS");
    }

    private void renderCategories(Project projectObj) {
        categoryRows.getChildren().clear();
        if (projectObj.apps == null) return;

        List<String> filled = CATEGORY_ORDER.stream()
                .filter(c -> projectObj.apps.get(c) != null && !projectObj.apps.get(c).isEmpty()).toList();
        // one past the limit just shows: a "+1 more" row would take the same line as the row it hides
        boolean fold = !showAllCategories && filled.size() > CATEGORIES_SHOWN + 1;
        for (String category : fold ? filled.subList(0, CATEGORIES_SHOWN) : filled) {
            HashSet<String> bucket = projectObj.apps.get(category);

            // a 120px label column: 12px glyph + the 12px label; "Communication" is the widest
            HBox labelBox = new HBox(6);
            labelBox.setAlignment(Pos.CENTER_LEFT);
            labelBox.setMinWidth(120);
            labelBox.setPrefWidth(120);
            labelBox.setMaxWidth(120);
            labelBox.getChildren().addAll(categoryIcon(category),
                    styled(new Label(categoryLabel(category)), "cat-label"));

            // flex-wrap: as wide as its chips, wrapping only when the row runs out. FlowPane
            // otherwise asks for a fixed 400px, which pushed the note off its chip.
            FlowPane chips = new FlowPane(5, 5) {
                @Override protected double computePrefWidth(double height) {
                    double w = -getHgap();
                    for (Node n : getManagedChildren()) w += n.prefWidth(-1) + getHgap();
                    return Math.max(0, w) + snappedLeftInset() + snappedRightInset();
                }
            };
            chips.setAlignment(Pos.CENTER_LEFT);
            chips.setMinWidth(0);
            for (String app : new TreeSet<>(bucket)) {
                chips.getChildren().add(chip(projectObj, category, app));
            }

            HBox row = new HBox(10);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getChildren().addAll(labelBox, chips);

            String note = trailingNote(category, projectObj);
            if (note != null) {
                Label arg = styled(new Label(note), "cat-arg");
                arg.setMinWidth(Region.USE_PREF_SIZE);   // white-space: nowrap
                row.getChildren().add(arg);
            }

            categoryRows.getChildren().add(row);
        }
        if (fold || (showAllCategories && filled.size() > CATEGORIES_SHOWN + 1)) {
            List<String> rest = filled.subList(CATEGORIES_SHOWN, filled.size());
            HBox more = new HBox(9, Icons.node(Icons.CHEVRON, 12, TX3), styled(new Label(fold
                    ? "+" + rest.size() + " more \u00b7 " + String.join(", ", rest.stream().map(HelloController::categoryLabel).toList())
                    : "show fewer"), "cat-foot"));
            more.setAlignment(Pos.CENTER_LEFT);
            more.getStyleClass().add("cat-more");
            clickable(more, fold ? "Show all categories" : "Show fewer categories", () -> { showAllCategories = !showAllCategories; renderCategories(projectObj); });
            categoryRows.getChildren().add(more);
        }

        // "Other" is captured but never relaunched, which the design calls out by name
        HashSet<String> other = projectObj.apps.get(OTHER);
        if (other != null && !other.isEmpty()) {
            String first = appName(new TreeSet<>(other).first());
            String rest = other.size() > 1 ? " (+" + (other.size() - 1) + ")" : "";
            HBox foot = new HBox(9, Icons.node(Icons.APP_WINDOW, 12, TX3),
                    styled(new Label(first + rest + " under Other \u2014 captured, not relaunched"), "cat-foot"));
            foot.setAlignment(Pos.CENTER_LEFT);
            categoryRows.getChildren().add(foot);
        }
    }

    // only where the exe name is not the app's name; everything else is the exe, capitalised
    private static final Map<String, String> APP_NAMES = Map.ofEntries(
            Map.entry("code", "VS Code"), Map.entry("code - insiders", "VS Code Insiders"),
            Map.entry("idea", "IntelliJ IDEA"), Map.entry("pycharm", "PyCharm"), Map.entry("webstorm", "WebStorm"),
            Map.entry("phpstorm", "PhpStorm"), Map.entry("clion", "CLion"), Map.entry("goland", "GoLand"),
            Map.entry("rubymine", "RubyMine"), Map.entry("datagrip", "DataGrip"), Map.entry("studio", "Android Studio"),
            Map.entry("devenv", "Visual Studio"), Map.entry("sublime_text", "Sublime Text"),
            Map.entry("sublime_merge", "Sublime Merge"), Map.entry("notepad++", "Notepad++"),
            Map.entry("windowsterminal", "Windows Terminal"), Map.entry("wezterm-gui", "WezTerm"),
            Map.entry("conemu", "ConEmu"), Map.entry("msedge", "Edge"), Map.entry("githubdesktop", "GitHub Desktop"),
            Map.entry("sourcetree", "Sourcetree"), Map.entry("gitkraken", "GitKraken"), Map.entry("ms-teams", "Teams"),
            Map.entry("ssms", "SSMS"), Map.entry("pgadmin4", "pgAdmin"), Map.entry("mysqlworkbench", "MySQL Workbench"),
            Map.entry("mongodbcompass", "MongoDB Compass"), Map.entry("dbeaver", "DBeaver"),
            Map.entry("heidisql", "HeidiSQL"), Map.entry("tableplus", "TablePlus"), Map.entry("chatgpt", "ChatGPT"));

    /** "idea64.exe" -> "IntelliJ IDEA", "WindowsTerminal.exe" -> "Windows Terminal",
        "obsidian.exe" -> "Obsidian": what a person calls the app, not what Windows does. */
    static String appName(String exe) {
        String base = exe.replaceFirst("(?i)\\.exe$", "");
        String key = base.toLowerCase().replaceFirst("64$", "");
        String known = APP_NAMES.get(key);
        if (known != null) return known;
        String name = base.replaceFirst("64$", "");
        return name.isEmpty() ? exe : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /** "VersionControl" -> "Version control": the map key, spaced and in sentence case, which
        reads faster than tracked-out caps JavaFX cannot track. Acronyms (IDE, AI, API) stay. */
    static String categoryLabel(String category) {
        if (category.equals(category.toUpperCase())) return category;
        String spaced = category.replaceAll("([a-z])([A-Z])", "$1 $2");
        return spaced.charAt(0) + spaced.substring(1).toLowerCase();
    }

    /** The extra argument resumeProject() will actually use for this category: claude opens its
        agent view, VS Code opens the project folder ("…\atlas-api"). */
    static String trailingNote(String category, Project projectObj) {
        if ("AI".equals(category) && hasApp(projectObj, "claude.exe")) {
            return "reopens chats";
        }
        if ("IDE".equals(category) && hasApp(projectObj, "Code.exe") && projectObj.folderPath != null) {
            return "…\\" + new File(projectObj.folderPath).getName();
        }
        return null;
    }

    /** The column scrolls; every header and tab row is always in it. */
    private void renderTabs(Project projectObj) {
        List<Node> tabRows = new ArrayList<>();
        projectObj.urlsByBrowser.forEach((browser, urls) -> {
            HBox head = new HBox(8);
            head.setAlignment(Pos.CENTER_LEFT);
            // every group after the first sits 4px further down, as margin-top:4px does
            if (!tabRows.isEmpty()) VBox.setMargin(head, new Insets(4, 0, 0, 0));
            Label name = styled(new Label(browserLabel(browser)), "browser-name");
            head.getChildren().addAll(name, styled(new Label(String.valueOf(urls.size())), "side-count"));
            tabRows.add(head);

            for (String url : urls) {
                // site + path: six github.com rows told nobody which six pages they were.
                // The query and fragment are one hover away with the rest of the url.
                Label item = styled(new Label(tabLabel(url)), "tab-title");
                item.setMaxWidth(Double.MAX_VALUE);
                item.setTextOverrun(OverrunStyle.ELLIPSIS);
                HBox.setHgrow(item, Priority.ALWAYS);
                Tooltip.install(item, tip(url));
                HBox row = new HBox(2, item, remover(() -> removeUrl(projectObj, browser, url), projectObj, 12, url));
                row.getStyleClass().add("tab-row");
                row.setAlignment(Pos.CENTER_LEFT);
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

    /** "github.com/atlas-api/pull/42" from "https://www.github.com/atlas-api/pull/42?tab=files#x". */
    static String tabLabel(String url) {
        String s = url.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "").replaceFirst("^www\\.", "");
        s = s.replaceFirst("[?#].*$", "");
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /** "github.com" from "https://www.github.com/atlas-api/pull/42". */
    static String site(String url) {
        String s = url.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "").replaceFirst("^www\\.", "");
        int cut = s.indexOf('/');
        return cut < 0 ? s : s.substring(0, cut);
    }

    /** The app's name, then the hover-only x at the right end (the row's glyph already names
        the kind). The exe it was captured as is on hover. */
    private HBox chip(Project projectObj, String category, String app) {
        HBox chip = new HBox(2, new Label(appName(app)), remover(() -> removeApp(projectObj, category, app), projectObj, 12, appName(app)));
        Tooltip.install(chip, tip(app));
        chip.getStyleClass().add("chip");
        chip.setAlignment(Pos.CENTER_LEFT);
        return chip;
    }

    /** The x on chips and tab rows, shown on hover or keyboard focus. Opacity, not managed, so
        nothing shifts. No undo: the next checkpoint brings anything back. */
    private StackPane remover(Runnable edit, Project projectObj, double size, String what) {
        StackPane x = new StackPane();
        x.getStyleClass().add("rm");
        Pane glyph = Icons.node(Icons.CLOSE, size, TX3);
        glyph.getStyleClass().add("rm-glyph");
        x.getChildren().add(glyph);
        Icons.animate(x, glyph);
        // in the Tab order and shown on focus (hud.css), so it is not mouse-only
        x.setFocusTraversable(true);
        x.setAccessibleRole(AccessibleRole.BUTTON);
        x.setAccessibleText("Remove " + what);
        Runnable remove = () -> {
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
        };
        x.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) {
                e.consume();
                remove.run();
            }
        });
        x.setOnMouseClicked(e -> {
            e.consume();
            if (e.getButton() == MouseButton.PRIMARY) remove.run();
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
        return Icons.node(Icons.CATEGORY.getOrDefault(category, Icons.APP_WINDOW), 12, HUD);
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
        // a resume during a checkpoint's capture would be recorded into that checkpoint
        if (activeProject == null || resuming || checkpointing != null || btnResume.isDisabled()) return;
        startRun(new ResumeRun(activeProject), true);
    }

    /** "Resume the rest" on a stopped 1l: the same run, picking up at its first queued step. */
    private void resumeRest(ResumeRun r) {
        if (resuming || checkpointing != null) return;
        stopCountdown();
        startRun(r, false);
    }

    /** Shows 1e and walks the run's queued steps on a worker; plan=true captures and plans first. */
    private void startRun(ResumeRun r, boolean plan) {
        resuming = true;
        stopRequested = false;
        r.stopped = false;
        r.error = null;
        lastLaunching = null;
        titleState("RESUMING", r.project.projectName, false);
        resumingName.setText(r.project.projectName);
        showBase(resumingPane);
        renderResuming(r);

        long startNanos = System.nanoTime();
        Thread worker = new Thread(() -> {
            try {
                if (plan) {
                    // the PowerShell capture takes seconds: off the FX thread it no longer freezes the HUD
                    r.plan = planResume(r.project, captureRunningApps(), r.log);
                    Platform.runLater(() -> renderResuming(r));
                }
                for (LaunchStep step : r.plan.steps()) {
                    if (step.state != LaunchStep.State.QUEUED) continue;   // ran before the stop
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
                // without the list of open apps there is no telling what would open twice, so
                // nothing is launched and 1l says so instead of "RESUMED"
                r.error = ex.getMessage() == null ? "the open apps could not be listed" : ex.getMessage();
                r.log.add("resume failed: " + r.error);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                r.tookMs += (System.nanoTime() - startNanos) / 1_000_000;
                r.stopped = r.error == null && r.queued() > 0;
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

        List<String> skipped = r.plan.skipped();
        resumingSkipped.setText(skipped.isEmpty() ? "" : skipped.size() + " already open, left alone · " + names(skipped));
    }

    /** The run 1l is showing, for "Resume the rest". */
    private ResumeRun shownRun;

    /** How a run ended, as 1l's verb: only a run that got through its plan says RESUMED. */
    static String outcome(ResumeRun r) {
        return r.error != null ? "NOT RESUMED" : r.stopped ? "STOPPED" : "RESUMED";
    }

    /** 1l. Orange closes the verb. A clean run goes back to 1b in three seconds; a stopped run,
        a failed capture or a failed launch stays until it is dealt with or left, so it can be read. */
    private void showRestored(ResumeRun r) {
        shownRun = r;
        String verb = outcome(r);
        boolean clean = r.error == null && !r.stopped && r.failed() == 0;
        titleState(verb, r.project.projectName, clean);
        restoredWord.setText(verb);
        restoredName.setText(r.project.projectName);
        logOpen = false;
        showBase(restoredPane);
        renderRestored(r);
        // no countdown and no "Stay here" unless the run is finished and clean
        btnStayHere.setVisible(clean);
        btnStayHere.setManaged(clean);
        btnResumeRest.setVisible(r.stopped);
        btnResumeRest.setManaged(r.stopped);
        if (clean) {
            startCountdown(r);
            btnStayHere.requestFocus();   // one keypress keeps the screen, Esc leaves it
        } else if (r.stopped) {
            // STOPPED and "3 / 10" are already on screen; the note adds only what is left
            restoredNote.setText(r.queued() + " not launched \u00b7 Esc to go back");
            btnResumeRest.requestFocus();
        } else if (r.error != null) {
            restoredNote.setText("Retry, or Esc to go back");
            if (restoredRows.lookup(".fail-row .button") instanceof Button retry) retry.requestFocus();
        } else {
            restoredNote.setText(r.failed() + " failed \u00b7 Retry it, or Esc to go back");
            if (restoredRows.lookup(".fail-row .button") instanceof Button retry) retry.requestFocus();
        }
    }

    /** The resume log under 1l, shown only when asked for. */
    private boolean logOpen;

    private void renderRestored(ResumeRun r) {
        int total = r.total();
        restoredCount.setText(r.done() + " / " + total);
        restoredTook.setText(String.format("took %.1fs", r.tookMs / 1000.0));
        restoredFrac.set(total == 0 ? 0 : (double) r.done() / total);
        restoredFailFrac.set(total == 0 ? 0 : (double) r.failed() / total);
        int tabsSent = tabsSent(r);
        restoredSpec.setText(r.appsDone() + " apps · " + tabsSent + " tabs");

        restoredRows.getChildren().clear();
        if (r.error != null) {
            // nothing was planned, so the launched / sent rows below would only say zero twice
            restoredSpec.setText("nothing launched");
            // the cause is whatever the capture threw; it rides on the hover, not on a guess here
            restoredRows.getChildren().add(failRow("open apps", "could not be listed", r.error, this::onResume));
        }
        List<String> browsers = new ArrayList<>(), apps = new ArrayList<>();
        for (LaunchStep step : r.plan.steps()) {
            if (step.state == LaunchStep.State.FAILED) restoredRows.getChildren().add(failRow(r, step));
            if (step.state == LaunchStep.State.DONE) (step.browser ? browsers : apps).add(step.label);
        }
        if (r.error == null) {
            restoredRows.getChildren().add(foldRow(Icons.APP_WINDOW, apps.size() + " apps launched",
                    apps.isEmpty() ? "nothing new to open" : names(apps)));
            restoredRows.getChildren().add(foldRow(Icons.BROWSER, tabsSent + " tabs sent",
                    browsers.isEmpty() ? "no browser opened" : String.join(" · ", browsers)));
        }
        // the RESUME block counted these too; without this row "6 apps" would end as "4 apps"
        // with nothing to say where the other two went
        List<String> skipped = r.plan.skipped();
        if (!skipped.isEmpty()) {
            restoredRows.getChildren().add(foldRow(Icons.CHECK, skipped.size() + " already open, left alone", names(skipped)));
        }
        HBox log = foldRow(Icons.TERMINAL, "Resume log", r.log.size() + " lines · " + (logOpen ? "hide" : "show"));
        clickable(log, logOpen ? "Hide the resume log" : "Show the resume log", () -> { logOpen = !logOpen; renderRestored(r); });
        restoredRows.getChildren().add(log);
        if (logOpen) {
            Label lines = styled(new Label(String.join("\n", r.log)), "foot-note");
            lines.setWrapText(true);
            ScrollPane scroll = new ScrollPane(lines);
            scroll.getStyleClass().add("tabs-scroll");
            scroll.setFitToWidth(true);
            scroll.setMaxHeight(140);
            restoredRows.getChildren().add(scroll);
        }
    }

    /** Tabs handed to a browser that started: every saved url of each browser step that ran. */
    private static int tabsSent(ResumeRun r) {
        int n = 0;
        for (LaunchStep step : r.plan.steps()) {
            if (step.browser && step.state == LaunchStep.State.DONE) {
                List<String> urls = r.project.urlsByBrowser.get(step.label);
                if (urls != null) n += urls.size();
            }
        }
        return n;
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
        // a browser's "14 urls · one window" says something; "cmd /c start wt" does not - the
        // raw command stays in the resume log
        String cmd = switch (step.state) {
            case LAUNCHING -> step.browser ? step.command : "launching";
            case FAILED -> failure(step);
            default -> step.browser ? step.command : "";
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
        row.getChildren().addAll(Icons.node(iconPath, 18, TX2), text);
        return row;
    }

    /** Settings glyphs are 20px: big enough to read the tick at a glance. */
    private static final int SETTING_GLYPH = 20;

    /** Settings: one behaviour the app has, stated as a fact. A plain tick, not the toggles'
        checkbox: a box that does not respond to a click read as a broken setting. */
    private HBox behaviourRow(String text) {
        HBox row = new HBox(10, Icons.node(Icons.CHECK, SETTING_GLYPH, TX3), styled(new Label(text), "behaviour"));
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private HBox failRow(ResumeRun r, LaunchStep step) {
        return failRow(step.label, failure(step), step.error, () -> retry(r, step));
    }

    /** A failure that stays on screen: what, why in the OS's words (full text on hover), Retry. */
    private HBox failRow(String label, String whyText, String tipText, Runnable onRetry) {
        HBox row = rowShell("fail-row");
        row.setMinHeight(56);
        row.setPrefHeight(56);
        Label why = styled(new Label(whyText), "fail-why");
        why.setMaxWidth(Double.MAX_VALUE);
        if (tipText != null) Tooltip.install(why, tip(tipText));
        HBox.setHgrow(why, Priority.ALWAYS);
        Button retry = styled(new Button("Retry"), "btn-ghost");
        retry.setGraphic(Icons.node(Icons.RECAPTURE, 16, TX));
        Icons.animate(retry, retry.getGraphic());
        retry.setGraphicTextGap(8);
        retry.setAccessibleText("Retry " + label);
        retry.setOnAction(e -> onRetry.run());
        row.getChildren().addAll(Icons.node(Icons.FAILED, 14, BAD), styled(new Label(label), "step-label"), why, retry);
        return row;
    }

    /** Why a launch failed, in the OS's words: "The system cannot find the file specified"
        rather than the whole "Cannot run program ... CreateProcess error=2, ..." line. */
    static String failure(LaunchStep step) {
        if (step.error == null || step.error.isBlank()) return "failed to launch";
        int cut = step.error.lastIndexOf(", ");
        return cut < 0 ? step.error : step.error.substring(cut + 2);
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
        for (String l : labels.subList(0, Math.min(3, labels.size()))) shown.add(appName(l));
        String out = String.join(" · ", shown);
        return labels.size() > 3 ? out + " +" + (labels.size() - 3) : out;
    }

    /** Glyph colours, set from code (hud.css still re-colours them on hover): the 5a tokens. */
    private static final String TX = "#E9E3DF", TX2 = "#A8A29E", TX3 = "#77726E",
            GO = "#FF6500", GOI = "#1A0A00", BAD = "#EB7C73", HUD = "#A8A29E",
            CK = "#2A5688";

    static int countApps(Project projectObj) {
        if (projectObj == null || projectObj.apps == null) return 0;
        return projectObj.apps.values().stream().mapToInt(Set::size).sum();
    }

    /** What one press of Resume can launch, as planResume walks it: Other is never relaunched, a
        browser whose tabs are saved comes back as its tabs, and a terminal that only hosts
        claude opens with it. Apps already open at the time are left alone, and 1l says so. */
    static int launchableApps(Project projectObj) {
        if (projectObj == null || projectObj.apps == null) return 0;
        Set<String> withTabs = new HashSet<>();
        if (projectObj.urlsByBrowser != null) {
            projectObj.urlsByBrowser.forEach((browser, urls) -> {
                String exe = BROWSER_EXE.get(browser.toLowerCase());
                if (exe != null && !urls.isEmpty()) withTabs.add(exe);
            });
        }
        boolean claudeInTerminal = hasApp(projectObj, "claude.exe") && hasApp(projectObj, "WindowsTerminal.exe");
        int n = 0;
        for (String category : CATEGORY_ORDER) {
            for (String app : projectObj.apps.getOrDefault(category, new HashSet<>())) {
                if (withTabs.contains(app.toLowerCase())) continue;
                if (claudeInTerminal && app.equalsIgnoreCase("WindowsTerminal.exe")) continue;
                n++;
            }
        }
        return n;
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
        // one level, add modes only: overwrite is the block itself, so it is not repeated here.
        // The verb is said once, as a heading; the items name only what gets added.
        ContextMenu menu = new ContextMenu();
        MenuItem heading = new MenuItem("ADD WHAT’S MISSING");
        heading.getStyleClass().add("menu-head");
        heading.setDisable(true);   // a label, not a choice: no hover, no click
        menu.getItems().add(heading);
        for (Checkpoint mode : List.of(Checkpoint.ADD_TABS, Checkpoint.ADD_APPS, Checkpoint.ADD_BOTH)) {
            MenuItem item = new MenuItem(switch (mode) {
                case ADD_TABS -> "Tabs";
                case ADD_APPS -> "Apps";
                default -> "Both";
            });
            item.setOnAction(e -> { if (activeProject != null) writeCheckpoint(activeProject, mode); });
            item.disableProperty().bind(btnCheckpoint.disabledProperty());
            menu.getItems().add(item);
        }
        MenuItem undo = new MenuItem("Undo last checkpoint  ·  Ctrl+Z");
        undo.setOnAction(e -> undoCheckpoint());
        menu.getItems().addAll(new SeparatorMenuItem(), undo);
        menu.setOnShowing(e -> undo.setDisable(activeProject == null
                || !undoByProject.containsKey(activeProject.projectName)));
        // consume, or the whole-button click handler fires an overwrite underneath the menu
        btnCheckpointMenu.setOnMouseClicked(e -> { e.consume(); menu.show(btnCheckpointMenu, Side.BOTTOM, 0, 4); });
        // reachable without a mouse: Tab lands on it, Enter / Space / Down opens the menu
        btnCheckpointMenu.setFocusTraversable(true);
        btnCheckpointMenu.setAccessibleRole(AccessibleRole.MENU_BUTTON);
        btnCheckpointMenu.setAccessibleText("Checkpoint options");
        btnCheckpointMenu.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE || e.getCode() == KeyCode.DOWN) {
                e.consume();   // or the block's own Enter handler checkpoints underneath
                menu.show(btnCheckpointMenu, Side.BOTTOM, 0, 4);
            }
        });
        Tooltip.install(btnCheckpointMenu, tip("Add what is missing"));
        btnCheckpoint.setOnContextMenuRequested(e -> { e.consume(); menu.show(btnCheckpoint, e.getScreenX(), e.getScreenY()); });
    }

    private void onCheckpoint() {
        // No "are you sure" for an old checkpoint: whatever this replaces is one Ctrl+Z away
        // (undoCheckpoint). A press while "Saved" is still showing is taken too, not dropped.
        if (activeProject == null) return;
        writeCheckpoint(activeProject, Checkpoint.OVERWRITE);
    }

    /** One level of undo per project: the project as it stood before its last checkpoint,
        as projects.json would hold it. */
    // ponytail: in memory, so a restart forgets it; write it beside projects.json if undo
    // ever needs to survive a relaunch
    private final Map<String, String> undoByProject = new HashMap<>();

    /** The project a capture push asked tabs for, and until when (System.nanoTime) replies
        belong to it rather than to whichever project is on screen by the time they land.
        A null target inside the window drops the replies: the checkpoint was undone. */
    private volatile Project tabTarget;
    private volatile long tabTargetUntil;
    static final long TAB_WINDOW_NANOS = 10_000_000_000L;

    /** Where a POST /tabs lands: the project that asked, while its window is open; after
        that (a manual send from the popup) the active one. Null means drop it. */
    static Project tabsTarget(Project pending, long until, long now, Project active) {
        return now < until ? pending : active;
    }

    private void askForTabs(Project projectObj) {
        tabTarget = projectObj;
        tabTargetUntil = System.nanoTime() + TAB_WINDOW_NANOS;
        wsSessions.forEach(session -> session.send("capture"));
    }

    /** Puts back what a checkpoint changes (apps, tabs, its time) from a gson snapshot. */
    void restore(Project projectObj, String snapshot) {
        Project old = gson.fromJson(snapshot, Project.class);
        projectObj.apps = old.apps;
        projectObj.urlsByBrowser = old.urlsByBrowser;
        projectObj.lastCheckpoint = old.lastCheckpoint;
    }

    private void dropIncomingTabs() {
        tabTarget = null;
        tabTargetUntil = System.nanoTime() + TAB_WINDOW_NANOS;
    }

    private void undoCheckpoint() {
        if (activeProject == null || resuming || checkpointing != null) return;
        String before = undoByProject.remove(activeProject.projectName);
        if (before == null) return;
        Project projectObj = activeProject;
        restore(projectObj, before);
        // a tab reply still on its way belongs to the checkpoint just undone
        dropIncomingTabs();
        try {
            saveProject(projectObj);
        } catch (IOException ex) {
            confirm("Undo not written", projectsFile().getPath(),
                    "The card shows the earlier checkpoint, the disk does not. " + ex.getMessage(),
                    "Retry", () -> { try { saveProject(projectObj); } catch (IOException ignored) { } });
        }
        StackPane row = rowsByProject.get(projectObj.projectName);
        if (row != null) railTip(row, projectObj);
        renderDetail(projectObj);
    }

    private void writeCheckpoint(Project projectObj, Checkpoint mode) {
        // a checkpoint mid-resume would record a half-launched desktop over the project, and a
        // second press while one is capturing would only race it to the disk
        if (resuming || checkpointing != null) return;
        checkpointing = projectObj;
        String before = gson.toJson(projectObj);
        // No bucket is ever dropped here: a browser that answers the capture push overwrites its
        // own bucket in POST /tabs, and one that does not answer (asleep worker, dead socket,
        // closed) keeps what it had. Stale beats gone.
        tabMode = mode;
        boolean tabsComing = mode.tabs() && !wsSessions.isEmpty();
        if (mode.tabs()) askForTabs(projectObj);
        if (projectObj == activeProject) checkpointSub.setText(CHECKPOINTING);
        // the PowerShell capture takes seconds: off the FX thread the HUD keeps drawing and
        // the block can say what it is doing
        Thread worker = new Thread(() -> {
            HashMap<String, HashSet<String>> now = null;
            IOException failed = null;
            try {
                if (mode.apps()) now = segregateApps(captureRunningApps());
            } catch (IOException ex) {
                failed = ex;
            }
            HashMap<String, HashSet<String>> captured = now;
            IOException error = failed;
            Platform.runLater(() -> finishCheckpoint(projectObj, mode, before, tabsComing, captured, error));
        }, "checkpoint");
        worker.setDaemon(true);
        worker.start();
    }

    static final String CHECKPOINTING = "checkpointing…";

    /** The FX-thread half of writeCheckpoint, once the capture is back (or failed). */
    private void finishCheckpoint(Project projectObj, Checkpoint mode, String before, boolean tabsComing,
                                  HashMap<String, HashSet<String>> captured, IOException captureError) {
        checkpointing = null;
        boolean onScreen = projectObj == activeProject;
        try {
            if (captureError != null) throw captureError;
            if (captured != null) {
                if (mode.merge()) mergeApps(projectObj.apps, captured);
                else projectObj.apps = captured;
            }
            projectObj.lastCheckpoint = Instant.now().toString();
            saveProject(projectObj);
        } catch (IOException ex) {
            // the card must not claim "saved" over a write that did not happen: memory goes back
            // to what the disk held, and the tabs already asked for are turned away. A reply that
            // landed during the capture was written by POST /tabs, so the old state is put back.
            restore(projectObj, before);
            dropIncomingTabs();
            try { saveProject(projectObj); } catch (IOException ignored) { }
            if (onScreen) renderDetail(projectObj);
            confirm("Checkpoint not written", projectsFile().getPath(),
                    (captureError != null ? "The open apps could not be listed. " : "")
                            + "Nothing changed on disk. " + ex.getMessage(),
                    "Retry", () -> writeCheckpoint(projectObj, mode));
            return;
        }
        if (replaceUndo(System.nanoTime(), ckBusyUntil, undoByProject.containsKey(projectObj.projectName))) {
            undoByProject.put(projectObj.projectName, before);
        }
        StackPane row = rowsByProject.get(projectObj.projectName);
        if (row != null) railTip(row, projectObj);
        // switched away mid-capture: the save landed, the other card stays as it is
        if (!onScreen) return;

        renderDetail(projectObj);
        // "just now" dropped: the block's column holds ~18 characters, and "Saved" already says it.
        // Tabs reply after this returns, so their count is "tabs…" until POST /tabs fills it in.
        checkpointSaved(countApps(projectObj) + " apps · " + (tabsComing ? "tabs…" : countTabs(projectObj) + " tabs"));
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

    /** "Saved" is on the block until this (System.nanoTime); a save inside it updates the
        line instead of replaying the ring. */
    private long ckBusyUntil;

    /** A save made while the last one's "Saved" is still up (a double click, a quick second
        Ctrl+S) keeps the earlier undo, so Ctrl+Z still goes back to before both. */
    static boolean replaceUndo(long now, long savedUntil, boolean hasUndo) {
        return now >= savedUntil || !hasUndo;
    }

    /** The handoff's checkpoint confirmation, on the button's four layers, once per written save:
          t=0     ring    rwCkRing  400ms standard  - 28px, 2px blue, scale .55 → 2.7, fading out
                  bar     rwCkBar   400ms snap      - the 3px strip rises into the bottom edge
                  idle    rwCkOut   160ms ease-in   - "Checkpoint / overwrite · …" lifts away
                  done    rwCkIn    300ms snap, after 120ms - "Saved / 10 apps · 14 tabs · just now"
          t=2400  done    rwCkOut   200ms ease-in
                  bar     rwCkBarOut 260ms standard
                  idle    rwCkIn    260ms standard, after 140ms
        A save before t=2860 only updates the line. Motion off: the words swap in place, no ring, no bar. */
    private void checkpointSaved(String doneLine) {
        if (!Motion.showing(btnCheckpoint)) return;
        if (System.nanoTime() < ckBusyUntil) {
            ckDoneSub.setText(doneLine);
            return;
        }
        ckBusyUntil = System.nanoTime() + 2_860_000_000L;
        ckDoneSub.setText(doneLine);
        Interpolator std = Motion.STANDARD, in = Motion.IN, snap = Motion.SNAP;
        DoubleProperty ringS = ckRing.scaleXProperty(), ringO = ckRing.opacityProperty(),
                bar = ckBar.translateYProperty(),
                idleO = ckIdle.opacityProperty(), idleY = ckIdle.translateYProperty(),
                doneO = ckDone.opacityProperty(), doneY = ckDone.translateYProperty();
        ckRing.scaleYProperty().bind(ringS);
        Timeline t = Motion.REDUCED
                ? new Timeline(
                        new KeyFrame(Duration.ZERO, kv(idleO, 0), kv(doneO, 1), kv(doneY, 0)),
                        new KeyFrame(Duration.millis(2400), kv(idleO, 0), kv(doneO, 1)),
                        new KeyFrame(Duration.millis(2401), kv(idleO, 1), kv(doneO, 0)))
                : new Timeline(
                        new KeyFrame(Duration.ZERO, kv(ringS, .55), kv(ringO, .85), kv(bar, 3),
                                kv(idleO, 1), kv(idleY, 0), kv(doneO, 0), kv(doneY, 5)),
                        new KeyFrame(Duration.millis(120), kv(doneO, 0), kv(doneY, 5)),
                        new KeyFrame(Duration.millis(160), kv(idleO, 0, in), kv(idleY, -5, in)),
                        new KeyFrame(Duration.millis(400), kv(ringS, 2.7, std), kv(ringO, 0, std), kv(bar, 0, snap)),
                        new KeyFrame(Duration.millis(420), kv(doneO, 1, snap), kv(doneY, 0, snap)),
                        new KeyFrame(Duration.millis(2400), kv(doneO, 1), kv(doneY, 0), kv(bar, 0),
                                kv(idleO, 0), kv(idleY, 5)),
                        new KeyFrame(Duration.millis(2540), kv(idleO, 0), kv(idleY, 5)),
                        new KeyFrame(Duration.millis(2600), kv(doneO, 0, in), kv(doneY, -5, in)),
                        new KeyFrame(Duration.millis(2660), kv(bar, 3, std)),
                        new KeyFrame(Duration.millis(2800), kv(idleO, 1, std), kv(idleY, 0, std)));
        Motion.stop(btnCheckpoint);
        Motion.run(btnCheckpoint, t);
    }

    private static KeyValue kv(DoubleProperty p, double v) { return new KeyValue(p, v); }
    private static KeyValue kv(DoubleProperty p, double v, Interpolator i) { return new KeyValue(p, v, i); }

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
        confirm(title, sub, body, verb, false, onOk);
    }

    /** danger: the verb deletes, so it wears brick, not checkpoint blue. */
    private void confirm(String title, String sub, String body, String verb, boolean danger, Runnable onOk) {
        confirmTitle.setText(title);
        confirmSub.setText(sub);
        confirmBody.setText(body);
        btnConfirmOk.setText(verb);
        btnConfirmOk.getStyleClass().removeAll("btn-checkpoint", "btn-danger");
        btnConfirmOk.getStyleClass().add(danger ? "btn-danger" : "btn-checkpoint");
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
            // get, not remove: a failed write must leave the row findable for Retry
            deleteProject(projectObj, rowsByProject.get(projectObj.projectName));
        } catch (IOException ex) {
            confirm("Project not deleted", projectsFile().getPath(),
                    "Nothing changed on disk. " + ex.getMessage(), "Retry", true, this::deleteActive);
            return;
        }
        rowsByProject.remove(projectObj.projectName);
        refreshCount();
        if (projects.isEmpty()) {
            showEmpty(true);
        } else {
            selectProject(projects.get(0));
        }
    }

    /** Opens (or re-opens) the window in which a missing extension reads as "connecting…". */
    private void startConnecting() {
        connectingUntil = System.nanoTime() + (long) CONNECT_GRACE.toMillis() * 1_000_000L;
        if (connectingEnds != null) connectingEnds.stop();
        connectingEnds = new PauseTransition(CONNECT_GRACE);
        connectingEnds.setOnFinished(e -> refreshStatusDot());
        connectingEnds.play();
    }

    /** The title bar's words: who is connected, "connecting…", or nobody. */
    static String statusLine(List<String> browsers, boolean connecting) {
        if (!browsers.isEmpty()) {
            return browsers.size() == 1 ? browserLabel(browsers.get(0)) + " connected"
                    : browsers.size() + " browsers connected";
        }
        return connecting ? "connecting\u2026" : "no browser connected";
    }

    /** Title-bar dot: sage while any extension holds the socket, a turning ring while one is
        expected, grey otherwise. */
    private void refreshStatusDot() {
        if (!isHud()) return;
        // one name per browser: every window of a browser shares one extension worker, so a
        // reconnect racing its own retry would otherwise list the same brand twice
        List<String> browsers = wsSessions.stream()
                .map(HelloController::wsBrowser).distinct().sorted().toList();
        Platform.runLater(() -> {
            boolean live = !browsers.isEmpty();
            boolean connecting = !live && System.nanoTime() < connectingUntil;
            statusDot.getStyleClass().setAll(live ? "dot-live" : "dot-idle");
            statusDot.setVisible(!connecting);
            statusDot.setManaged(!connecting);
            statusSpin.setVisible(connecting);
            statusSpin.setManaged(connecting);
            // motion off: the arc holds still, which still reads as "not yet"
            if (connecting && !Motion.REDUCED) spin.play(); else spin.pause();
            statusLabel.setText(statusLine(browsers, connecting));
            statusLabel.getStyleClass().setAll(live ? "port-live" : "port");
            statusTip.setText(live ? String.join(", ", browsers) + " \u00b7 tabs come and go through the extension"
                    : connecting ? "waiting for the coldstart extension \u00b7 a browser can take a minute to reconnect"
                    : "install the coldstart extension to checkpoint tabs");
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
    /** Writes the project to projects.json (replacing any entry with the same name). Both the
        in-memory list and the on-disk list are updated. It does not make the project active:
        tabs can now land on a project that is not on screen. */
    public void saveProject(Project projectObj) throws IOException {

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
        if (tabTarget != null && tabTarget.projectName.equals(projectObj.projectName)) dropIncomingTabs();
        undoByProject.remove(projectObj.projectName);
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
    //                       claude (agent view of the project folder), VS Code (project folder), wt

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
    // every running claude writes ~/.claude/sessions/<pid>.json with its cwd. a live one for
    // this folder means its window is already up, so opening another would only duplicate it.
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
        return exe == null ? null : appPath(exe);
    }

    /** Is "name.exe" in one of the PATH directories - the first place `start name` looks.
        NOFOLLOW_LINKS: Store apps live in WindowsApps as execution-alias reparse points, which
        File.isFile and a following Files.exists both report as missing. */
    static boolean onPath(String exe, String path) {
        if (path == null) return false;
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) continue;
            try {
                if (Files.exists(Path.of(dir.trim().replace("\"", ""), exe), LinkOption.NOFOLLOW_LINKS)) return true;
            } catch (java.nio.file.InvalidPathException badEntry) {
                // a malformed PATH entry is skipped, as cmd skips it
            }
        }
        return false;
    }

    /** The exe's App Paths registration (HKCU, then HKLM) - where `start` looks after PATH. */
    static String appPath(String exe) throws IOException {
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
    static List<String> claudeCommand(String folder, boolean inTerminal) {
        List<String> command = new ArrayList<>(List.of("cmd.exe", "/c", "start"));
        if (inTerminal) {
            command.add("wt");
            if (folder != null) command.addAll(List.of("-d", folder));
        }
        // the agent view lists every conversation for the project, not just the latest one;
        // --cwd scopes it to sessions started under this folder - with no folder it shows them all
        command.addAll(List.of("claude", "agents"));
        if (folder != null) command.addAll(List.of("--cwd", folder));
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
        final List<String> log = new CopyOnWriteArrayList<>();
        volatile ResumePlan plan = new ResumePlan(List.of(), List.of());
        long tookMs;
        /** Stop was pressed with steps still queued: 1l says STOPPED and offers the rest. */
        volatile boolean stopped;
        /** The capture before planning failed, so nothing was planned or launched. */
        volatile String error;

        ResumeRun(Project project) { this.project = project; }

        int total()     { return plan.steps().size(); }
        long started()  { return plan.steps().stream().filter(s -> s.state != LaunchStep.State.QUEUED).count(); }
        long done()     { return plan.steps().stream().filter(s -> s.state == LaunchStep.State.DONE).count(); }
        long failed()   { return plan.steps().stream().filter(s -> s.state == LaunchStep.State.FAILED).count(); }
        long queued()   { return plan.steps().stream().filter(s -> s.state == LaunchStep.State.QUEUED).count(); }
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
                // a browser handed its tabs above is coming back, not "already open"
                if (handledBrowsers.contains(app.toLowerCase())) {
                    log.add("skipping " + app + ": opens with its tabs");
                    continue;
                }
                if (!shouldLaunch(app, running, handledBrowsers)) {
                    skipped.add(app);
                    log.add("skipping (already open or handled): " + app);
                    continue;
                }
                String appName = app.replace(".exe", "");

                if (appName.equalsIgnoreCase("claude")) {
                    if (folder != null && claudeRunningIn(folder)) {
                        skipped.add(app);
                        log.add("skipping claude: already running in " + folder);
                        continue;
                    }
                    List<String> cmd = claudeCommand(folder, claudeInTerminal);
                    // open claude's agent view, in the project folder when there is one
                    steps.add(new LaunchStep(appName(app), String.join(" ", cmd.subList(2, cmd.size())), false, () ->
                            new ProcessBuilder(cmd).directory(folder == null ? null : new File(folder)).start()));

                } else if (appName.equalsIgnoreCase("Code")) {
                    // open VS Code in the project folder; with none it reopens its last
                    // workspace rather than silently not coming back
                    List<String> cmd = new ArrayList<>(List.of("cmd.exe", "/c", "code"));
                    if (folder != null) cmd.add(folder);
                    steps.add(new LaunchStep(appName(app), folder == null ? "code" : "code " + folder, false, () ->
                            new ProcessBuilder(cmd).start()));

                } else if (appName.equalsIgnoreCase("WindowsTerminal")) {
                    if (claudeInTerminal) {
                        log.add("skipping wt: claude launches inside it");
                        continue;
                    }
                    // WindowsTerminal.exe is the process name, but "wt" is the actual
                    // launch command - "start WindowsTerminal" can't resolve it
                    steps.add(new LaunchStep(appName(app), "start wt", false, () ->
                            new ProcessBuilder("cmd.exe", "/c", "start", "wt").start()));

                } else {
                    // no folder needed: this used to sit under the folderPath guard too, so a
                    // project without a folder relaunched nothing at all
                    // `start` reports nothing back when windows cannot find the exe - it shows its
                    // own dialog and the step would read as launched - so look first, the way
                    // start does: PATH, then the App Paths keys
                    steps.add(new LaunchStep(appName(app), "start " + appName, false, () -> {
                        if (!onPath(app, System.getenv("PATH")) && appPath(app) == null) {
                            throw new IOException("cannot find " + app);
                        }
                        new ProcessBuilder("cmd.exe", "/c", "start", appName).start();
                    }));
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
                // the last one gone is usually a worker suspending or a browser restarting:
                // it gets the same minute to come back before the title bar says nobody is here
                if (wsSessions.isEmpty() && isHud()) Platform.runLater(this::startConnecting);
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
            // the project whose checkpoint asked, even if another one is on screen by now
            Project target = tabsTarget(tabTarget, tabTargetUntil, System.nanoTime(), activeProject);
            if (target != null && incoming != null) {
                // browser is absent when getBrowserFromBrands() recognises no brand
                String key = browser != null ? browser.toString() : DEFAULT_BROWSER;
                List<String> pages = incoming.stream().filter(HelloController::isPageUrl).toList();
                target.urlsByBrowser.put(key, tabMode.merge()
                        ? mergeUrls(target.urlsByBrowser.getOrDefault(key, new ArrayList<>()), pages)
                        : new ArrayList<>(pages));
                saveProject(target);
                // tabs arrive after the checkpoint button has already returned, so the
                // card has to be redrawn off the javalin thread - and only if it is this card
                if (isHud()) {
                    Platform.runLater(() -> {
                        if (target != activeProject) return;
                        renderDetail(target);
                        // the "Saved" line said "tabs…" until now
                        if (System.nanoTime() < ckBusyUntil) {
                            ckDoneSub.setText(countApps(target) + " apps · " + countTabs(target) + " tabs");
                        }
                    });
                }
            }
        });
    }

}
