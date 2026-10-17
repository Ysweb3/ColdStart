package com.example.resumeone;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.javalin.Javalin;
import io.javalin.http.ForbiddenResponse;
import io.javalin.websocket.WsContext;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane; // explicit: java.awt.* also exports ScrollPane
import javafx.scene.control.TextField;
import javafx.scene.control.TextArea; // explicit: java.awt.* also exports TextArea
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.event.ActionEvent;
import javafx.scene.AccessibleRole;
import javafx.scene.Cursor;
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
import javafx.scene.input.MouseEvent;
import javafx.css.PseudoClass;
import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.PauseTransition;
import javafx.scene.transform.Rotate;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.net.URISyntaxException;
import java.util.*;
import java.util.List; // explicit: java.awt.* also exports List
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
 *       %APPDATA%\Coldstart\projects.json (see {@link #projectsFile()}, {@link #saveProject}).</li>
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
    /** An {"open":[urls]} waiting on its {"opened":n}: how many tabs that browser really opened. */
    private final Map<WsContext, CompletableFuture<Integer>> openReplies = new ConcurrentHashMap<>();
    /** The extension answers in milliseconds; one from before the reply never does. */
    static final long OPEN_REPLY_MS = 2000;
    /** Tabs a browser resume just started still owes, by bucket key: its extension takes them
        the moment it connects and opens them a few at a time, rather than the command line
        loading every one at once. */
    private final Map<String, List<String>> pendingOpens = new ConcurrentHashMap<>();
    /** How long that browser has to bring its extension up before the rest are launched the
        old way, all in one go - it may not have the extension (another profile, firefox). */
    static final long PENDING_OPEN_MS = 15_000;
    /** An awake extension redials every 3s, so by this long after the server came up it has
        connected - a resume started sooner waits for it, briefly. */
    static final long EXT_DIAL_MS = 3_500;
    /** A checkpoint's tabs still owed, by bucket key, by an open browser whose extension was
        asleep when it was taken: asked for the moment it connects, until `until`. Past that
        the bucket keeps the last checkpoint's tabs, and its group says so. */
    record Owed(Project project, Checkpoint mode, long until) {}
    private final Map<String, Owed> owedTabs = new ConcurrentHashMap<>();
    /** An asleep extension wakes on its 30s alarm, then dials. */
    static final long OWED_NANOS = 35_000_000_000L;
    /** A quit is held on the "waiting for tabs" scrim; it goes through once nothing is owed. */
    private boolean exitWaiting;
    private volatile long serverStartedNanos;

    @FXML
    public VBox projectList;

    // ── HUD v2 (hud-view.fxml). All null when the old hello-view.fxml is loaded,
    //    so every use is guarded by isHud().
    @FXML public VBox detailPane, categoryRows, tabsList;
    @FXML public StackPane emptyPane;
    @FXML public Pane emptyGrid, emptyBoard;
    @FXML public VBox emptyHead, step01, step02, step03;
    @FXML public Label emptyFootLeft, emptyFootRight;
    @FXML public Button btnReplayEntrance;   // TEMP: remove with its fxml before shipping
    @FXML public VBox deletePane, newProjectPane, confirmPane;
    @FXML public Label confirmTitle, confirmSub, confirmBody;
    // the checkpoint block's swappable parts, and the close offer under the card (handoff 1d)
    @FXML public StackPane ckGlyphBox, ckOffer, offerGlyphBox, btnOfferDismiss;
    @FXML public SVGPath ckBookmark;
    @FXML public Label ckWord, ckHint, ckDoneWord, offerSub;
    @FXML public Button btnOfferClose;
    @FXML public Region offerBar;
    private LaunchStep lastLaunching;   // the row whose beat already played
    @FXML public Button btnConfirmCancel, btnConfirmOk;
    // 1e / 1l (resume in progress, resume done) - see the resume section below
    @FXML public VBox resumingPane, restoredPane, resumingRows, restoredRows;
    @FXML public HBox titleState, restoredMeter, countdownTrack;
    @FXML public StackPane resumingMeter;
    @FXML public Region titleStateDot, resumingFill, restoredFill, restoredFailFill, countdownFill;
    @FXML public Label titleStateVerb, titleStateName, resumingCount, resumingPct, resumingName,
            resumingSkipped, restoredCount, restoredTook, restoredSpec, restoredNote, restoredName,
            restoredWord, restoredNext;
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
    @FXML public VBox settingsRows;
    /** Settings > Window: pin coldstart on top while a resume runs. On unless unticked. */
    @FXML public CheckBox pinToggle;
    private static final String PIN_KEY = "pinWhileResuming";
    // TODO: placeholder - swap in the real store listing once the extension is published
    static final String EXTENSION_URL = "https://example.com/coldstart-extension";
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
    @FXML public StackPane resumeGlyphBox, ckDoneGlyphBox, stepCkGlyph, stepResumeGlyph, trailInto02, trailInto03,
            deleteFolderGlyph, npFolderGlyph, noteGlyphBox;
    /** The NEXT line under the buttons: the project's "where was I" note. */
    @FXML public TextArea noteField;
    @FXML public HBox noteStrip;
    @FXML public Button btnAddNote;
    @FXML public StackPane btnNoteClose;
    /** The project the note field was filled from. Typing edits this one, not whichever is
        active when focus leaves the field: a click on another row switches project first. */
    private Project noteOwner;
    private boolean noteDirty;
    @FXML public Region statusDot;
    @FXML public Label projectNameLabel, projectPathLabel, checkpointSub, ckDoneSub, statusLabel, resumeCounts;
    /** The full folder path, one hover away from the "…\\atlas-api" the header shows. */
    private Tooltip pathTip;
    private Label pathTipPath;

    /** "connecting…": a 10px ring whose arc turns in place of the dot while no extension holds
        the socket yet but one is expected - at startup, and after the last one leaves. */
    private Pane statusSpin;
    private Timeline spin;
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
        // before the projects load: their Apps/Other rows are sorted by these lists
        loadUserApps();

        for (Project project : getProjectsJson()) {
            createProjectUI(project, false);
        }
        segregateApps(captureRunningApps());
        startServer();

        if (!isHud()) return;
        clickable(btnResume, "Resume", this::onResume);
        clickable(btnCheckpoint, "Checkpoint", this::onCheckpoint);
        installCheckpointMenu();
        installNote();
        notch(btnResume, 14);   // Checkpoint's notch too: the two verbs share one size
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
        pinToggle.setSelected(PREFS.getBoolean(PIN_KEY, true));
        // read by showBase the next time a resume starts
        pinToggle.selectedProperty().addListener((o, was, on) -> PREFS.putBoolean(PIN_KEY, on));
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
                // a shown window focuses its first text field, which lit the NEXT strip on every
                // launch as if mid-typing; nothing is focused until the user clicks into it
                // a click on a pane (the header, the gaps) takes no focus, so the note field kept
                // it and never saved or folded; any press outside the strip lets go of it
                scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
                    if (noteField.isFocused() && !(e.getTarget() instanceof Node n && inside(n, noteStrip))) {
                        detailPane.requestFocus();
                    }
                });
                scene.windowProperty().addListener((o2, none, window) -> {
                    if (window != null) window.addEventHandler(javafx.stage.WindowEvent.WINDOW_SHOWN, e -> {
                        detailPane.requestFocus();
                        // a first launch opens on the empty state before anything can animate
                        if (emptyPane.isVisible() && !emptyEntered) playEmptyEntranceWhenOnScreen();
                    });
                });
            }
        });
        btnDeleteCancel.setOnAction(e -> backToProjects());
        btnDeleteOk.setOnAction(e -> deleteActive());
        btnSettingsBack.setOnAction(e -> backToProjects());
        btnNpCancel.setOnAction(e -> backToProjects());
        btnConfirmCancel.setOnAction(e -> showOverlay(null));
        installOffer();
        btnNpBrowse.setOnAction(e -> {
            String chosen = openFolder(btnNpBrowse, NO_FOLDER.equals(npFolder.getText()) ? null : npFolder.getText());
            if (chosen != null) npFolder.setText(chosen);
        });
        // the name is the project's key: a second "atlas-api" would replace the first one's
        // checkpoint, so a taken name cannot be created and the hint says why
        btnNpCreate.disableProperty().bind(Bindings.createBooleanBinding(
                () -> npName.getText().trim().isEmpty() || nameTaken(projects, npName.getText()), npName.textProperty()));
        // both hints take no room until they have something to say
        for (Label hint : List.of(npHint, npCaptureHint)) hint.managedProperty().bind(hint.visibleProperty());
        npHint.pseudoClassStateChanged(PC_FAILED, true);
        npName.textProperty().addListener((o, was, now) -> {
            npHint.setText(now.trim() + " already exists");
            npHint.setVisible(nameTaken(projects, now));
        });
        btnNpCreate.setOnAction(e -> createFromDialog());
        npName.setOnAction(e -> { if (!btnNpCreate.isDisabled()) createFromDialog(); });   // Enter creates
        npName.setAccessibleText("Project name");
        btnNpBrowse.setAccessibleText("Browse for the project folder");
        btnNpCreate.textProperty().bind(Bindings.when(npCapture.selectedProperty())
                .then("Create + checkpoint").otherwise("Create"));
        // blue is only ever Checkpoint: a create that will not checkpoint is the neutral bone
        // action, and drops the bookmark glyph with the blue
        btnNpCreate.contentDisplayProperty().bind(Bindings.when(npCapture.selectedProperty())
                .then(ContentDisplay.LEFT).otherwise(ContentDisplay.TEXT_ONLY));
        npCapture.selectedProperty().addListener((o, was, on) -> styleAs(btnNpCreate, on ? "btn-checkpoint" : "btn-primary"));
        btnResumeStop.setGraphic(Icons.node(Icons.CLOSE, 16, TX2));
        Icons.animate(btnResumeStop, btnResumeStop.getGraphic());
        btnResumeStop.setOnAction(e -> stopRequested = true);
        btnStayHere.setOnAction(e -> stopCountdown());
        btnResumeRest.setGraphic(Icons.node(Icons.RESUME, 16, GO));
        Icons.animate(btnResumeRest, btnResumeRest.getGraphic());
        btnResumeRest.setOnAction(e -> { if (shownRun != null) resumeRest(shownRun); });
        btnOpenProject.setGraphic(Icons.node(Icons.CHEVRON, 16, TX));
        btnOpenProject.setOnAction(e -> { stopCountdown(); backToProjects(); });
        // meters are fractions bound to their track, so they are right before first layout
        resumingFill.prefWidthProperty().bind(resumingMeter.widthProperty().multiply(resumingFrac));
        restoredFill.prefWidthProperty().bind(restoredMeter.widthProperty().multiply(restoredFrac));
        restoredFailFill.prefWidthProperty().bind(restoredMeter.widthProperty().multiply(restoredFailFrac));
        countdownFill.prefWidthProperty().bind(countdownTrack.widthProperty().multiply(countdownFrac));
        // the action on top in bold, the path under it in the card's own path style
        Label editLine = new Label("Click to edit");
        editLine.getStyleClass().add("tip-action");
        pathTipPath = new Label();
        pathTipPath.getStyleClass().add("project-path");
        pathTip = tip("");
        pathTip.setGraphic(new VBox(4, editLine, pathTipPath));
        pathTip.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        Tooltip.install(projectPathLabel, pathTip);
        clickable(projectPathLabel, "Edit project folder", this::editFolder);
        projectPathLabel.setCursor(Cursor.HAND);
        // A fixed 12x12 box with the ring at its exact centre, and only the arc turning, about
        // that same point. Rotating the whole node pivoted on bounds JavaFX recomputed, so the
        // ring wobbled as it spun.
        Circle track = new Circle(6, 6, 5, Color.TRANSPARENT);
        track.setStroke(Color.web("#464A52"));
        track.setStrokeWidth(1.6);
        Arc arc = new Arc(6, 6, 5, 5, 90, -110);
        arc.setType(ArcType.OPEN);
        arc.setFill(null);
        arc.setStroke(Color.web(TX2));
        arc.setStrokeWidth(1.6);
        arc.setStrokeLineCap(StrokeLineCap.ROUND);
        Rotate turn = new Rotate(0, 6, 6);
        arc.getTransforms().add(turn);
        statusSpin = new Pane(track, arc);
        statusSpin.setMinSize(12, 12);
        statusSpin.setPrefSize(12, 12);
        statusSpin.setMaxSize(12, 12);
        statusBox.getChildren().add(0, statusSpin);
        spin = new Timeline(new KeyFrame(Duration.ZERO, new KeyValue(turn.angleProperty(), 0)),
                new KeyFrame(Duration.millis(900), new KeyValue(turn.angleProperty(), 360, Interpolator.LINEAR)));
        spin.setCycleCount(Animation.INDEFINITE);
        startConnecting();
        statusTip = new Tooltip();
        statusTip.setShowDelay(Duration.millis(150));
        Tooltip.install(statusBox, statusTip);
        Tooltip.install(btnSettings, tip("Settings"));
        Tooltip.install(btnDelete, tip("Delete project"));
        refreshStatusDot();
        refreshCount();
        // ages are relative, so they go stale on their own: without this tick a row read "now"
        // for hours, and the rail and the Checkpoint block showed one checkpoint as 15h and 18h
        Timeline ages = new Timeline(new KeyFrame(Duration.minutes(1), e -> {
            refreshCount();
            renderCkPanel();
        }));
        ages.setCycleCount(Animation.INDEFINITE);
        ages.play();
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

    /** %APPDATA%\Coldstart\projects.json - the single file every project is stored in, and the
        only place the app writes. Projects from before the rename were copied over from
        %APPDATA%\ResumeWork by hand; nothing reads that folder any more. */
    @FXML
    static File projectsFile() {
        File dir = new File(System.getenv("APPDATA"), "Coldstart");
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
                recategorizeOther(p);
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
        boolean noTabs = wsSessions.isEmpty();
        npCaptureHint.visibleProperty().bind(Bindings.createBooleanBinding(
                () -> noTabs && npCapture.isSelected(), npCapture.selectedProperty()));
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
            projectFolder = openFolder(selectFolder, projectFolder);
            selectFolder.setText(projectFolder == null ? "Select Folder" : projectFolder);
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
                if (projectObj.lastCheckpoint != null) {
                    projectObj.apps = segregateApps(captureRunningApps());
                    AppFolders.Capture dirs = captureFolders(projectObj.apps);
                    if (dirs != null) {
                        projectObj.folders = dirs.folders();
                        projectObj.exePaths = dirs.exePaths();
                        projectObj.windows = dirs.windows();
                    }
                }
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
            Pane g = Icons.node(e.getValue(), 16, TX2);
            e.getKey().getChildren().add(g);
            Icons.animate(e.getKey(), g);
        }
        Tooltip.install(btnNewProjectRail, tip("New project"));
        graphic(btnNewProjectWide, Icons.NEW, TX);
        graphic(btnNewProjectEmpty, Icons.NEW, TX);
        graphic(btnNpBrowse, Icons.BROWSE, TX2);
        graphic(btnNpCreate, Icons.BOOKMARK_PLUS, TX);
        for (CheckBox cb : List.of(pinToggle, motionToggle, sidebarToggle, npCapture)) iconCheck(cb);

        Pane play = Icons.node(Icons.RESUME, 30, GOI, 2.4);
        resumeGlyphBox.getChildren().add(play);
        Icons.animate(btnResume, play);
        // the checkpoint block keeps its earlier slate; its "Saved" check matches that sub-line
        ckDoneGlyphBox.getChildren().add(Icons.node(Icons.CHECK, 22, CKT, 2.2));
        // the empty state's steps wear the glyphs of the blocks they stand for, in the blocks'
        // colours: Checkpoint's bone bookmark, RESUME's play in ink on orange at its 2.4 stroke
        stepCkGlyph.getChildren().add(Icons.node(Icons.CHECKPOINT_ADD, 16, TX));
        stepResumeGlyph.getChildren().add(Icons.node(Icons.RESUME, 16, GOI, 2.4));
        // where each path enters its step, in that verb's colour
        trailInto02.getChildren().add(Icons.node(Icons.CHEVRON, 14, CKT));
        trailInto03.getChildren().add(Icons.node(Icons.CHEVRON, 14, GO));
        // TEMP: replay the entrance on demand while it is being tuned
        graphic(btnReplayEntrance, Icons.RECAPTURE, TX2);
        btnReplayEntrance.setOnAction(e -> playEmptyEntrance());
        emptyPane.layoutBoundsProperty().addListener((o, was, b) -> drawEmptyGrid());
        emptyBoard.boundsInParentProperty().addListener((o, was, b) -> drawEmptyGrid());
        deleteFolderGlyph.getChildren().add(Icons.node(Icons.OTHER, 16, TX3));
        npFolderGlyph.getChildren().add(Icons.node(Icons.OTHER, 16, TX3));
    }

    /** The empty state's 40px grid, every line through a multiple of 40 from the board's corner,
        so the steps keep sitting on cells wherever the window puts the board. +.5 keeps each
        1px line on one pixel row. */
    private void drawEmptyGrid() {
        double w = emptyPane.getWidth(), h = emptyPane.getHeight();
        List<javafx.scene.shape.Line> lines = new ArrayList<>();
        for (double x = emptyBoard.getLayoutX() % GRID; x <= w; x += GRID) lines.add(gridLine(x + .5, 0, x + .5, h));
        for (double y = emptyBoard.getLayoutY() % GRID; y <= h; y += GRID) lines.add(gridLine(0, y + .5, w, y + .5));
        emptyGrid.getChildren().setAll(lines);
    }

    private static final double GRID = 40;

    private static javafx.scene.shape.Line gridLine(double x1, double y1, double x2, double y2) {
        javafx.scene.shape.Line l = new javafx.scene.shape.Line(x1, y1, x2, y2);
        l.getStyleClass().add("grid-line");
        return l;
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
        // a handler, not an accelerator: accelerators run before the focused control, so Ctrl+Z
        // in the note field would undo the checkpoint instead of the typing. The field consumes
        // its own Ctrl+Z, so this only hears it from outside a text field.
        KeyCombination undo = new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN);
        scene.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (!undo.match(e) || overlayOpen() || !detailPane.isVisible()) return;
            undoCheckpoint();
            e.consume();
        });
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.B, KeyCombination.SHORTCUT_DOWN),
                () -> { if (!overlayOpen()) setRailWide(!rail.getStyleClass().contains("rail-wide")); });
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            // the close offer takes Enter (close) and Esc (keep) while it is up
            if (offerFor != null && !overlayOpen() && (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.ESCAPE)) {
                if (e.getCode() == KeyCode.ENTER) acceptOffer(); else dismissOffer();
                e.consume();
                return;
            }
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
        // what the user sorted out of Other; clicking the row undoes it all
        List<HBox> userRows = new ArrayList<>();
        for (Set<String> list : List.of(relaunched, hidden)) {
            if (list.isEmpty()) continue;
            boolean keep = list == relaunched;
            List<String> sorted = list.stream().sorted().toList();
            HBox row = statRow(Icons.APP_WINDOW, keep ? "Relaunched from Other" : "Hidden apps",
                    names(sorted) + (keep ? " · click to stop" : " · click to show again"), String.join("\n", sorted));
            row.getChildren().add(Icons.node(Icons.CHEVRON, 14, TX3));
            row.getStyleClass().add("stat-link");
            clickable(row, keep ? "Stop relaunching these" : "Capture these again", () -> {
                list.clear();
                storeUserApps();
                projects.forEach(HelloController::recategorizeOther);
                renderSettingsRows();
            });
            userRows.add(row);
        }
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
        HBox extensionRow = statRow(Icons.BROWSER, "Get the browser extension", "Chrome, Edge, Brave, Opera", null);
        extensionRow.getChildren().add(Icons.node(Icons.CHEVRON, 14, TX3));
        extensionRow.getStyleClass().add("stat-link");
        clickable(extensionRow, "Get the browser extension", () -> {
            try {
                Desktop.getDesktop().browse(new URI(EXTENSION_URL));
            } catch (IOException | URISyntaxException ex) {
                System.err.println("open extension page: " + ex.getMessage());
            }
        });
        settingsRows.getChildren().setAll(
                statRow(Icons.BROWSER, "Browser extensions",
                        browsers.isEmpty() ? (System.nanoTime() < connectingUntil ? "connecting\u2026"
                                                                           : "none connected \u00b7 install the coldstart extension")
                                           : String.join(", ", browsers.stream().map(HelloController::browserLabel).toList())
                                             + " connected", null),
                extensionRow,
                statRow(Icons.APP_WINDOW, "Ignored processes", names(ignored), String.join("\n", ignored)),
                storageRow);
        settingsRows.getChildren().addAll(userRows);
    }

    // ponytail: 3s is a guess past the slowest window measured (a cold launch, ~1.2s); an app
    // that takes longer still opens on top. Waiting for each launched window would close that.
    static final Duration PIN_GRACE = Duration.seconds(3);
    private PauseTransition pinHold;

    /** detailPane / emptyPane / settingsPane are the base views: exactly one shows. */
    private void showBase(Node target) {
        boolean arriving = !target.isVisible();
        for (Node pane : List.of(detailPane, emptyPane, settingsPane, resumingPane, restoredPane)) {
            pane.setVisible(pane == target);
            pane.setManaged(pane == target);
        }
        if (arriving && Motion.showing(target)) {
            if (target == emptyPane && !emptyEntered) playEmptyEntrance();
            else panelIn(target);
        }
        // Pinned on top while Resuming shows, so launched apps cannot bury progress and Stop.
        // Derived here, from the view, so no path out of a resume can leave it pinned. The pin
        // outlives the run by PIN_GRACE: the last app's window can show up after its launch
        // returned (Explorer ~0.6s, a cold Notepad ~0.9s), and one that appears unpinned lands on top.
        // Settings > Window can turn the pin off; unpinning on every other view still runs.
        if (pinHold != null) pinHold.stop();
        if (target.getScene() != null && target.getScene().getWindow() instanceof Stage st) {
            boolean pin = pinToggle.isSelected() && (target == resumingPane || target == restoredPane);
            st.setAlwaysOnTop(pin);
            if (pin && target == restoredPane) {
                pinHold = new PauseTransition(PIN_GRACE);
                pinHold.setOnFinished(e -> st.setAlwaysOnTop(false));
                pinHold.play();
            }
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
        exitWaiting = false;   // the quit scrim went, whichever way
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

    /** Once per launch, the first time the empty state is seen; after that it arrives as any
        panel does. */
    private boolean emptyEntered;

    /** The empty state builds in the order it is used. The grid comes up under the heading, 01
        lands, New project lights up and the entrance waits on it (HOLD). Then checkpoint's blue
        runs cell by cell along the path into 02, which arrives from the side the path enters;
        resume's orange runs on into 03 the same way, a little quicker. Settled by ~2s, clickable
        from the first frame. Reduced motion: everything takes the short fade, nothing travels or
        lights up. */
    private void playEmptyEntrance() {
        emptyEntered = true;
        List<Node> parts = emptyParts();
        if (Motion.REDUCED) {
            parts.forEach(n -> Motion.enter(n, 0, 0, 1, Motion.FAST, Motion.STANDARD));
            return;
        }
        parts.forEach(n -> n.setOpacity(0));
        // a raised grey button until it lights up; igniteNewProject hands it back to CSS's bone
        igniteStep(0);
        Timeline t = new Timeline(
                at(0, () -> Motion.enter(emptyGrid, 0, 0, 1, ENTER_GRID, Motion.IN_OUT)),
                at(0, () -> Motion.enter(emptyHead, 0, 6, 1, ENTER_HEAD, Motion.IN_OUT)),
                at(80, () -> Motion.enter(step01, 0, 8, 1, ENTER_STEP, Motion.IN_OUT)),
                at(200, () -> {
                    Motion.enter(emptyFootLeft, 0, 0, 1, ENTER_FOOT, Motion.IN_OUT);
                    Motion.enter(emptyFootRight, 0, 0, 1, ENTER_FOOT, Motion.IN_OUT);
                }),
                at(IGNITE_AT, this::igniteNewProject));
        // the rest waits on the lit button: nothing moves for HOLD, then the blue sets off from it
        int go = IGNITE_AT + (int) IGNITE.toMillis() + (int) HOLD.toMillis();
        t.getKeyFrames().addAll(
                at(go + 140, () -> Motion.enter(step02, -8, 0, 1, ENTER_STEP, Motion.IN_OUT)),
                at(go + 480, () -> Motion.enter(step03, -8, 0, 1, ENTER_STEP, Motion.IN_OUT)));
        flow(t, trail("trail-ck"), go, 45);
        flow(t, trail("trail-go"), go + 340, 35);
        t.play();
    }

    /** When New project starts to light, and how long the entrance then waits on it, lit and
        alone, before going on: the one pause, so the eye lands on the thing to press. */
    private static final int IGNITE_AT = 300;
    private static final Duration HOLD = Duration.millis(500);

    /** The entrance's durations, in one place to tune. Every move eases in and out
        (Motion.IN_OUT), so each runs a little longer than its ease-out equivalent would: the
        gentle start would otherwise read as a late one. */
    private static final Duration ENTER_GRID = Duration.millis(600), ENTER_HEAD = Duration.millis(300),
            ENTER_STEP = Duration.millis(360), ENTER_CELL = Duration.millis(200),
            ENTER_FOOT = Duration.millis(400), IGNITE = Duration.millis(420);

    /** Everything the entrance brings in; the TEMP replay button stays put. */
    private List<Node> emptyParts() {
        List<Node> parts = new ArrayList<>(List.of(emptyGrid, emptyHead, step01, step02, step03,
                emptyFootLeft, emptyFootRight));
        parts.addAll(trail("trail-ck"));
        parts.addAll(trail("trail-go"));
        return parts;
    }

    /** WINDOW_SHOWN fires inside show(), before the first frame is painted, and the frames
        right after it stutter (measured: the first at ~20ms, then two ~45ms gaps, steady only
        from ~120ms), so an entrance started there spent its first beats unseen. Instead the
        parts hide at once, and the entrance starts when the window is up, not minimised, and four
        frames in a row have come within 25ms of each other. If frames never settle it starts
        after 800ms anyway; a minimised window waits until it is restored. */
    private void playEmptyEntranceWhenOnScreen() {
        emptyEntered = true;
        if (!Motion.REDUCED) {
            emptyParts().forEach(n -> n.setOpacity(0));
            igniteStep(0);
        }
        Stage st = (Stage) emptyPane.getScene().getWindow();
        long since = System.nanoTime();
        new javafx.animation.AnimationTimer() {
            long last;
            int steady;
            @Override public void handle(long now) {
                boolean up = st.isShowing() && !st.isIconified();
                steady = up && last != 0 && now - last < 25_000_000L ? steady + 1 : 0;
                last = now;
                if (up && (steady >= 4 || now - since > 800_000_000L)) {
                    stop();
                    playEmptyEntrance();
                }
            }
        }.start();
    }

    private List<Node> trail(String styleClass) {
        // fxml order is path order: out of one step, into the next
        return emptyBoard.getChildren().stream().filter(n -> n.getStyleClass().contains(styleClass)).toList();
    }

    /** A path lights cell by cell, each cell pushed out of the one before it, so the colour
        travels the way the path runs; the first comes down out of its step. */
    private static void flow(Timeline t, List<Node> cells, int startMs, int gapMs) {
        for (int i = 0; i < cells.size(); i++) {
            Node c = cells.get(i);
            double dx = i == 0 ? 0 : -6 * Math.signum(c.getLayoutX() - cells.get(i - 1).getLayoutX());
            double dy = i == 0 ? -6 : -6 * Math.signum(c.getLayoutY() - cells.get(i - 1).getLayoutY());
            t.getKeyFrames().add(at(startMs + gapMs * i, () -> Motion.enter(c, dx, dy, 1, ENTER_CELL, Motion.IN_OUT)));
        }
    }

    private static KeyFrame at(int ms, Runnable action) {
        return new KeyFrame(Duration.millis(ms), e -> action.run());
    }

    /** New project lights up: a raised grey button (bone words) fills to the bone primary (ink
        words) under one beat of bloom, the launch bloom's shape in bone. Then the inline colours
        and the bloom go, and CSS owns the button again (hover included). */
    private void igniteNewProject() {
        Button b = btnNewProjectEmpty;
        DoubleProperty lit = new SimpleDoubleProperty();
        lit.addListener((o, was, v) -> igniteStep(v.doubleValue()));
        DropShadow bloom = new DropShadow(BlurType.GAUSSIAN, Color.web(TX, .55), 0, 0, 0, 0);
        b.setEffect(bloom);
        Timeline t = new Timeline(
                new KeyFrame(IGNITE, new KeyValue(lit, 1, Motion.IN_OUT)),
                new KeyFrame(Duration.millis(200), new KeyValue(bloom.radiusProperty(), 16, Motion.IN_OUT)),
                new KeyFrame(Motion.CAPTURE, new KeyValue(bloom.radiusProperty(), 0, Motion.IN_OUT)));
        t.setOnFinished(e -> {
            b.setStyle("");
            b.lookupAll(".ico").forEach(n -> n.setStyle(""));
            b.setEffect(null);
        });
        t.play();
    }

    /** New project part way lit: 0 is the raised secondary look, 1 the bone primary. Inline, so
        it beats .btn-primary while the entrance runs; the words and the glyph cross from bone to
        ink as the fill crosses from grey to bone. */
    private void igniteStep(double v) {
        Color fill = Color.web(RAISED).interpolate(Color.web(TX), v), ink = Color.web(TX).interpolate(Color.web(G1), v);
        btnNewProjectEmpty.setStyle("-fx-background-color: " + web(fill) + "; -fx-text-fill: " + web(ink) + ";");
        btnNewProjectEmpty.lookupAll(".ico").forEach(n -> n.setStyle("-fx-stroke: " + web(ink) + ";"));
    }

    private static String web(Color c) {
        return String.format("#%02X%02X%02X", Math.round(c.getRed() * 255), Math.round(c.getGreen() * 255),
                Math.round(c.getBlue() * 255));
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

    /** No projects: the narrow rail, whatever the saved width. The steps need 21 cells and the
        wide sidebar would only list nothing; the first project brings the saved width back. */
    private void showEmpty(boolean empty) {
        showRail(!empty && PREFS.getBoolean(SIDEBAR_WIDE, true));
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
        showRail(wide);
        sidebarToggle.setSelected(wide);   // Ctrl+B keeps the Settings box honest; no-op from the box
        PREFS.putBoolean(SIDEBAR_WIDE, wide);
    }

    /** The look alone, remembered nowhere: the empty state borrows the narrow rail. */
    private void showRail(boolean wide) {
        rail.getStyleClass().remove("rail-wide");
        if (wide) rail.getStyleClass().add("rail-wide");
        // the narrow rail has no header at all, so its first number sits where PROJECTS would
        for (Node n : List.of(railHead, btnNewProjectWide)) {
            n.setVisible(wide);
            n.setManaged(wide);
        }
        btnNewProjectRail.setVisible(!wide);
        btnNewProjectRail.setManaged(!wide);
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
        // the offer and Unsaved work belong to the project that saved, not to the next one
        if (activeProject != projectObj) {
            dismissOffer();
            endClose();
        }
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
        pathTipPath.setText(projectObj.folderPath == null ? "no folder chosen" : projectObj.folderPath);

        int apps = launchableApps(projectObj);
        int tabs = countTabs(projectObj);

        btnResume.setDisable(apps == 0 && tabs == 0);
        // the count is the proof: what one press brings back, on the button that does it - so it
        // matches the chips on the card, not everything captured (Other is never relaunched)
        resumeCounts.setText(apps > 0 || tabs > 0 ? resumeCountLine(apps, tabs)
                : countApps(projectObj) > 0 ? "NOTHING TO RELAUNCH" : "NOTHING SAVED YET");
        // the same age, computed at the same moment, on the project's rail row
        StackPane railRow = rowsByProject.get(projectObj.projectName);
        if (railRow != null) railTip(railRow, projectObj);

        renderCategories(projectObj);
        renderTabs(projectObj);
        fillNote(projectObj);
        // last: it marks the chips and tab groups just drawn
        renderCkPanel();
    }

    private void installNote() {
        noteGlyphBox.getChildren().add(Icons.node(Icons.STICKY, 14, TX2));
        Pane x = Icons.node(Icons.CLOSE, 14, TX2);
        btnNoteClose.getChildren().add(x);
        Icons.animate(btnNoteClose, x);
        Tooltip.install(btnNoteClose, tip("Remove note"));
        // the field writes the cleared note back and saves it; the strip then folds away
        clickable(btnNoteClose, "Remove note", () -> {
            noteField.clear();
            saveNote();
            showNoteStrip(false);
            detailPane.requestFocus();
        });
        noteField.textProperty().addListener((o, was, now) -> {
            if (noteOwner == null) return;
            String note = noteOf(now);
            if (Objects.equals(note, noteOwner.note)) return;
            noteOwner.note = note;
            noteDirty = true;
        });
        // the strip's own sticky, size and all, so nothing shifts when the button opens it
        btnAddNote.setGraphic(Icons.node(Icons.STICKY, 14, TX2));
        btnAddNote.setOnAction(e -> { showNoteStrip(true); noteField.requestFocus(); });
        // Enter, Esc and Tab are all "done": let go of the field, which saves it. A filter, so
        // the TextArea never gets to insert the newline or tab: the note wraps, it has no lines
        noteField.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            KeyCode k = e.getCode();
            if (k == KeyCode.ENTER || k == KeyCode.ESCAPE || k == KeyCode.TAB) {
                detailPane.requestFocus();
                e.consume();
            }
        });
        // grow with the wrapped text, one line to two, then scroll
        for (var p : List.of(noteField.textProperty(), noteField.widthProperty(), noteField.fontProperty())) {
            p.addListener(o -> fitNote());
        }
        noteField.focusedProperty().addListener((o, was, focused) -> {
            if (focused) return;
            saveNote();
            // a strip left empty folds back into the button
            if (noteOwner == null || noteOwner.note == null) showNoteStrip(false);
        });
    }

    /** Off-screen twin of the note's text: same font, same wrap width, so its height is the
        height the wrapped note needs. The TextArea itself only ever reports its pref rows. */
    private final javafx.scene.text.Text noteMeasure = new javafx.scene.text.Text();

    /** Snaps the field to one or two whole rows (past two it scrolls): sizing it to the
        measured pixels on every keystroke made it wobble as the text reflowed and the
        TextArea scrolled to keep the caret in view. */
    private void fitNote() {
        noteMeasure.setFont(noteField.getFont());
        noteMeasure.setWrappingWidth(0);
        noteMeasure.setText("X");
        double line = noteMeasure.getLayoutBounds().getHeight();
        noteMeasure.setText("X\nX");
        double twoLines = noteMeasure.getLayoutBounds().getHeight();
        // the content's 8px right padding and the 10px scrollbar, always: a line that only just
        // fit without the bar overflowed, the bar appeared, the line wrapped under it and the
        // field stayed one row with its second line hidden. Erring narrow costs at worst an
        // early second row
        noteMeasure.setWrappingWidth(Math.max(1, noteField.getWidth() - 18));
        noteMeasure.setText(noteField.getText().isEmpty() ? " " : noteField.getText());
        int rows = noteMeasure.getLayoutBounds().getHeight() > line * 1.5 ? 2 : 1;
        // the measured block, not rows * line: a second line also brings the gap above it.
        // 8 of slack, or the TextArea clipped the last row's descenders
        double target = (rows == 1 ? line : twoLines) + 8;
        // compared on height, not rows: the first fit can run before CSS sets Plex Sans 14, and a
        // row count that did not change then kept the default font's shorter rows
        if (target == noteField.getPrefHeight()) return;
        noteField.setPrefHeight(target);
        // a VBox short of room shrinks EVERY child toward its min, not just the card that
        // grows, so with a long tab list the strip sat at its 40 and hid the second row.
        // Its min is its full height (the field, plus the strip's 6 + 6 padding)
        noteStrip.setMinHeight(Math.max(40, target + 12));
    }

    /** The NEXT strip while there is a note (or one being typed), else "Add a note". */
    private void showNoteStrip(boolean open) {
        noteStrip.setVisible(open);
        noteStrip.setManaged(open);
        btnAddNote.setVisible(!open);
        btnAddNote.setManaged(!open);
    }

    /** What the field holds, as stored: trimmed, and null rather than blank. */
    static String noteOf(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    /** renderDetail re-runs on every checkpoint and tab reply; only a change of project
        refills the field, or a re-render mid-typing would trim the text and move the caret. */
    private void fillNote(Project projectObj) {
        if (noteOwner == projectObj) return;
        saveNote();   // the project being left, if its note was edited and not yet written
        noteOwner = projectObj;   // first, so the listener sees the note as unchanged
        noteField.setText(projectObj.note == null ? "" : projectObj.note);
        noteDirty = false;
        showNoteStrip(projectObj.note != null);
    }

    private void saveNote() {
        if (noteOwner == null || !noteDirty) return;
        noteDirty = false;
        Project projectObj = noteOwner;
        try {
            saveProject(projectObj);
        } catch (IOException ex) {
            confirm("Note not written", projectsFile().getPath(),
                    "The card shows the note, the disk does not. " + ex.getMessage(),
                    "Retry", () -> { try { saveProject(projectObj); } catch (IOException ignored) { } });
        }
    }

    // the design folds at four categories and links to an inspector; that artboard is not
    // built, so every non-empty category is listed rather than hidden behind a dead link
    private static final List<String> CATEGORY_ORDER = List.of(
            "IDE", "AI", "Terminal", "Browser", "Database", "API", "Containers",
            "VersionControl", "Design", "Video", "Office", "Communication", "Notes", "Files", "Music", "Apps");

    /** "2h ago" under Checkpoint: when the last one was taken. It used to read "overwrite \u00b7
        2h old", a warning on every press; a checkpoint is now one Ctrl+Z from undone. */
    private String idleLine(Project projectObj) {
        return checkpointing == projectObj ? CHECKPOINTING : checkpointLine(projectObj.lastCheckpoint);
    }

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

        List<String> filled = new ArrayList<>(CATEGORY_ORDER);
        filled.removeIf(c -> visibleApps(projectObj, c).isEmpty());
        // every category is listed: the column scrolls, so nothing hides behind a "+n more"
        for (String category : filled) {
            Set<String> bucket = visibleApps(projectObj, category);

            // a 120px label column: 12px glyph + the 12px label; "Communication" is the widest
            HBox labelBox = new HBox(6);
            labelBox.setAlignment(Pos.CENTER_LEFT);
            labelBox.setMinWidth(120);
            labelBox.setPrefWidth(120);
            labelBox.setMaxWidth(120);
            labelBox.getChildren().addAll(categoryIcon(category),
                    styled(new Label(categoryLabel(category)), "cat-label"));

            FlowPane chips = chipFlow();
            for (String app : bucket) {
                chips.getChildren().add(category.equals(USER_APPS) ? appsChip(projectObj, app) : chip(projectObj, category, app));
            }

            HBox row = new HBox(10);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getChildren().addAll(labelBox, chips);

            String note = trailingNote(category, projectObj);
            if (note != null) {
                // "reopens chats" is a phrase, the IDE note a path: the face follows which
                Label arg = styled(new Label(note), note.startsWith("…\\") ? "project-path" : "cat-arg");
                arg.setMinWidth(Region.USE_PREF_SIZE);   // white-space: nowrap
                // "…\Downloads +1" is short on purpose; hovering shows every full path, as the
                // project path does
                String full = trailingTip(category, projectObj);
                if (full != null) Tooltip.install(arg, tip(full));
                row.getChildren().add(arg);
            }

            categoryRows.getChildren().add(row);
        }

        // "Other" is captured but never relaunched, which the design calls out by name: the
        // greyed line it always was, now a fold. Opened, its chips sit under the line and each
        // app can be sorted out of it with + or x. Hidden apps (from any project) are left out.
        Set<String> other = visibleApps(projectObj, OTHER);
        if (other.isEmpty()) return;
        String first = appName(other.iterator().next());
        String rest = other.size() > 1 ? " (+" + (other.size() - 1) + ")" : "";
        Pane chevron = Icons.node(Icons.CHEVRON, 12, TX3);
        chevron.setRotate(otherOpen ? 90 : 0);
        HBox foot = new HBox(9, Icons.node(Icons.APP_WINDOW, 12, TX3),
                styled(new Label(first + rest + " under Other — captured, not relaunched"), "cat-foot"), chevron);
        foot.setAlignment(Pos.CENTER_LEFT);
        foot.getStyleClass().add("other-fold");
        clickable(foot, otherOpen ? "Fold the apps under Other" : "Show the apps under Other", () -> {
            otherOpen = !otherOpen;
            renderCategories(projectObj);
        });
        categoryRows.getChildren().add(foot);
        if (otherOpen) {
            FlowPane chips = chipFlow();
            other.forEach(app -> chips.getChildren().add(otherChip(projectObj, app)));
            // under the words, past the 12px glyph and the 9px gap
            VBox.setMargin(chips, new Insets(0, 0, 0, 21));
            categoryRows.getChildren().add(chips);
        }
    }

    /** Whether the Other fold is open. Per run, not saved: it starts folded, as Other always
        read before it could be acted on. */
    private boolean otherOpen;

    /** flex-wrap: as wide as its chips, wrapping only when the row runs out. FlowPane otherwise
        asks for a fixed 400px, which pushed the note off its chip. */
    private static FlowPane chipFlow() {
        FlowPane chips = new FlowPane(5, 5) {
            @Override protected double computePrefWidth(double height) {
                double w = -getHgap();
                for (Node n : getManagedChildren()) w += n.prefWidth(-1) + getHgap();
                return Math.max(0, w) + snappedLeftInset() + snappedRightInset();
            }
        };
        chips.setAlignment(Pos.CENTER_LEFT);
        chips.setMinWidth(0);
        return chips;
    }

    /** The category's apps in chip order; for Other, without the ones the user hid. */
    static Set<String> visibleApps(Project projectObj, String category) {
        TreeSet<String> out = new TreeSet<>(projectObj.apps.getOrDefault(category, new HashSet<>()));
        if (category.equals(OTHER)) out.removeIf(app -> hidden.contains(app.toLowerCase()));
        return out;
    }

    // only where the exe name is not the app's name; everything else is the exe, capitalised
    private static final Map<String, String> APP_NAMES = Map.ofEntries(
            Map.entry("code", "VS Code"), Map.entry("code - insiders", "VS Code Insiders"),
            Map.entry("explorer", "File Explorer"), Map.entry("mscopilot", "Copilot"),
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
            Map.entry("heidisql", "HeidiSQL"), Map.entry("tableplus", "TablePlus"), Map.entry("chatgpt", "ChatGPT"),
            Map.entry("afterfx", "After Effects"), Map.entry("resolve", "DaVinci Resolve"),
            Map.entry("capcut", "CapCut"), Map.entry("indesign", "InDesign"),
            Map.entry("mspaint", "Paint"), Map.entry("paintdotnet", "Paint.NET"), Map.entry("winword", "Word"),
            Map.entry("excel", "Excel"), Map.entry("powerpnt", "PowerPoint"), Map.entry("msaccess", "Access"),
            Map.entry("visio", "Visio"), Map.entry("mspub", "Publisher"), Map.entry("winproj", "Project"),
            Map.entry("acrord32", "Acrobat Reader"), Map.entry("foxitpdfreader", "Foxit PDF Reader"),
            Map.entry("wps", "WPS Writer"), Map.entry("et", "WPS Spreadsheets"), Map.entry("wpp", "WPS Presentation"),
            Map.entry("outlook", "Outlook"), Map.entry("olk", "Outlook"), Map.entry("onenote", "OneNote"),
            Map.entry("obs", "OBS Studio"), Map.entry("camtasiastudio", "Camtasia"), Map.entry("fl", "FL Studio"),
            Map.entry("itunes", "iTunes"), Map.entry("azuredatastudio", "Azure Data Studio"),
            Map.entry("xournalpp", "Xournal++"), Map.entry("aimp", "AIMP"), Map.entry("xd", "Adobe XD"));

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
        agent view, any app its saved folders or files ("…\atlas-api +1"), else VS Code the
        project folder. */
    static String trailingNote(String category, Project projectObj) {
        if ("AI".equals(category) && hasApp(projectObj, "claude.exe")) {
            return "reopens chats";
        }
        List<String> paths = notePaths(category, projectObj);
        if (paths.isEmpty()) return null;
        List<String> names = paths.stream().map(p -> new File(p).getName()).distinct().toList();
        return "…\\" + names.get(0) + (names.size() > 1 ? " +" + (names.size() - 1) : "");
    }

    /** The full paths behind trailingNote's "…\name +1", one per line: its hover label. */
    static String trailingTip(String category, Project projectObj) {
        List<String> paths = notePaths(category, projectObj);
        return paths.isEmpty() ? null : String.join("\n", paths);
    }

    /** The saved folders and files of this category's apps, else VS Code's project folder. */
    private static List<String> notePaths(String category, Project projectObj) {
        if (projectObj.folders != null) {
            List<String> paths = projectObj.folders.entrySet().stream()
                    .filter(e -> category.equals(categoryOf(e.getKey())))
                    .flatMap(e -> e.getValue().stream()).distinct().toList();
            if (!paths.isEmpty()) return paths;
        }
        if ("IDE".equals(category) && hasApp(projectObj, "Code.exe") && projectObj.folderPath != null) {
            return List.of(projectObj.folderPath);
        }
        return List.of();
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
            // its extension was asleep at the checkpoint: these are the tabs from the one before
            Owed owed = owedTabs.get(browser);
            if (owed != null && owed.project() == projectObj) {
                Label stale = styled(new Label(System.nanoTime() < owed.until() ? "waiting for tabs…"
                        : "from last checkpoint"), "side-count");
                Tooltip.install(stale, tip("Its extension was asleep when you saved, so these are the tabs from the checkpoint before"));
                head.getChildren().add(stale);
            }
            head.getProperties().put("browser", browser);   // markClosed dims the group
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
                row.getProperties().put("browser", browser);
                tabRows.add(row);
            }
        });
        tabsList.getChildren().setAll(tabRows);
    }

    /** "brave" -> "Brave": extension brands arrive in whatever case the browser reports. */
    static String browserLabel(String browser) {
        // tabs from a browser the extension could not name, reopened in the system default
        if (DEFAULT_BROWSER.equals(browser)) return "Default browser";
        // "Chrome · Work" reads better than the email the bucket is keyed by
        // ponytail: reads Local State on every label; cache it if the file ever gets slow
        String name = nameOf(profileEntry(browser));
        if (name != null) browser = brandOf(browser) + PROFILE_SEP + name;
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
        chip.getProperties().put("exe", app);   // markClosed strikes it through once it closes
        Tooltip.install(chip, tip(app));
        chip.getStyleClass().addAll("chip","cut");
        chip.setAlignment(Pos.CENTER_LEFT);
        return chip;
    }

    /** An Other chip: + relaunches the app on every resume from now on (it moves to Apps), x
        hides it for good. The − on its Apps chip undoes the +; Settings undoes the x. */
    private HBox otherChip(Project projectObj, String app) {
        String name = appName(app);
        StackPane add = chipButton(Icons.NEW, "Relaunch " + name + " on every resume", () -> relaunchApp(app), projectObj, 12);
        add.getStyleClass().add("rm-add");
        return actionChip(app, add,
                chipButton(Icons.CLOSE, "Hide " + name + ": stop capturing it", () -> hideApp(projectObj, app), projectObj, 12));
    }

    /** An Apps chip: − takes back the + that put it here (back to Other, not relaunched); x
        removes it from this project like any chip. */
    private HBox appsChip(Project projectObj, String app) {
        String name = appName(app);
        // the minimize glyph is a plain minus; not red on hover, it is an undo, not a removal
        StackPane back = chipButton(Icons.MINIMIZE, "Stop relaunching " + name + ": back to Other",
                () -> unrelaunchApp(app), projectObj, 12);
        back.getStyleClass().add("rm-add");
        return actionChip(app, back, remover(() -> removeApp(projectObj, USER_APPS, app), projectObj, 12, name));
    }

    /** A chip with two hover buttons. Each says what it does on hover - the x on an Other chip
        is not a plain remove - and they sit 12px apart so their 24px targets meet, not overlap. */
    private HBox actionChip(String app, StackPane first, StackPane second) {
        for (StackPane b : List.of(first, second)) Tooltip.install(b, tip(b.getAccessibleText()));
        HBox.setMargin(second, new Insets(-4, -6, -4, 4));
        HBox chip = new HBox(2, new Label(appName(app)), first, second);
        chip.getProperties().put("exe", app);
        Tooltip.install(chip, tip(app));
        chip.getStyleClass().addAll("chip", "cut");
        chip.setAlignment(Pos.CENTER_LEFT);
        return chip;
    }

    /** The x on chips and tab rows, shown on hover or keyboard focus. Opacity, not managed, so
        nothing shifts. No undo: the next checkpoint brings anything back. */
    private StackPane remover(Runnable edit, Project projectObj, double size, String what) {
        return chipButton(Icons.CLOSE, "Remove " + what, edit, projectObj, size);
    }

    /** A hover button on a chip or tab row that edits the project, redraws and saves it. The
        action is its accessible text and, beside a chip's own tooltip, its hover label. */
    private StackPane chipButton(Icons.Glyph shape, String action, Runnable edit, Project projectObj, double size) {
        StackPane x = new StackPane();
        x.getStyleClass().add("rm");
        // a 24px target (WCAG 2.5.8) that still lays out as the glyph's 12x16: the negative
        // margin hands the extra back, so rows and chips keep their size
        x.setPickOnBounds(true);
        HBox.setMargin(x, new Insets(-4, -6, -4, -6));
        Pane glyph = Icons.node(shape, size, TX3);
        glyph.getStyleClass().add("rm-glyph");
        x.getChildren().add(glyph);
        Icons.animate(x, glyph);
        // in the Tab order and shown on focus (hud.css), so it is not mouse-only
        x.setFocusTraversable(true);
        x.setAccessibleRole(AccessibleRole.BUTTON);
        x.setAccessibleText(action);
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
        return Icons.node(Icons.CATEGORY.getOrDefault(category, Icons.APP_WINDOW), 14, TX2);
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
    //   which keeps failures visible with a Retry and auto-returns to the project in 5s.

    // ponytail: 750ms between spawns is a first guess at what stops ten processes landing
    // on the machine at once; the design paces its prototype at 220ms for looks. Tune by feel.
    static final long LAUNCH_GAP_MS = 750;

    private void onResume() {
        // a resume during a checkpoint's capture would be recorded into that checkpoint
        if (activeProject == null || resuming || checkpointing != null || btnResume.isDisabled()) return;
        // nor while its apps are being closed; Unsaved work is over once they come back
        if (closeState == Close.CLOSING) return;
        dismissOffer();
        endClose();
        unsavedByProject.remove(activeProject.projectName);
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
                    r.plan = planResume(r.project, captureRunningApps(), liveFolders(r.project), r.log);
                    Platform.runLater(() -> renderResuming(r));
                }
                if (r.queued() > 0) placeWindows(r.project.windows);
                for (LaunchStep step : r.plan.steps()) {
                    if (step.state != LaunchStep.State.QUEUED) continue;   // ran before the stop
                    if (stopRequested) {
                        r.log.add("stopped by user");
                        break;
                    }
                    step.state = LaunchStep.State.LAUNCHING;
                    Platform.runLater(() -> renderResuming(r));
                    step.run();
                    r.log.add(step.logLine());
                    Platform.runLater(() -> renderResuming(r));
                    // the gap paces processes landing on the machine: a step that failed or found
                    // everything open started none, and after the last one it only padded "took"
                    if (step.state == LaunchStep.State.DONE && !step.waiting && r.queued() > 0) Thread.sleep(LAUNCH_GAP_MS);
                }
            } catch (IOException ex) {
                // without the list of open apps there is no telling what would open twice, so
                // nothing is launched and 1l says so instead of "RESUMED"
                r.error = ex.getMessage() == null ? "the open apps could not be listed" : ex.getMessage();
                r.log.add("resume failed: " + r.error);
                System.err.println("resume failed: " + ex);
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
        // finished, not started: counting the one launching put 100% up while the last step
        // (a browser waiting on its extension) still had seconds to go
        long finished = r.done() + r.failed();
        resumingCount.setText(finished + " / " + (total == 0 ? "?" : String.valueOf(total)));
        double frac = total == 0 ? 0 : (double) finished / total;
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
                case DONE, SKIPPED -> { }
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

        List<String> skipped = r.alreadyOpen();
        resumingSkipped.setText(skipped.isEmpty() ? "" : skipped.size() + " already open, left alone · " + names(skipped));
    }

    /** The run 1l is showing, for "Resume the rest". */
    private ResumeRun shownRun;

    /** How a run ended, as 1l's verb: only a run that got through its plan says RESUMED. */
    static String outcome(ResumeRun r) {
        return r.error != null ? "NOT RESUMED" : r.stopped ? "STOPPED" : "RESUMED";
    }

    /** 1l. Orange closes the verb. A clean run goes back to 1b after RETURN_SECONDS; a stopped run,
        a failed capture or a failed launch stays until it is dealt with or left, so it can be read. */
    private void showRestored(ResumeRun r) {
        shownRun = r;
        String verb = outcome(r);
        boolean clean = r.error == null && !r.stopped && r.failed() == 0;
        titleState(verb, r.project.projectName, clean);
        restoredWord.setText(verb);
        restoredName.setText(r.project.projectName);
        boolean hasNote = r.project.note != null;
        restoredNext.setText(hasNote ? r.project.note : "");
        restoredNext.setVisible(hasNote);
        restoredNext.setManaged(hasNote);
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
        int tabsOpened = tabsOpened(r);
        restoredSpec.setText(count(r.appsDone(), "app") + " · " + count(tabsOpened, "tab"));

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
            if (step.state == LaunchStep.State.DONE && !step.waiting) (step.browser ? browsers : apps).add(step.label);
        }
        if (r.error == null) {
            restoredRows.getChildren().add(foldRow(Icons.APP_WINDOW, count(apps.size(), "app") + " launched",
                    apps.isEmpty() ? "nothing new to open" : names(apps)));
            // the extension skips tabs that are already up; saying so is what makes 3 of 6 add up
            int tabsUp = tabsAlreadyOpen(r);
            restoredRows.getChildren().add(foldRow(Icons.BROWSER, count(tabsOpened, "tab") + " opened",
                    browsers.isEmpty() ? "no browser opened"
                            : String.join(" · ", browsers) + (tabsUp > 0 ? " · " + tabsUp + " already open" : "")));
            // an open browser whose extension is asleep: not sent yet, and saying so beats a
            // "sent" count while nothing appears for up to the extension's 30s wake-up
            List<String> waiting = new ArrayList<>();
            for (LaunchStep step : r.plan.steps()) {
                if (step.waiting) waiting.add(step.label);
            }
            if (!waiting.isEmpty()) {
                restoredRows.getChildren().add(foldRow(Icons.TIMESTAMP, count(tabsWaiting(r), "tab") + " waiting for "
                        + String.join(" · ", waiting), "they open when its extension connects"));
            }
        }
        // the RESUME block counted these too; without this row "6 apps" would end as "4 apps"
        // with nothing to say where the other two went
        List<String> skipped = r.alreadyOpen();
        if (!skipped.isEmpty()) {
            restoredRows.getChildren().add(foldRow(Icons.CHECK, skipped.size() + " already open, left alone", names(skipped)));
        }
        HBox log = foldRow(Icons.TERMINAL, "Resume log", r.log.size() + " lines · " + (logOpen ? "hide" : "show"));
        clickable(log, logOpen ? "Hide the resume log" : "Show the resume log", () -> { logOpen = !logOpen; renderRestored(r); });
        restoredRows.getChildren().add(log);
        if (logOpen) {
            Label lines = styled(new Label(String.join("\n", r.log)), "step-cmd");
            lines.setWrapText(true);
            ScrollPane scroll = new ScrollPane(lines);
            scroll.getStyleClass().add("tabs-scroll");
            scroll.setFitToWidth(true);
            scroll.setMaxHeight(140);
            restoredRows.getChildren().add(scroll);
        }
    }

    /** Tabs a browser step opened: its extension's count, or every saved url when there is none. */
    static int tabsOpened(ResumeRun r) {
        return tabs(r, false, false);
    }

    /** Saved tabs an extension found already open, so left alone. */
    static int tabsAlreadyOpen(ResumeRun r) {
        return tabs(r, false, true);
    }

    /** Tabs queued for an extension that has not connected yet. */
    static int tabsWaiting(ResumeRun r) {
        return tabs(r, true, false);
    }

    private static int tabs(ResumeRun r, boolean waiting, boolean alreadyOpen) {
        int n = 0;
        for (LaunchStep step : r.plan.steps()) {
            if (step.browser && step.state == LaunchStep.State.DONE && step.waiting == waiting) {
                List<String> urls = r.project.urlsByBrowser.get(step.label);
                int saved = urls == null ? 0 : urls.size();
                int opened = step.opened < 0 ? saved : Math.min(step.opened, saved);
                n += alreadyOpen ? saved - opened : opened;
            }
        }
        return n;
    }

    /** "1 app", "3 apps". */
    static String count(long n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    /** How long a clean RESUMED stays up before going back to the project. */
    static final int RETURN_SECONDS = 5;

    private void startCountdown(ResumeRun r) {
        String name = r.project.projectName;
        countdownFrac.set(0);
        countdown = new Timeline(
                new KeyFrame(Duration.seconds(RETURN_SECONDS), e -> backToProjects(),
                        new KeyValue(countdownFrac, 1, Interpolator.LINEAR)));
        for (int s = 0; s < RETURN_SECONDS; s++) {
            String left = "returning to " + name + " in " + (RETURN_SECONDS - s) + "s";
            countdown.getKeyFrames().add(new KeyFrame(Duration.seconds(s), e -> restoredNote.setText(left)));
        }
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
            r.log.add(step.logLine() + " (retry)");
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
            CK = "#2A5688", CKT = "#6E9BD1", WARN = "#E3C55A", RAISED = "#222428", G1 = "#0F1012";

    static int countApps(Project projectObj) {
        if (projectObj == null || projectObj.apps == null) return 0;
        return projectObj.apps.values().stream().mapToInt(Set::size).sum();
    }

    /** What one press of Resume brings back: every app chip on the card. A browser with saved
        tabs comes back as its tabs and a terminal hosting claude opens with it, but both still
        open, so both count; only Other, listed apart as "not relaunched", is left out. */
    static int launchableApps(Project projectObj) {
        if (projectObj == null || projectObj.apps == null) return 0;
        return CATEGORY_ORDER.stream().mapToInt(c -> projectObj.apps.getOrDefault(c, new HashSet<>()).size()).sum();
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
        // what a first-timer cannot read off the block: what it saves, and its key
        Tooltip.install(btnCheckpoint, tip("Save this project's open apps and tabs · Ctrl+S"));
        btnCheckpoint.setOnContextMenuRequested(e -> { e.consume(); menu.show(btnCheckpoint, e.getScreenX(), e.getScreenY()); });
    }

    private void onCheckpoint() {
        // No "are you sure" for an old checkpoint: whatever this replaces is one Ctrl+Z away
        // (undoCheckpoint). A press while "Saved" is still showing is taken too, not dropped.
        if (activeProject == null) return;
        // Unsaved work after a close: "Save, then click again". The press saves again, adding
        // to the checkpoint (the closed apps are not open to capture any more), so a file just
        // saved is recorded, then asks only the apps still open to close.
        if (closeState == Close.PARTIAL && closeProj == activeProject) {
            writeCheckpoint(activeProject, Checkpoint.ADD_APPS, survivors);
            return;
        }
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

    /** How many POST /tabs this checkpoint waits for: one per browser, plus the replies the
        last checkpoint is still owed while its window is open - those land on this latch, and
        would otherwise end the wait before this checkpoint's own browsers answer.
        ponytail: an owed reply that never comes costs the full TAB_WAIT_MILLIS, only inside the
        window; a capture id echoed by the extension would make it exact. */
    static long repliesToWait(int browsers, long stillOwed, long until, long now) {
        return browsers + (now < until ? stillOwed : 0);
    }

    private CountDownLatch askForTabs(Project projectObj) {
        long now = System.nanoTime();
        long wait = repliesToWait(wsSessions.size(), tabReplies.getCount(), tabTargetUntil, now);
        tabTarget = projectObj;
        tabTargetUntil = now + TAB_WINDOW_NANOS;
        tabReplies = new CountDownLatch((int) wait);
        wsSessions.forEach(session -> session.send("capture"));
        return tabReplies;
    }

    /** One count per browser asked by the last checkpoint; POST /tabs ticks it off. */
    private volatile CountDownLatch tabReplies = new CountDownLatch(0);
    /** How long "checkpointing…" waits, after the apps are listed, for browsers still to reply.
        ponytail: fixed, a worker the browser has put to sleep needs its alarm (up to a minute)
        and is not waited for; its saved tabs stay and a late reply still updates the card. */
    static final long TAB_WAIT_MILLIS = 3_000;

    /** Puts back what a checkpoint changes (apps, their folders and exe paths, tabs, its time)
        from a gson snapshot. A field a checkpoint starts writing must be added here, or undo
        leaves the newer value behind. */
    void restore(Project projectObj, String snapshot) {
        Project old = gson.fromJson(snapshot, Project.class);
        projectObj.apps = old.apps;
        projectObj.folders = old.folders;
        projectObj.exePaths = old.exePaths;
        projectObj.windows = old.windows;
        projectObj.urlsByBrowser = old.urlsByBrowser;
        projectObj.lastCheckpoint = old.lastCheckpoint;
    }

    private void dropIncomingTabs(Project projectObj) {
        tabTarget = null;
        tabTargetUntil = System.nanoTime() + TAB_WINDOW_NANOS;
        owedTabs.values().removeIf(o -> o.project() == projectObj);
    }

    /** Whether a bucket's browser has its profile open, from the running exes. */
    static boolean browserOpen(String bucket, Set<String> running) {
        String exe = BROWSER_EXE.get(brandOf(bucket).toLowerCase());
        return exe != null && profileOpen(bucket, exe, running);
    }

    /** The owed entry a connected extension (or its POST /tabs) answers, while it is still due. */
    static Map.Entry<String, Owed> owedFor(Map<String, Owed> owed, String connected, long now) {
        return owed.entrySet().stream()
                .filter(e -> now < e.getValue().until() && sameBrowser(connected, e.getKey()))
                .findFirst().orElse(null);
    }

    /** The browsers still due to send a checkpoint their tabs. */
    private List<String> owedBrowsers() {
        long now = System.nanoTime();
        return owedTabs.entrySet().stream().filter(e -> now < e.getValue().until())
                .map(e -> browserLabel(e.getKey())).distinct().sorted().toList();
    }

    /** Owed tabs landed or ran out: the tab groups say where they stand, and a held quit goes. */
    private void owedSettled() {
        if (activeProject != null && isHud()) renderDetail(activeProject);
        if (exitWaiting && owedBrowsers().isEmpty()) ((Stage) btnCheckpoint.getScene().getWindow()).close();
    }

    /** The window's close: a browser still owing a checkpoint its tabs holds it on a scrim that
        goes by itself once they land (35s at most), or at once on Quit anyway. Quitting first
        would leave that browser with the last checkpoint's tabs. True when held. */
    public boolean holdExit() {
        List<String> owed = owedBrowsers();
        if (owed.isEmpty()) return false;
        confirm("Waiting for tabs", String.join(" · ", owed),
                "Its extension is waking up and sends this checkpoint its tabs in under a minute; "
                        + "this window closes once they land. Quit now and it keeps the tabs from the checkpoint before.",
                "Quit anyway", () -> ((Stage) btnCheckpoint.getScene().getWindow()).close());
        exitWaiting = true;   // after confirm: its showOverlay clears it
        return true;
    }

    private void undoCheckpoint() {
        if (activeProject == null || resuming || checkpointing != null) return;
        String before = undoByProject.remove(activeProject.projectName);
        if (before == null) return;
        Project projectObj = activeProject;
        restore(projectObj, before);
        // a tab reply still on its way belongs to the checkpoint just undone
        dropIncomingTabs(projectObj);
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
        writeCheckpoint(projectObj, mode, null);
    }

    /** retry: the apps a close left open (Unsaved work). Once this save lands - it records a
        file the user has just saved - they are asked to close again, and nothing is offered. */
    private void writeCheckpoint(Project projectObj, Checkpoint mode, List<String> retry) {
        // a checkpoint mid-resume would record a half-launched desktop over the project, and a
        // second press while one is capturing would only race it to the disk
        if (resuming || checkpointing != null || closeState == Close.CLOSING) return;
        dismissOffer();
        checkpointing = projectObj;
        String before = gson.toJson(projectObj);
        // No bucket is ever dropped here: a browser that answers the capture push overwrites its
        // own bucket in POST /tabs, and one that does not answer (asleep worker, dead socket,
        // closed) keeps what it had. Stale beats gone.
        tabMode = mode;
        // a browser with no extension on the socket owes its tabs instead: asked when it
        // connects. The ones not open at all are let off once the capture below has looked
        if (mode.tabs()) {
            owedTabs.values().removeIf(o -> o.project() == projectObj);   // this one asks again
            long until = System.nanoTime() + OWED_NANOS;
            for (String bucket : projectObj.urlsByBrowser.keySet()) {
                if (liveSession(bucket) == null) owedTabs.put(bucket, new Owed(projectObj, mode, until));
            }
        }
        boolean owes = owedTabs.values().stream().anyMatch(o -> o.project() == projectObj);
        CountDownLatch replies = mode.tabs() ? askForTabs(projectObj) : new CountDownLatch(0);
        if (projectObj == activeProject) renderCkPanel();   // "checkpointing…"
        // the PowerShell capture takes seconds: off the FX thread the HUD keeps drawing and
        // the block can say what it is doing
        Thread worker = new Thread(() -> {
            HashMap<String, HashSet<String>> now = null;
            AppFolders.Capture dirs = null;
            IOException failed = null;
            try {
                HashSet<String> running = mode.apps() || owes ? captureRunningApps() : null;
                if (mode.apps()) {
                    now = segregateApps(new HashSet<>(running));
                    dirs = captureFolders(now);
                }
                // a closed browser has no tabs to send: it keeps the saved ones, as always
                if (owes) owedTabs.entrySet().removeIf(e -> e.getValue().project() == projectObj
                        && !browserOpen(e.getKey(), running));
                // still "checkpointing…": the browsers usually answered while PowerShell ran,
                // so this rarely waits, and "Saved" can always give the tab count
                replies.await(TAB_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (IOException ex) {
                System.err.println("checkpoint capture failed: " + ex);
                failed = ex;
            } catch (InterruptedException ignored) {
            }
            HashMap<String, HashSet<String>> captured = now;
            AppFolders.Capture folders = dirs;
            IOException error = failed;
            Platform.runLater(() -> finishCheckpoint(projectObj, mode, retry, before, captured, folders, error));
        }, "checkpoint");
        worker.setDaemon(true);
        worker.start();
    }

    static final String CHECKPOINTING = "checkpointing…";

    /** The FX-thread half of writeCheckpoint, once the capture is back (or failed). */
    private void finishCheckpoint(Project projectObj, Checkpoint mode, List<String> retry, String before,
                                  HashMap<String, HashSet<String>> captured,
                                  AppFolders.Capture folders, IOException captureError) {
        checkpointing = null;
        boolean onScreen = projectObj == activeProject;
        try {
            if (captureError != null) throw captureError;
            if (captured != null) {
                if (mode.merge()) mergeApps(projectObj.apps, captured);
                else projectObj.apps = captured;
            }
            // null: the window listing failed, so the folders saved last time are kept
            if (folders != null) {
                if (mode.merge() && projectObj.folders != null) {
                    mergeFolders(projectObj.folders, folders.folders());
                    if (projectObj.exePaths == null) projectObj.exePaths = new HashMap<>();
                    projectObj.exePaths.putAll(folders.exePaths());
                    // an app open now is where it is now
                    if (projectObj.windows == null) projectObj.windows = new HashMap<>();
                    projectObj.windows.putAll(folders.windows());
                } else {
                    projectObj.folders = folders.folders();
                    projectObj.exePaths = folders.exePaths();
                    projectObj.windows = folders.windows();
                }
            }
            projectObj.lastCheckpoint = Instant.now().toString();
            saveProject(projectObj);
        } catch (IOException ex) {
            // the card must not claim "saved" over a write that did not happen: memory goes back
            // to what the disk held, and the tabs already asked for are turned away. A reply that
            // landed during the capture was written by POST /tabs, so the old state is put back.
            restore(projectObj, before);
            dropIncomingTabs(projectObj);
            try { saveProject(projectObj); } catch (IOException ignored) { }
            if (onScreen) renderDetail(projectObj);
            confirm("Checkpoint not written", projectsFile().getPath(),
                    (captureError != null ? "The open apps could not be listed. " : "")
                            + "Nothing changed on disk. " + ex.getMessage(),
                    "Retry", "btn-checkpoint", () -> writeCheckpoint(projectObj, mode, retry));
            return;   // nothing is offered or closed over a save that did not happen
        }
        // windows whose title says they hold unsaved work; null when the listing failed
        if (folders != null) {
            Set<String> closable = appsToClose(projectObj.apps);   // a browser's page title is not a document
            List<String> dirty = folders.unsaved().stream().filter(closable::contains).toList();
            if (dirty.isEmpty()) unsavedByProject.remove(projectObj.projectName);
            else unsavedByProject.put(projectObj.projectName, dirty);
        }
        if (replaceUndo(System.nanoTime(), ckBusyUntil, undoByProject.containsKey(projectObj.projectName))) {
            undoByProject.put(projectObj.projectName, before);
        }
        StackPane row = rowsByProject.get(projectObj.projectName);
        if (row != null) railTip(row, projectObj);
        if (owedTabs.values().stream().anyMatch(o -> o.project() == projectObj)) {
            // "waiting for tabs…" turns into "from last checkpoint" for any that never came
            PauseTransition due = new PauseTransition(Duration.millis(OWED_NANOS / 1_000_000));
            due.setOnFinished(e -> owedSettled());
            due.play();
        }
        // switched away mid-capture: the save landed, the other card stays as it is
        if (!onScreen) return;

        renderDetail(projectObj);
        if (retry != null) {
            closeApps(projectObj, retry);   // the block goes straight to Closing
            return;
        }
        // "just now" dropped: the block's column holds ~18 characters, and "Saved" already says it.
        checkpointSaved("Saved", resumeCountLine(countApps(projectObj), countTabs(projectObj)));
        showOffer(projectObj);
    }

    /** Add mode: every exe open now joins its category; nothing saved is dropped. */
    static void mergeApps(Map<String, HashSet<String>> into, Map<String, HashSet<String>> from) {
        from.forEach((category, exes) -> into.computeIfAbsent(category, k -> new HashSet<>()).addAll(exes));
    }

    /** Add mode: a folder open now joins its app's list; nothing saved is dropped. */
    static void mergeFolders(Map<String, ArrayList<String>> into, Map<String, ArrayList<String>> from) {
        from.forEach((exe, dirs) -> {
            ArrayList<String> saved = into.computeIfAbsent(exe, k -> new ArrayList<>());
            for (String dir : dirs) {
                if (saved.stream().noneMatch(dir::equalsIgnoreCase)) saved.add(dir);
            }
        });
    }

    /** Folders and files the open apps have up (see AppFolders). An app only seen through its
        windows - Explorer, whose folder windows Get-Process does not report - joins its
        category in apps, so the card shows what resume will open. Null when the window
        listing failed, so the caller keeps what it had instead of forgetting every folder. */
    static AppFolders.Capture captureFolders(Map<String, HashSet<String>> apps) {
        AppFolders.Capture found;
        try {
            // always run, ~0.5s: Explorer windows never show up in apps to gate on
            found = AppFolders.capture();
        } catch (IOException ex) {
            System.err.println("could not list window folders: " + ex.getMessage());
            return null;
        }
        addFolderApps(apps, found.folders());
        keepCapturedExePaths(found.exePaths(), apps);
        keepCapturedExePaths(found.windows(), apps);
        return found;
    }

    /** The window listing has every titled window's exe - coldstart's own java.exe, the
        desktop's explorer.exe, overlays the capture ignores, apps the user hid. Only the apps
        this checkpoint kept need a path to relaunch from. */
    static void keepCapturedExePaths(Map<String, ?> exePaths, Map<String, HashSet<String>> apps) {
        exePaths.keySet().removeIf(exe -> apps.values().stream().flatMap(Set::stream).noneMatch(exe::equalsIgnoreCase));
    }

    static void addFolderApps(Map<String, HashSet<String>> apps, Map<String, ArrayList<String>> folders) {
        for (String exe : folders.keySet()) {
            boolean listed = apps.values().stream().flatMap(Set::stream).anyMatch(exe::equalsIgnoreCase);
            if (!listed) apps.computeIfAbsent(categoryOf(exe), k -> new HashSet<>()).add(exe);
        }
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
                  done    rwCkIn    300ms snap, after 120ms - "Saved / 10 APPS · 14 TABS"
          t=2400  done    rwCkOut   200ms ease-in
                  bar     rwCkBarOut 260ms standard
                  idle    rwCkIn    260ms standard, after 140ms
        A save before t=2860 only updates the line. Motion off: the words swap in place, no ring, no bar. */
    private void checkpointSaved(String word, String doneLine) {
        if (!Motion.showing(btnCheckpoint)) return;
        ckDoneWord.setText(word);
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
        // renderDetail has already moved the idle line on to "just now", which flashed for the
        // 160ms before it lifted away: it keeps "checkpointing…" and changes while hidden
        checkpointSub.setText(CHECKPOINTING);
        // and comes back as whatever the block is by then: idle, Closing, or Unsaved work
        t.getKeyFrames().add(new KeyFrame(Duration.millis(2400), e -> renderCkPanel()));
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

    // == Checkpoint & close (handoff 1d) =======================================
    //   Checkpoint stays one verb. Once a save lands, the note's place offers to close what it
    //   captured, for 6s (showOffer). Taking it asks every saved app to close the way its X
    //   does (WM_CLOSE), so an app holding unsaved work asks about it itself - nothing is ever
    //   killed - and each connected browser closes the project's tabs through its extension.
    //   The block counts the apps down as they really go (Closing · 3 of 6 apps); what is still
    //   open once the count stops moving is Unsaved work, and a press there saves again (so a
    //   file just saved is recorded) and retries only those. The result lands on the block.

    /** DONE: Saved & closed is on the block, the struck chips with it, for its 2.4s */
    enum Close { IDLE, CLOSING, PARTIAL, DONE }

    static final long CLOSE_POLL_MS = 1_000;
    static final int OFFER_SECONDS = 6;
    // ponytail: a PowerShell listing every second while apps are left; capped so an Unsaved
    // work left up all afternoon stops polling
    static final long CLOSE_WATCH_NANOS = 10L * 60 * 1_000_000_000L;
    /** the close cascade: chips go dark 90ms apart, starting 160ms after the click */
    static final Duration CASCADE_START = Duration.millis(160), CASCADE_STEP = Duration.millis(90);

    // never closed: a terminal ends whatever runs in it (claude, a dev server) without asking,
    // Explorer is the taskbar too (only its folder windows close, see closeScript), browsers
    // go tab by tab, and Other is never relaunched, so closing it would lose it
    static final Set<String> KEEP_OPEN = Set.of("Terminal", "Browser", "Files", HelloController.OTHER);

    private static final PseudoClass PC_CLOSING = PseudoClass.getPseudoClass("closing"),
            PC_FILLED = PseudoClass.getPseudoClass("filled"), PC_WARN = PseudoClass.getPseudoClass("warn"),
            PC_CLOSED = PseudoClass.getPseudoClass("closed"), PC_STUCK = PseudoClass.getPseudoClass("stuck");

    /** FX thread only, except closeWatch, which the poll reads. */
    private Close closeState = Close.IDLE;
    private Project closeProj;
    private volatile Object closeWatch;
    private int closeDone, closeTotal;
    /** struck-through chips (exe names) and dimmed tab groups (urlsByBrowser keys) */
    private final Set<String> closedApps = new TreeSet<>(String.CASE_INSENSITIVE_ORDER),
            closedBrowsers = new HashSet<>();
    private List<String> survivors = List.of();
    /** what a checkpoint saw with unsaved work in its title, per project (looksUnsaved) */
    private final Map<String, List<String>> unsavedByProject = new HashMap<>();

    private Project offerFor;
    private Timeline offerTimer;
    private final DoubleProperty offerLeft = new SimpleDoubleProperty(1);
    private Label offerWord;
    private Pane ckPower, ckAlert;

    static Set<String> appsToClose(Map<String, HashSet<String>> apps) {
        Set<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        apps.forEach((category, exes) -> { if (!KEEP_OPEN.contains(category)) out.addAll(exes); });
        out.remove("claude.exe");   // under AI, but it lives in a terminal tab
        return out;
    }

    /** Sends WM_CLOSE to every top-level window of these exes - PostMessage, so a save prompt
        never blocks the script - and quits Explorer windows that show a real folder. */
    static String closeScript(Collection<String> exes, boolean explorerFolders) {
        String names = exes.stream()
                .map(e -> "'" + e.replaceFirst("(?i)\\.exe$", "").replace("'", "''") + "'")
                .collect(java.util.stream.Collectors.joining(","));
        return "Add-Type -AssemblyName UIAutomationClient; "
                + "Add-Type -Namespace RW -Name U -MemberDefinition '[DllImport(\"user32.dll\")] "
                + "public static extern bool PostMessage(IntPtr h, uint m, IntPtr w, IntPtr l);'; "
                + "$n = @(" + names + "); $p = @{}; Get-Process | ForEach-Object { $p[$_.Id] = $_.ProcessName }; "
                + "[Windows.Automation.AutomationElement]::RootElement.FindAll("
                + "[Windows.Automation.TreeScope]::Children, [Windows.Automation.Condition]::TrueCondition) | "
                + "ForEach-Object { if ($n -contains $p[$_.Current.ProcessId]) { "
                + "[void][RW.U]::PostMessage([IntPtr]$_.Current.NativeWindowHandle, 0x10, [IntPtr]::Zero, [IntPtr]::Zero) } }"
                + (explorerFolders ? "; (New-Object -ComObject Shell.Application).Windows() | "
                        + "Where-Object { $_.LocationURL -like 'file:*' } | ForEach-Object { $_.Quit() }" : "");
    }

    /** -EncodedCommand (base64 UTF-16LE) skips Windows' argument quoting, which breaks on the
        double quotes closeScript needs (see captureRunningApps). */
    static Process powershell(String script) throws IOException {
        return new ProcessBuilder("powershell.exe", "-NoProfile", "-EncodedCommand",
                Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE)))
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    /** The closed apps that still have a window. */
    static List<String> stillOpen(Collection<String> closed, Set<String> running) {
        return closed.stream().filter(app -> running.stream().anyMatch(app::equalsIgnoreCase)).toList();
    }

    /** "6 apps · 8 tabs" */
    static String countLine(int apps, int tabs) {
        return apps + (apps == 1 ? " app" : " apps") + " · " + tabs + (tabs == 1 ? " tab" : " tabs");
    }

    /** The connected browsers' sockets and the saved tabs each would close. */
    private Map<WsContext, List<String>> tabsToClose(Project projectObj) {
        Map<WsContext, List<String>> out = new LinkedHashMap<>();
        for (WsContext s : wsSessions) {
            List<String> urls = projectObj.urlsByBrowser.entrySet().stream()
                    .filter(e -> sameBrowser(wsBrowser(s), e.getKey()))
                    .flatMap(e -> e.getValue().stream()).toList();
            if (!urls.isEmpty()) out.put(s, urls);
        }
        return out;
    }

    /** Apps the offer would close: the closable apps, plus each browser closing its tabs. A
        browser with no extension is left whole and not counted: closing it would take tabs
        the checkpoint could not see. */
    private int closeCount(Project projectObj) {
        Set<String> browsers = new HashSet<>();
        tabsToClose(projectObj).keySet().forEach(s -> browsers.add(brandOf(wsBrowser(s)).toLowerCase()));
        return appsToClose(projectObj.apps).size() + browsers.size();
    }

    private void installOffer() {
        offerGlyphBox.getChildren().add(Icons.node(Icons.CHECK, 14, CKT));   // the note's sticky is 14
        offerWord = styled(new Label(), "offer-word");
        HBox content = new HBox(10, Icons.node(Icons.POWER, 16, TX), offerWord, styled(new Label("Enter"), "offer-key"));
        content.setAlignment(Pos.CENTER_LEFT);
        btnOfferClose.setGraphic(content);
        btnOfferClose.getStyleClass().add("cut");
        btnOfferClose.setOnAction(e -> acceptOffer());
        Motion.pressable(btnOfferClose);
        Pane x = Icons.node(Icons.CLOSE, 14, TX2);   // the note's own x
        btnOfferDismiss.getChildren().add(x);
        Icons.animate(btnOfferDismiss, x);
        clickable(btnOfferDismiss, "Dismiss", this::dismissOffer);
        offerBar.maxWidthProperty().bind(ckOffer.widthProperty().multiply(offerLeft));
        offerBar.prefWidthProperty().bind(offerBar.maxWidthProperty());
        ckPower = Icons.node(Icons.POWER, 26, TX);
        ckAlert = Icons.node(Icons.CIRCLE_ALERT, 26, WARN);
    }

    /** "Checkpoint saved. Done for now?" in the note's place, for OFFER_SECONDS. Nothing to
        close, no offer; ignoring it closes nothing. */
    private void showOffer(Project projectObj) {
        int apps = closeCount(projectObj);
        if (apps == 0) return;
        offerFor = projectObj;
        offerWord.setText("Close " + apps + (apps == 1 ? " app" : " apps"));
        btnOfferClose.setAccessibleText(offerWord.getText());
        offerSub.setText(countLine(apps, countTabs(projectObj)) + " still open");
        // the exact box of what it stands in for: a two-line note's strip, or the 40 of "Add a note"
        Region spot = noteStrip.isVisible() ? noteStrip : btnAddNote;
        double h = spot.getHeight() > 0 ? spot.getHeight() : 40;
        ckOffer.setMinHeight(h);
        ckOffer.setPrefHeight(h);
        ckOffer.setMaxHeight(h);
        btnAddNote.setVisible(false);
        btnAddNote.setManaged(false);
        noteStrip.setVisible(false);
        noteStrip.setManaged(false);
        ckOffer.setVisible(true);
        ckOffer.setManaged(true);
        // rwCkIn: up 5px into place; motion off swaps in place and the countdown bar hides
        if (Motion.showing(ckOffer)) Motion.enter(ckOffer, 0, 5, 1, Motion.BASE, Motion.STANDARD);
        offerBar.setVisible(!Motion.REDUCED);
        offerLeft.set(1);
        if (offerTimer != null) offerTimer.stop();
        offerTimer = new Timeline();
        for (int s = 1; s <= OFFER_SECONDS; s++) {
            double left = 1 - s / (double) OFFER_SECONDS;   // 1s steps, not a smooth drain
            offerTimer.getKeyFrames().add(new KeyFrame(Duration.seconds(s), e -> offerLeft.set(left)));
        }
        offerTimer.setOnFinished(e -> dismissOffer());
        offerTimer.play();
    }

    /** x, Esc, the countdown, a new checkpoint, Resume or another project: the note comes back. */
    private void dismissOffer() {
        if (offerFor == null) return;
        offerFor = null;
        if (offerTimer != null) offerTimer.stop();
        ckOffer.setVisible(false);
        ckOffer.setManaged(false);
        showNoteStrip(activeProject != null && activeProject.note != null);
    }

    private void acceptOffer() {
        Project projectObj = offerFor;
        if (projectObj == null) return;
        dismissOffer();
        closeApps(projectObj, null);
    }

    /** Closes the project's apps and tabs, or only these (a retry, which closes no tabs), and
        watches them go. */
    private void closeApps(Project projectObj, List<String> only) {
        Set<String> apps = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        List<String> browsers = new ArrayList<>();
        if (only == null) {
            apps.addAll(appsToClose(projectObj.apps));
            closedApps.clear();
            closedBrowsers.clear();
            tabsToClose(projectObj).forEach((s, urls) -> {
                s.send(gson.toJson(Map.of("close", urls)));
                String exe = BROWSER_EXE.get(brandOf(wsBrowser(s)).toLowerCase());
                if (exe != null && !browsers.contains(exe)) browsers.add(exe);
                projectObj.urlsByBrowser.keySet().stream()
                        .filter(key -> sameBrowser(wsBrowser(s), key)).forEach(closedBrowsers::add);
            });
        } else {
            apps.addAll(only);
        }
        boolean folders = only == null && hasApp(projectObj, AppFolders.EXPLORER);
        Object watch = closeWatch = new Object();
        closeProj = projectObj;
        closeState = Close.CLOSING;
        closeDone = 0;
        closeTotal = apps.size() + browsers.size();
        survivors = List.of();
        // the browsers' tabs went with the message: their chips and tab groups go dark first
        cascade(watch, browsers);
        renderCkPanel();
        Thread worker = new Thread(() -> {
            try {
                powershell(closeScript(apps, folders)).waitFor(20, TimeUnit.SECONDS);
                long until = System.nanoTime() + CLOSE_WATCH_NANOS;
                List<String> before = null;
                boolean settled = false;
                while (closeWatch == watch && System.nanoTime() < until) {
                    Thread.sleep(CLOSE_POLL_MS);
                    List<String> open = stillOpen(apps, captureRunningApps());
                    // an app with nothing to save is gone within a look or two; a list that did
                    // not shrink since the last look is the apps waiting on the user.
                    // ponytail: an app slower than a look to quit is named, then drops off
                    settled = settled || open.isEmpty() || open.equals(before);
                    boolean done = settled;
                    Platform.runLater(() -> closeTick(watch, apps, open, done));
                    if (open.isEmpty()) return;
                    before = open;
                }
            } catch (IOException | InterruptedException ex) {
                System.err.println("close failed: " + ex.getMessage());
                Platform.runLater(() -> { if (closeWatch == watch) closeTick(watch, apps, new ArrayList<>(apps), true); });
            }
        }, "close");
        worker.setDaemon(true);
        worker.start();
    }

    /** One look at what is still open: the newly gone go dark; once settled, what is left is
        Unsaved work, and nothing left is Saved & closed. */
    private void closeTick(Object watch, Set<String> apps, List<String> open, boolean settled) {
        if (closeWatch != watch) return;
        List<String> gone = apps.stream()
                .filter(a -> !closedApps.contains(a) && open.stream().noneMatch(a::equalsIgnoreCase)).toList();
        Duration end = cascade(watch, gone);
        if (settled && !open.isEmpty()) {
            closeState = Close.PARTIAL;
            survivors = open;
            renderCkPanel();
        }
        if (settled && open.isEmpty()) {
            PauseTransition last = new PauseTransition(end.add(Duration.millis(120)));
            last.setOnFinished(e -> { if (closeWatch == watch) finishClose(); });
            last.play();
        }
    }

    /** Strikes these through one by one, CASCADE_STEP apart; returns when the last lands. */
    private Duration cascade(Object watch, List<String> exes) {
        Timeline t = new Timeline();
        Duration at = closeDone == 0 && closedApps.isEmpty() ? CASCADE_START : Duration.ZERO;
        for (String exe : exes) {
            Duration when = at;
            t.getKeyFrames().add(new KeyFrame(when, e -> {
                if (closeWatch != watch) return;
                closedApps.add(exe);
                closeDone++;
                renderCkPanel();
            }));
            at = at.add(CASCADE_STEP);
        }
        t.play();
        return at;
    }

    private void finishClose() {
        Project projectObj = closeProj;
        int apps = closedApps.size();   // a retry's too: everything this close put away
        closeWatch = null;
        closeState = Close.DONE;
        survivors = List.of();
        unsavedByProject.remove(projectObj.projectName);
        if (projectObj != activeProject) {
            endClose();
            return;
        }
        renderCkPanel();
        // the result lands on the block, as "Saved" does; the fill stays while it shows
        btnCheckpoint.pseudoClassStateChanged(PC_FILLED, true);
        ckWide(true);
        checkpointSaved("Saved & closed", countLine(apps, countTabs(projectObj)) + " · just now");
        PauseTransition back = new PauseTransition(Duration.millis(2400));
        back.setOnFinished(e -> {
            btnCheckpoint.pseudoClassStateChanged(PC_FILLED, false);
            if (closeState == Close.DONE) endClose();
            renderCkPanel();
        });
        back.play();
    }

    /** Resume, leaving the project, or Saved & closed running out: the close is over. One
        still closing keeps going; the block shows it again on the way back. */
    private void endClose() {
        if (closeState == Close.CLOSING) return;
        closeWatch = null;
        closeState = Close.IDLE;
        closeProj = null;
        survivors = List.of();
        closedApps.clear();
        closedBrowsers.clear();
    }

    /** The block's own words for where it is: Closing · n of N apps; Unsaved work, what holds
        it and "Save, then click again"; or Checkpoint and its age. */
    private void renderCkPanel() {
        Project projectObj = activeProject;
        if (projectObj == null || ckPower == null) return;
        boolean ours = closeProj == projectObj;
        boolean closingNow = ours && closeState == Close.CLOSING;
        List<String> warn = checkpointing == projectObj || closingNow ? List.of()
                : ours && closeState == Close.PARTIAL ? survivors
                : unsavedByProject.getOrDefault(projectObj.projectName, List.of());
        boolean unsaved = !warn.isEmpty();
        Node glyph = closingNow ? ckPower : unsaved ? ckAlert : ckBookmark;
        if (ckGlyphBox.getChildren().size() != 1 || ckGlyphBox.getChildren().get(0) != glyph) {
            ckGlyphBox.getChildren().setAll(glyph);
        }
        ckWord.setText(closingNow ? "Closing" : unsaved ? "Unsaved work" : "Checkpoint");
        ckHint.setVisible(unsaved);
        ckHint.setManaged(unsaved);
        checkpointSub.setText(closingNow ? closeDone + " of " + closeTotal + " apps"
                : unsaved ? String.join(" · ", warn.stream().map(HelloController::appName).toList())
                : idleLine(projectObj));
        checkpointSub.pseudoClassStateChanged(PC_WARN, unsaved);
        btnCheckpoint.pseudoClassStateChanged(PC_CLOSING, closingNow);
        ckWide(closingNow || unsaved);
        markClosed(projectObj);
    }

    /** Closing and Unsaved work need the whole block for their words: the add-modes chevron
        steps aside (right-click still opens its menu). */
    private void ckWide(boolean wide) {
        btnCheckpointMenu.setVisible(!wide);
        Node divider = btnCheckpoint.lookup(".ck-divider");
        if (divider != null) divider.setVisible(!wide);
        Insets pad = new Insets(0, wide ? 23 : 69, 0, 23);
        ckIdle.setPadding(pad);
        ckDone.setPadding(pad);
    }

    /** Struck-through chips for what closed, mustard for what holds unsaved work, and dimmed
        tab groups for the browsers that closed their tabs. Re-run on every render of the card. */
    private void markClosed(Project projectObj) {
        boolean ours = closeProj == projectObj && closeState != Close.IDLE;
        List<String> stuck = ours && closeState == Close.PARTIAL ? survivors
                : unsavedByProject.getOrDefault(projectObj.projectName, List.of());
        for (Node chip : categoryRows.lookupAll(".chip")) {
            Object exe = chip.getProperties().get("exe");
            if (!(exe instanceof String app)) continue;
            chip.pseudoClassStateChanged(PC_CLOSED, ours && closedApps.contains(app));
            chip.pseudoClassStateChanged(PC_STUCK, stuck.stream().anyMatch(app::equalsIgnoreCase)
                    && !(ours && closedApps.contains(app)));
        }
        for (Node row : tabsList.getChildren()) {
            Object browser = row.getProperties().get("browser");
            double target = ours && browser != null && closedBrowsers.contains(browser) ? .35 : 1;
            if (row.getOpacity() == target) continue;
            if (Motion.REDUCED || !Motion.showing(row)) row.setOpacity(target);
            else new Timeline(new KeyFrame(Motion.BASE, new KeyValue(row.opacityProperty(), target, Motion.STANDARD))).play();
        }
    }

    /** The generic confirm scrim. The verb button carries the caller's word and action, in the
        neutral bone: a Retry that rewrites a note is not a checkpoint. */
    private void confirm(String title, String sub, String body, String verb, Runnable onOk) {
        confirm(title, sub, body, verb, "btn-primary", onOk);
    }

    /** style: "btn-checkpoint" when the verb checkpoints, "btn-danger" when it deletes. */
    private void confirm(String title, String sub, String body, String verb, String style, Runnable onOk) {
        confirmTitle.setText(title);
        confirmSub.setText(sub);
        confirmBody.setText(body);
        btnConfirmOk.setText(verb);
        styleAs(btnConfirmOk, style);
        btnConfirmOk.setOnAction(e -> {
            showOverlay(null);
            onOk.run();
        });
        showOverlay(confirmPane);
    }

    /** Swaps a main button between bone, checkpoint blue and brick, keeping its other classes. */
    private static void styleAs(Button b, String style) {
        b.getStyleClass().removeAll("btn-primary", "btn-checkpoint", "btn-danger");
        b.getStyleClass().add(style);
    }

    private void deleteActive() {
        if (activeProject == null) return;
        Project projectObj = activeProject;
        try {
            // get, not remove: a failed write must leave the row findable for Retry
            deleteProject(projectObj, rowsByProject.get(projectObj.projectName));
        } catch (IOException ex) {
            confirm("Project not deleted", projectsFile().getPath(),
                    "Nothing changed on disk. " + ex.getMessage(), "Retry", "btn-danger", this::deleteActive);
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
            statusTip.setText(live ? String.join(", ", browsers.stream().map(HelloController::browserLabel).toList())
                    : connecting ? "waiting for the coldstart extension \u00b7 can take a minute to reconnect"
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
        return IGNORED_WINDOWS.contains(lower) || lower.startsWith("powertoys.") || hidden.contains(lower);
    }

    // == Apps the user sorted out of Other ==================================
    //   + on an Other chip relaunches that app from then on, in every project, under "Apps";
    //   x hides it: it is never captured again. Both are the user's, not a project's, so they
    //   live in PREFS (lowercase exe names), loaded by initialize() - empty in tests.
    static final String USER_APPS = "Apps";
    private static final String RELAUNCH_KEY = "relaunchApps", HIDDEN_KEY = "hiddenApps";
    // read by the capture worker while the FX thread edits them
    static final Set<String> relaunched = ConcurrentHashMap.newKeySet(), hidden = ConcurrentHashMap.newKeySet();

    private static void loadUserApps() {
        for (String exe : PREFS.get(RELAUNCH_KEY, "").split("\n")) if (!exe.isBlank()) relaunched.add(exe);
        for (String exe : PREFS.get(HIDDEN_KEY, "").split("\n")) if (!exe.isBlank()) hidden.add(exe);
    }

    private static void storeUserApps() {
        PREFS.put(RELAUNCH_KEY, String.join("\n", relaunched));
        PREFS.put(HIDDEN_KEY, String.join("\n", hidden));
    }

    /** + on an Other chip. Every open project that captured it moves it to Apps now; the ones
        on disk do when they load (recategorizeOther). */
    private void relaunchApp(String app) {
        relaunched.add(app.toLowerCase());
        storeUserApps();
        projects.forEach(HelloController::recategorizeOther);
    }

    /** − on an Apps chip: the + taken back. Every open project moves it to Other again. */
    private void unrelaunchApp(String app) {
        relaunched.remove(app.toLowerCase());
        storeUserApps();
        projects.forEach(HelloController::recategorizeOther);
    }

    /** x on an Other chip: gone from this card now, never captured again. Other projects that
        still list it stop showing it (renderCategories). */
    private void hideApp(Project projectObj, String app) {
        hidden.add(app.toLowerCase());
        storeUserApps();
        removeApp(projectObj, OTHER, app);
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

    /** exe -> Store app id, for packaged apps `start` cannot find (no PATH alias, no App Paths).
        ponytail: one entry per app; look the id up with Get-StartApps if more turn up. */
    static final Map<String, String> STORE_APPS = Map.of(
            "mscopilot.exe", "Microsoft.Copilot_8wekyb3d8bbwe!App");

    // purely for labelling: an app missing from this map is still captured, under OTHER
    private static final Map<String, List<String>> KNOWN_APPS = Map.ofEntries(
            Map.entry("Browser", List.of("chrome.exe", "brave.exe", "firefox.exe", "msedge.exe",
                    "opera.exe", "vivaldi.exe", "arc.exe", "zen.exe", "librewolf.exe", "chromium.exe")),
            Map.entry("IDE", List.of("Code.exe", "Code - Insiders.exe", "cursor.exe", "windsurf.exe",
                    "zed.exe", "trae.exe", "fleet.exe", "idea64.exe", "devenv.exe", "eclipse.exe",
                    "sublime_text.exe", "notepad++.exe", "pycharm64.exe", "webstorm64.exe",
                    "phpstorm64.exe", "clion64.exe", "rider64.exe", "goland64.exe", "rubymine64.exe",
                    "studio64.exe", "netbeans64.exe", "qtcreator.exe", "gvim.exe", "emacs.exe",
                    "Kiro.exe", "Antigravity.exe", "Positron.exe", "dataspell64.exe", "neovide.exe",
                    "rstudio.exe", "MATLAB.exe", "Arduino IDE.exe", "Unity.exe", "Unity Hub.exe",
                    "UnrealEditor.exe")),
            // powershell/cmd stay Other on purpose: with claude in a conhost window they'd come
            // back as an empty shell beside claude, the case the wt special-case already avoids
            Map.entry("Terminal", List.of("WindowsTerminal.exe", "alacritty.exe", "wezterm-gui.exe",
                    "Hyper.exe", "ConEmu64.exe", "mintty.exe", "Tabby.exe", "warp.exe")),
            Map.entry("AI", List.of("claude.exe", "ChatGPT.exe", "Perplexity.exe", "LM Studio.exe",
                    "ollama app.exe", "Copilot.exe", "mscopilot.exe", "Msty.exe", "Jan.exe", "AnythingLLM.exe",
                    "Cherry Studio.exe")),
            Map.entry("Database", List.of("dbeaver.exe", "datagrip64.exe", "pgAdmin4.exe", "Ssms.exe",
                    "MySQLWorkbench.exe", "TablePlus.exe", "MongoDBCompass.exe", "redisinsight.exe",
                    "HeidiSQL.exe", "azuredatastudio.exe", "Beekeeper Studio.exe", "SQLiteStudio.exe",
                    "DB Browser for SQLite.exe", "navicat.exe")),
            Map.entry("API", List.of("Postman.exe", "Insomnia.exe", "Bruno.exe", "Hoppscotch.exe",
                    "Yaak.exe", "Fiddler Everywhere.exe", "Wireshark.exe")),
            Map.entry("Containers", List.of("Docker Desktop.exe", "Rancher Desktop.exe",
                    "podman-desktop.exe", "VirtualBox.exe", "vmware.exe", "Lens.exe", "OpenLens.exe")),
            Map.entry("VersionControl", List.of("GitHubDesktop.exe", "SourceTree.exe", "gitkraken.exe",
                    "Fork.exe", "smartgit.exe", "sublime_merge.exe", "GitExtensions.exe")),
            Map.entry("Design", List.of("Figma.exe", "Photoshop.exe", "Illustrator.exe", "blender.exe",
                    "inkscape.exe", "krita.exe", "Canva.exe", "InDesign.exe", "Lightroom.exe",
                    "mspaint.exe", "PaintDotNet.exe", "XD.exe", "draw.io.exe", "Lunacy.exe", "Affinity.exe")),
            Map.entry("Video", List.of("Adobe Premiere Pro.exe", "AfterFX.exe", "Resolve.exe", "CapCut.exe",
                    "Adobe Media Encoder.exe", "shotcut.exe", "kdenlive.exe", "obs64.exe",
                    "CamtasiaStudio.exe", "HandBrake.exe", "Descript.exe")),
            Map.entry("Office", List.of("WINWORD.exe", "EXCEL.exe", "POWERPNT.exe", "MSACCESS.exe",
                    "VISIO.exe", "MSPUB.exe", "WINPROJ.exe", "Acrobat.exe", "AcroRd32.exe",
                    "SumatraPDF.exe", "FoxitPDFReader.exe", "wps.exe", "et.exe", "wpp.exe")),
            Map.entry("Communication", List.of("discord.exe", "slack.exe", "teams.exe", "ms-teams.exe",
                    "Zoom.exe", "Telegram.exe", "WhatsApp.exe", "Signal.exe", "Skype.exe", "Element.exe",
                    "OUTLOOK.exe", "olk.exe", "thunderbird.exe", "Mailspring.exe", "Mattermost.exe",
                    "Viber.exe", "Messenger.exe", "Zulip.exe", "Beeper.exe")),
            Map.entry("Notes", List.of("notion.exe", "obsidian.exe", "onenote.exe", "Logseq.exe",
                    "Typora.exe", "Joplin.exe", "anytype.exe", "notepad.exe", "Evernote.exe",
                    "Zettlr.exe", "Todoist.exe", "AFFiNE.exe", "xournalpp.exe")),
            // only its folder windows count (AppFolders); with none saved it is always running,
            // so resume never starts a bare one
            Map.entry("Files", List.of("explorer.exe")),
            Map.entry("Music", List.of("spotify.exe", "amazonmusic.exe", "AppleMusic.exe", "Tidal.exe",
                    "MusicBee.exe", "foobar2000.exe", "vlc.exe", "iTunes.exe", "Deezer.exe", "AIMP.exe",
                    "Audacity.exe", "reaper.exe", "FL64.exe"))
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
        result.put(USER_APPS, new HashSet<>());

        for (String app : appset) {
            result.get(categoryOf(app)).add(app);
        }
        for (String item : result.keySet()) {
            System.out.println(item + " -> " + result.get(item));
        }
        return result;
    }

    /** Apps saved under Other before KNOWN_APPS learned them (mspaint, Word, ...) move to their
        category, or they'd never relaunch and an add-mode checkpoint would list them twice.
        Same for Apps both ways: one the user just added leaves Other, one they stopped
        relaunching goes back to it. */
    static void recategorizeOther(Project projectObj) {
        if (projectObj.apps == null) return;
        for (String from : List.of(OTHER, USER_APPS)) {
            HashSet<String> bucket = projectObj.apps.get(from);
            if (bucket == null) continue;
            bucket.removeIf(app -> {
                String category = categoryOf(app);
                if (category.equals(from)) return false;
                projectObj.apps.computeIfAbsent(category, k -> new HashSet<>()).add(app);
                return true;
            });
        }
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
        // a known category wins, so an app that KNOWN_APPS learns later gets its real row
        return relaunched.contains(app.toLowerCase()) ? USER_APPS : OTHER;
    }
    /** Writes the project to projects.json (replacing any entry with the same name). Both the
        in-memory list and the on-disk list are updated. It does not make the project active:
        tabs can now land on a project that is not on screen. Synchronized with deleteProject:
        two browsers answering one checkpoint post /tabs at once, and their overlapping read
        and swap of projects.json failed with "being used by another process". */
    public synchronized void saveProject(Project projectObj) throws IOException {

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
        try {
            try (Writer writer = new FileWriter(temp, false)) {
                gson.toJson(list, writer);
            }
            Files.move(temp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            // every caller shows its own dialog (or swallows a Retry); this is the one place they all pass
            System.err.println("could not write projects.json: " + ex);
            throw ex;
        }
    }

    public synchronized void deleteProject(Project projectObj, Node projectRow) throws IOException {
        removeByName(projects, projectObj.projectName);
        // an unsaved note edit would otherwise write the deleted project back
        if (noteOwner == projectObj) noteOwner = null;

        ArrayList<Project> onDisk = getProjectsJson();
        removeByName(onDisk, projectObj.projectName);
        writeProjects(onDisk);

        // a deleted project must not stay the target of an incoming /tabs post
        if (activeProject != null && activeProject.projectName.equals(projectObj.projectName)) {
            activeProject = null;
        }
        if (tabTarget != null && tabTarget.projectName.equals(projectObj.projectName)) dropIncomingTabs(projectObj);
        owedTabs.values().removeIf(o -> o.project().projectName.equals(projectObj.projectName));
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
    //                    2. `start` every other captured app, with special cases for
    //                       claude (agent view of the project folder), VS Code (project folder), wt
    //                    3. last, push {"open":[urls]} to each browser's connected extension,
    //                       which opens only the missing tabs, one at a time. A profile already
    //                       open whose extension has not connected yet is never started again -
    //                       its tabs wait for the extension. A closed one is started on the first
    //                       url and handed the rest when its extension connects - or all at once
    //                       after PENDING_OPEN_MS

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
    // this folder (that is not a background one) means its window is already up, so opening
    // another would only duplicate it.
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
                // a background session ("kind":"bg") outlives the terminal it started in and has
                // no window, so a leftover one from yesterday must not stand in for an open claude
                if ("bg".equals(session.get("kind"))) continue;
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
        String brand = browser == null || browser.isEmpty() ? "unknown browser" : browser;
        return browserKey(brand, profilePart(brand, ctx.queryParam("profile"), ctx.queryParam("pid")));
    }

    // each profile runs its own extension, so with the brand alone two chrome profiles
    // overwrote each other's tabs. a profile rides along as "Chrome · me@x.com" when the
    // browser gives its email, else by its name, "Chrome · Yash" (profilePart). Only one the
    // app cannot tell (no email, no id it can find) stays plain "Chrome".
    static final String PROFILE_SEP = " · ";

    /** The profile half of a bucket key: the email when the browser gives one; else the name of
        the profile folder holding the extension's id (pidDir) - a profile can be signed in
        and still hand an extension no email; else null, plain "Chrome". */
    static String profilePart(String brand, String email, String pid) {
        if (email != null && !email.isEmpty()) return email;
        String data = USER_DATA.get(brand.toLowerCase());
        if (data == null) return null;
        File userData = new File(System.getenv("LOCALAPPDATA"), data);
        String dir = pidDir(userData, pid);
        if (dir == null) return null;
        String name = nameOf(profileEntry(new File(userData, "Local State"), dir));
        return name != null ? name : dir;
    }

    /** pid -> profile folder; "" when no folder held it. */
    private static final Map<String, String> PID_DIRS = new ConcurrentHashMap<>();

    /** The profile folder ("Default", "Profile 1") whose extension storage holds this id. The
        extension writes it once to chrome.storage.local, which every profile keeps apart under
        <folder>\Local Extension Settings\<extension>\, a LevelDB whose fresh writes sit as plain
        text in its .log; a random id stays a literal when compacted into .ldb.
        ponytail: a byte search of those files, remembered per id, misses included; read the
        LevelDB properly if an id ever goes unfound. */
    static String pidDir(File userData, String pid) {
        if (pid == null || !pid.matches("[0-9a-fA-F-]{36}")) return null;
        String known = PID_DIRS.get(pid);
        if (known != null) return known.isEmpty() ? null : known;
        byte[] needle = pid.getBytes(StandardCharsets.US_ASCII);
        String found = "";
        File[] profiles = userData.listFiles(File::isDirectory);
        search:
        for (File profile : profiles == null ? new File[0] : profiles) {
            File[] stores = new File(profile, "Local Extension Settings").listFiles(File::isDirectory);
            for (File store : stores == null ? new File[0] : stores) {
                File[] files = store.listFiles((d, n) -> n.endsWith(".log") || n.endsWith(".ldb"));
                for (File f : files == null ? new File[0] : files) {
                    try {
                        if (f.length() < 8_000_000 && indexOf(Files.readAllBytes(f.toPath()), needle) >= 0) {
                            found = profile.getName();
                            break search;
                        }
                    } catch (IOException ex) {
                        // a file the browser holds or just compacted away: the next one may have it
                    }
                }
            }
        }
        PID_DIRS.put(pid, found);
        return found.isEmpty() ? null : found;
    }

    static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    static String browserKey(String browser, String profile) {
        return profile == null || profile.isEmpty() ? browser : browser + PROFILE_SEP + profile;
    }

    static String brandOf(String key) {
        int i = key.indexOf(PROFILE_SEP);
        return i < 0 ? key : key.substring(0, i);
    }

    static String profileOf(String key) {
        int i = key.indexOf(PROFILE_SEP);
        return i < 0 ? null : key.substring(i + PROFILE_SEP.length());
    }

    /** Whether a connected extension is the one a saved bucket belongs to. A bucket with no
        profile (saved before the extension sent one) goes to any profile of its brand, as before. */
    static boolean sameBrowser(String connected, String saved) {
        return connected.equalsIgnoreCase(saved)
                || profileOf(saved) == null && brandOf(connected).equalsIgnoreCase(saved);
    }

    // where each brand keeps its profiles under %LOCALAPPDATA%; opera has no profile folders
    static final Map<String, String> USER_DATA = Map.of(
            "chrome", "Google\\Chrome\\User Data",
            "edge", "Microsoft\\Edge\\User Data",
            "brave", "BraveSoftware\\Brave-Browser\\User Data");

    /** The profile folder ("Profile 2") a bucket's email is signed in to; null opens the
        browser on whichever profile it picks, as before. */
    static String profileDir(String key) {
        Map.Entry<String, Map<String, Object>> p = profileEntry(key);
        return p == null ? null : p.getKey();
    }

    static String profileDir(File localState, String email) {
        Map.Entry<String, Map<String, Object>> p = profileEntry(localState, email);
        return p == null ? null : p.getKey();
    }

    /** The name the profile goes by in the browser ("Work", "Yash"); null when unknown, so the
        email shows instead. */
    static String profileName(File localState, String email) {
        return nameOf(profileEntry(localState, email));
    }

    private static String nameOf(Map.Entry<String, Map<String, Object>> p) {
        Object name = p == null ? null : p.getValue().get("name");
        return name instanceof String s && !s.isBlank() ? s : null;
    }

    private static Map.Entry<String, Map<String, Object>> profileEntry(String key) {
        String who = profileOf(key), data = USER_DATA.get(brandOf(key).toLowerCase());
        if (who == null || data == null) return null;
        return profileEntry(new File(System.getenv("LOCALAPPDATA"), data + "\\Local State"), who);
    }

    /** Local State's profile.info_cache maps each folder to its profile: user_name the email,
        name what the browser calls it. who is any of the three, as profilePart keys them.
        ponytail: two profiles with one name - the first wins */
    private static Map.Entry<String, Map<String, Object>> profileEntry(File localState, String who) {
        Map<String, Map<String, Object>> profiles = profiles(localState);
        for (Map.Entry<String, Map<String, Object>> p : profiles.entrySet()) {
            if (who.equalsIgnoreCase(String.valueOf(p.getValue().get("user_name")))) return p;
        }
        for (Map.Entry<String, Map<String, Object>> p : profiles.entrySet()) {
            if (who.equals(p.getKey()) || who.equals(p.getValue().get("name"))) return p;
        }
        return null;
    }

    /** folder -> profile, empty when there is no Local State or it has a shape we do not know
        (the browser then picks the profile itself). */
    private static Map<String, Map<String, Object>> profiles(File localState) {
        try (Reader r = new FileReader(localState)) {
            Map<String, Object> state = new Gson().fromJson(r, Map.class);
            Map<String, Map<String, Object>> profiles =
                    (Map<String, Map<String, Object>>) ((Map<String, Object>) state.get("profile")).get("info_cache");
            if (profiles != null) return profiles;
        } catch (IOException | RuntimeException ex) {
            // fall through
        }
        return Map.of();
    }

    /** How many profiles the brand has; 1 when it keeps none we can read. */
    static int profileCount(String brand) {
        String data = USER_DATA.get(brand.toLowerCase());
        if (data == null) return 1;
        return Math.max(1, profiles(new File(System.getenv("LOCALAPPDATA"), data + "\\Local State")).size());
    }

    /** One profile's window of tabs: every url its own argument with no shell in between, as cmd
        would split a query string on its & (java only quotes args containing spaces). */
    static List<String> browserCommand(String exePath, String profileDir, List<String> urls) {
        List<String> cmd = new ArrayList<>(List.of(exePath));
        if (profileDir != null) cmd.add("--profile-directory=" + profileDir);
        cmd.addAll(urls);
        return cmd;
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
        String exe = browser == null ? null : BROWSER_EXE.get(brandOf(browser).toLowerCase());
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
    interface Launch { void start() throws IOException, URISyntaxException, AlreadyOpen, Queued; }

    /** A launch that found nothing to do: every saved tab was already open in that browser. */
    static final class AlreadyOpen extends Exception {
        AlreadyOpen() { super(null, null, false, false); }
    }

    /** A launch handed to an extension that has not connected yet: it opens them when it does. */
    static final class Queued extends Exception {
        Queued() { super(null, null, false, false); }
    }

    /** One process launch: the row's label, the command it shows, and how it went. */
    static final class LaunchStep {
        /** SKIPPED: ran, but there was nothing to open - shown as already open, not launched */
        enum State { QUEUED, LAUNCHING, DONE, SKIPPED, FAILED }
        final String label, command;
        final boolean browser;
        final Launch launch;
        volatile State state = State.QUEUED;
        volatile String error;
        /** DONE, but its tabs are still waiting for the browser's extension to connect */
        volatile boolean waiting;
        /** a browser's tabs its extension says it opened; -1 = no count (command line, an older
            extension), so every saved url counts */
        volatile int opened = -1;

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
            } catch (AlreadyOpen ex) {
                state = State.SKIPPED;
            } catch (Queued ex) {
                waiting = true;
                state = State.DONE;
            } catch (IOException | URISyntaxException ex) {
                error = ex.getMessage();
                state = State.FAILED;
                System.err.println("failed to launch " + label + ": " + error);
            }
        }

        String logLine() {
            return switch (state) {
                case DONE -> waiting ? label + ": tabs wait for its extension to connect" : label + " → " + command;
                case SKIPPED -> label + ": every tab already open";
                default -> label + " failed: " + error;
            };
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
        /** finished fine, including a browser that had every tab open already */
        long done()     { return plan.steps().stream().filter(s -> s.state == LaunchStep.State.DONE || s.state == LaunchStep.State.SKIPPED).count(); }
        long failed()   { return plan.steps().stream().filter(s -> s.state == LaunchStep.State.FAILED).count(); }
        long queued()   { return plan.steps().stream().filter(s -> s.state == LaunchStep.State.QUEUED).count(); }
        long appsDone() { return plan.steps().stream().filter(s -> s.state == LaunchStep.State.DONE && !s.browser).count(); }

        /** Left alone: apps the plan found running, plus browsers that turned out to have every tab. */
        List<String> alreadyOpen() {
            List<String> all = new ArrayList<>(plan.skipped());
            plan.steps().stream().filter(s -> s.state == LaunchStep.State.SKIPPED).forEach(s -> all.add(s.label));
            return all;
        }
    }

    /** Brings a saved project back: browsers with their tabs, then every other captured app.
        Synchronous - the HUD's Resume runs the same plan staggered on its own thread. */
    public void resumeProject(Project projectObj) throws IOException {
        for (LaunchStep step : planResume(projectObj, captureRunningApps(), liveFolders(projectObj), new ArrayList<>()).steps()) {
            step.run();
        }
    }

    /** What the project's saved IDEs have open right now, so resume skips only those folders.
        Empty when no folder was saved or the listing failed: asking VS Code or a JetBrains IDE
        for a folder it already has open just brings that window forward. */
    static Map<String, ArrayList<String>> liveFolders(Project projectObj) {
        if (projectObj.folders == null || projectObj.folders.isEmpty()) return Map.of();
        try {
            return AppFolders.capture().folders();
        } catch (IOException ex) {
            return Map.of();
        }
    }

    ResumePlan planResume(Project projectObj, Set<String> running, List<String> log) {
        return planResume(projectObj, running, Map.of(), log);
    }

    /** Decides what to launch, in order, without starting anything. */
    ResumePlan planResume(Project projectObj, Set<String> running,
                          Map<String, ArrayList<String>> openFolders, List<String> log) {
        //TODO:add multiple apps checking for path list
        System.out.println("projectName:" + projectObj.projectName);
        migrateLegacyUrls(projectObj);
        boolean claudeInTerminal = hasApp(projectObj, "claude.exe") && hasApp(projectObj, "WindowsTerminal.exe");
        List<LaunchStep> steps = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        // browsers go last: the apps launching first give an extension that has not reconnected
        // to a just-started coldstart time to, and only an extension can skip the open tabs
        List<LaunchStep> browserSteps = new ArrayList<>();
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
            // a profile already open must never be started again: its open tabs would come back
            // twice. Decided now, from the windows the capture saw; the extension is looked for
            // when the step runs, since it may connect while the apps before it launch
            boolean open = exePath != null && profileOpen(browser, new File(exePath).getName(), running);
            String command = urls.size() + " urls · " + (open || liveSession(browser) != null ? "via extension, open tabs skipped"
                    : exePath == null ? "default browser" : "starting the browser");
            LaunchStep[] self = new LaunchStep[1];   // the launch reports the opened count back onto its step
            browserSteps.add(self[0] = new LaunchStep(browser, command, true, () -> {
                WsContext live = awaitExtension(browser, open);
                if (live != null) {
                    CompletableFuture<Integer> reply = new CompletableFuture<>();
                    openReplies.put(live, reply);
                    live.send(gson.toJson(Map.of("open", urls)));
                    // every tab already there means nothing launched; no answer (an older
                    // extension) still counts as sent, as it always did
                    int opened = reply.completeOnTimeout(-1, OPEN_REPLY_MS, TimeUnit.MILLISECONDS).join();
                    openReplies.remove(live, reply);
                    if (opened == 0) throw new AlreadyOpen();
                    self[0].opened = opened;
                    return;
                }
                if (open) {
                    // its extension takes every tab the moment it connects and skips the open
                    // ones; there is no command-line fallback, which would duplicate them
                    pendingOpens.put(browser, List.copyOf(urls));
                    throw new Queued();
                }
                if (exePath == null) {
                    // unknown brand, browser not installed, or urls saved before we tracked the
                    // browser: fall back to whatever windows considers the default
                    for (String url : urls) {
                        Desktop.getDesktop().browse(new URI(url));
                    }
                } else {
                    // only the first tab goes on the command line, in the profile it was saved
                    // from; the rest wait in pendingOpens for this browser's extension to connect
                    String dir = profileDir(browser);
                    new ProcessBuilder(browserCommand(exePath, dir, urls.subList(0, Math.min(1, urls.size())))).start();
                    if (urls.size() > 1) {
                        List<String> rest = List.copyOf(urls.subList(1, urls.size()));
                        pendingOpens.put(browser, rest);
                        CompletableFuture.delayedExecutor(PENDING_OPEN_MS, TimeUnit.MILLISECONDS).execute(() -> {
                            if (!pendingOpens.remove(browser, rest)) return;   // the extension took them
                            try {
                                new ProcessBuilder(browserCommand(exePath, dir, rest)).start();
                            } catch (IOException ex) {
                                System.err.println("failed to open the rest of " + browser + ": " + ex.getMessage());
                            }
                        });
                    }
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
                List<String> dirs = projectObj.folders == null ? null : projectObj.folders.get(app);
                if (dirs != null && !dirs.isEmpty()) {
                    // one window per saved folder: the app being up only rules out the folders
                    // it already has open, not the rest
                    List<String> open = openFolders.getOrDefault(app, new ArrayList<>());
                    boolean any = false;
                    for (String dir : dirs) {
                        if (open.stream().anyMatch(dir::equalsIgnoreCase)) {
                            log.add("skipping " + app + ": already open on " + dir);
                            continue;
                        }
                        steps.add(folderStep(app, dir, projectObj.exePaths == null ? null : projectObj.exePaths.get(app)));
                        any = true;
                    }
                    if (!any) skipped.add(app);
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

                } else if (STORE_APPS.containsKey(app.toLowerCase())) {
                    // a Store app with no exe on PATH or in App Paths: started by its app id,
                    // the way the Start menu does it
                    String id = STORE_APPS.get(app.toLowerCase());
                    steps.add(new LaunchStep(appName(app), "start " + appName(app), false, () ->
                            new ProcessBuilder("explorer.exe", "shell:AppsFolder\\" + id).start()));

                } else {
                    // no folder needed: this used to sit under the folderPath guard too, so a
                    // project without a folder relaunched nothing at all
                    // `start` reports nothing back when windows cannot find the exe - it shows its
                    // own dialog and the step would read as launched - so look first, the way
                    // start does: PATH, then the App Paths keys
                    String exePath = projectObj.exePaths == null ? null : projectObj.exePaths.get(app);
                    steps.add(new LaunchStep(appName(app), "start " + appName, false, () -> {
                        if (!onPath(app, System.getenv("PATH")) && appPath(app) == null) {
                            // not findable by name (most apps moved out of Other): the exe the
                            // last checkpoint saw it run from
                            if (exePath == null || !new File(exePath).isFile()) throw new IOException("cannot find " + app);
                            new ProcessBuilder(exePath).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                            return;
                        }
                        // the "" is start's window title: without it, a name with a space
                        // ("Adobe Premiere Pro") arrives quoted and start takes it as the title,
                        // opening an empty console instead of the app
                        new ProcessBuilder("cmd.exe", "/c", "start", "", appName).start();
                    }));
                }
            }
        }
        steps.addAll(browserSteps);
        return new ResumePlan(steps, skipped);
    }

    /** The connected extension a bucket's tabs go to: its own profile before any other of its brand. */
    private WsContext liveSession(String browser) {
        return wsSessions.stream()
                .filter(s -> sameBrowser(wsBrowser(s), browser))
                .min(Comparator.comparing(s -> !wsBrowser(s).equalsIgnoreCase(browser))).orElse(null);
    }

    /** The bucket's extension, waited for only when its profile is open and coldstart started
        so recently that the extension's 3s redial has not come round yet - after that a missing
        extension is asleep (it wakes on a 30s alarm) and waiting would only hold the resume. */
    private WsContext awaitExtension(String browser, boolean open) {
        long due = serverStartedNanos + TimeUnit.MILLISECONDS.toNanos(EXT_DIAL_MS);
        WsContext live = liveSession(browser);
        while (live == null && open && serverStartedNanos != 0 && !stopRequested && System.nanoTime() < due) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
            live = liveSession(browser);
        }
        return live;
    }

    /** Whether the bucket's profile has a window open now: the browser is running and, when the
        bucket names a profile, Local State lists that profile's folder as active.
        ponytail: Local State is written a few seconds behind, so a profile opened just before
        Resume can read as closed and be started once more. */
    static boolean profileOpen(String browser, String exe, Set<String> running) {
        if (running.stream().noneMatch(exe::equalsIgnoreCase)) return false;
        String data = USER_DATA.get(brandOf(browser).toLowerCase()), dir = profileDir(browser);
        // a bucket with no profile, or a brand without profile folders: the browser being up is
        // all there is to go on, and starting it again is what duplicates
        if (data == null || dir == null) return true;
        return activeProfiles(new File(System.getenv("LOCALAPPDATA"), data + "\\Local State")).contains(dir);
    }

    /** Local State's profile.last_active_profiles: the folders with a window open while the
        browser runs. Empty when unreadable. */
    static List<String> activeProfiles(File localState) {
        try (Reader r = new FileReader(localState)) {
            Map<String, Object> state = new Gson().fromJson(r, Map.class);
            Object active = ((Map<String, Object>) state.get("profile")).get("last_active_profiles");
            if (active instanceof List<?> l) return l.stream().map(String::valueOf).toList();
        } catch (IOException | RuntimeException ex) {
            // fall through
        }
        return List.of();
    }

    /** Opens one saved folder or file in the app that had it: a VS Code build through its own
        command, anything else from the exe it ran from last time (the same Unity version, a
        Blender that is not on PATH), or else through `start` like any other app. */
    static LaunchStep folderStep(String app, String dir, String exePath) {
        String label = appName(app) + " · " + new File(dir).getName();
        AppFolders.VsCode vs = AppFolders.VSCODE_FAMILY.get(app.toLowerCase());
        String name = vs != null ? vs.cli() : app.replaceFirst("(?i)\\.exe$", "");
        List<String> args = AppFolders.openArgs(app, dir);
        return new LaunchStep(label, name + " " + String.join(" ", args), false, () -> {
            // moved or deleted since: VS Code would open an empty window and read as restored
            if (!new File(dir).exists()) throw new IOException("no longer there: " + dir);
            List<String> cmd = new ArrayList<>();
            if (vs != null) {
                cmd.addAll(List.of("cmd.exe", "/c", vs.cli()));
            } else if (exePath != null && new File(exePath).isFile()) {
                cmd.add(exePath);
            } else if (onPath(app, System.getenv("PATH")) || appPath(app) != null) {
                cmd.addAll(List.of("cmd.exe", "/c", "start", "", name));
            } else {
                throw new IOException("cannot find " + app);
            }
            cmd.addAll(args);
            // nothing reads the child's output, and a chatty one (Blender logs to stdout)
            // would stall on a full pipe
            new ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        });
    }

    // == Window placement ====================================================
    //   A launched app opens wherever it likes (Chrome came back half-size), so a script runs
    //   beside the resume: it notes the windows already open, then gives each new window of a
    //   saved app the next place the checkpoint saw that app's windows in - maximized on the
    //   same monitor, or at the same position and size. Windows that were open before are
    //   never moved.

    /** How long the script waits for windows to appear: IntelliJ can take this long to load. */
    static final int PLACE_SECONDS = 90;

    /** The script for placeWindows. Prints "ready" once it knows which windows were open.
        Places that are not AppFolders.PLACE (a hand-edited projects.json) are dropped here,
        as they are pasted into the script.
        ponytail: an app's windows take its places in capture order, so two VS Code windows
        can come back swapped; matching on the folder in the title would fix that.
        ponytail: UI Automation coordinates in a DPI-unaware PowerShell, read and written the
        same way; monitors at different scales may land a little off. */
    static String placeScript(Map<String, ArrayList<String>> windows) {
        StringBuilder want = new StringBuilder();
        windows.forEach((exe, places) -> {
            List<String> ok = places.stream().filter(p -> AppFolders.PLACE.matcher(p).matches()).toList();
            if (ok.isEmpty()) return;
            want.append("'").append(exe.replaceFirst("(?i)\\.exe$", "").replace("'", "''")).append("' = ")
                    .append("[Collections.ArrayList]@(").append(String.join(",", ok.stream().map(p -> "'" + p + "'").toList()))
                    .append("); ");
        });
        return "$ErrorActionPreference = 'SilentlyContinue'; Add-Type -AssemblyName UIAutomationClient; "
                + "$want = @{ " + want + "}; "
                + "$root = [Windows.Automation.AutomationElement]::RootElement; "
                + "$kids = [Windows.Automation.TreeScope]::Children; $any = [Windows.Automation.Condition]::TrueCondition; "
                + "$seen = @{}; foreach ($w in $root.FindAll($kids, $any)) { $seen[$w.Current.NativeWindowHandle] = 1 }; "
                + "'ready'; "
                + "$end = (Get-Date).AddSeconds(" + PLACE_SECONDS + "); "
                + "while ((Get-Date) -lt $end -and @($want.Values | Where-Object { $_.Count }).Count) { "
                + "Start-Sleep -Milliseconds 500; $p = @{}; Get-Process | ForEach-Object { $p[$_.Id] = $_.ProcessName }; "
                + "foreach ($w in $root.FindAll($kids, $any)) { "
                + "$h = $w.Current.NativeWindowHandle; if ($seen[$h]) { continue }; "
                + "$q = $want[$p[$w.Current.ProcessId]]; "
                // untitled (still loading): looked at again next time round
                + "if (-not $q -or -not $q.Count -or -not $w.Current.Name) { continue }; "
                + "$seen[$h] = 1; "
                + "try { $wp = $w.GetCurrentPattern([Windows.Automation.WindowPattern]::Pattern); "
                // a splash screen or a dialog cannot maximize, and takes no place
                + "if (-not $wp.Current.CanMaximize -or $wp.Current.IsModal) { continue }; "
                + "$s, $x, $y, $cx, $cy = ($q[0] -split ','); $q.RemoveAt(0); "
                + "$t = $w.GetCurrentPattern([Windows.Automation.TransformPattern]::Pattern); "
                + "$wp.SetWindowVisualState('Normal'); "
                // maximized: moved just inside the monitor it was on, then maximized there
                + "if ($s -eq '1') { $t.Move([double]$x + 40, [double]$y + 40); $wp.SetWindowVisualState('Maximized') } "
                + "else { $t.Move([double]$x, [double]$y); $t.Resize([double]$cx, [double]$cy) } "
                + "} catch { } } }";
    }

    /** Starts the placement script and returns once it is ready, so the first launch cannot
        open a window it would take for one that was already there. Never fails a resume. */
    static void placeWindows(Map<String, ArrayList<String>> windows) {
        if (windows == null || windows.isEmpty()) return;
        try {
            Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", placeScript(windows))
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            CompletableFuture.runAsync(process::destroyForcibly,
                    CompletableFuture.delayedExecutor(PLACE_SECONDS + 30, TimeUnit.SECONDS));
            BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream()));
            CompletableFuture.supplyAsync(() -> {
                try { return out.readLine(); } catch (IOException ex) { return null; }
            }).get(15, TimeUnit.SECONDS);
        } catch (Exception ex) {
            System.err.println("window placement skipped: " + ex);
        }
    }

    /** Native folder picker, opened at `current` when it still exists. Returns the absolute
        path, or null on cancel. */
    public String openFolder(Node source, String current) {
        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Select Project Folder");
        if (current != null && new File(current).isDirectory()) directoryChooser.setInitialDirectory(new File(current));
        File selectedDirectory = directoryChooser.showDialog(source.getScene().getWindow());
        return selectedDirectory == null ? null : selectedDirectory.getAbsolutePath();
    }

    /** The card's folder name: pick a new one and save it. Only Claude and, when no folder was
        captured for it, VS Code launch in it; every other app reopens on its own folders. */
    private void editFolder() {
        Project projectObj = activeProject;
        if (projectObj == null) return;
        String chosen = openFolder(projectPathLabel, projectObj.folderPath);
        if (chosen == null || chosen.equalsIgnoreCase(String.valueOf(projectObj.folderPath))) return;
        String was = projectObj.folderPath;
        projectObj.folderPath = chosen;
        try {
            saveProject(projectObj);
        } catch (IOException ex) {
            projectObj.folderPath = was;
            confirm("Folder not saved", projectsFile().getPath(),
                    "The project keeps its old folder. " + ex.getMessage(), "OK", () -> { });
        }
        if (activeProject == projectObj) renderDetail(projectObj);
    }
    // == Embedded server (the contract with ResumeOneExtension) ============
    //   WS  /ws?browser=X&profile=email   extension keeps this open; app pushes "capture" to request tabs,
    //                       or {"open":[urls]} on resume and the extension answers {"opened":n},
    //                       or {"close":[urls]} on Checkpoint & close (no answer)
    //   GET /project        active project name ("" if none) - the popup's "is the app up?"
    //   POST /tabs          {browser, profile, urls[]} -> stored on the active project and saved
    //   POST /focus         a second launch: bring this window forward, answer "coldstart"
    //   Port 8765, CORS open, because the extension's fetch comes from a chrome-extension:// origin.
    //   Bound to 127.0.0.1: no Windows Firewall prompt on first launch, and nothing else on the
    //   network can reach it. The extension's ws://localhost falls back from ::1 to 127.0.0.1.

    /** Whether a request may reach the server. A web page's request carries its own site as
        Origin (Chrome and Edge extensions send chrome-extension://), and without this any open
        tab could read the project name, overwrite its tabs or pose as the extension on /ws.
        No Origin is not a page (curl, a local tool), which already runs as the user.
        ponytail: any installed extension passes; pin the extension id if that ever matters. */
    static boolean fromExtension(String origin) {
        return origin == null || origin.startsWith("chrome-extension://");
    }

    /** The port the extension dials. coldstart.port only exists to run a test copy beside the
        real app; the installed app never sets it. */
    static final int PORT = Integer.getInteger("coldstart.port", 8765);

    public void startServer(){
        System.out.println("server starting...");
        Javalin app = Javalin.create(config -> {
            config.bundledPlugins.enableCors(cors -> {
                cors.addRule(rule -> {
                    rule.anyHost();
                });
            });
        }).start("127.0.0.1", PORT);
        serverStartedNanos = System.nanoTime();
        // CORS stays open for the extension, so these gates are what keep web pages out
        app.before(ctx -> { if (!fromExtension(ctx.header("Origin"))) throw new ForbiddenResponse(); });
        app.wsBeforeUpgrade("/ws", ctx -> { if (!fromExtension(ctx.header("Origin"))) throw new ForbiddenResponse(); });
        app.ws("/ws", ws -> {
            System.out.println("WEB starting...");

            ws.onConnect(ctx -> {
                wsSessions.add(ctx);
                System.out.println("extension connected: " + wsBrowser(ctx)
                        + " (" + wsSessions.size() + " connected)");
                refreshStatusDot();
                // a browser a resume just started: its extension takes the tabs still owed
                for (String saved : pendingOpens.keySet()) {
                    if (!sameBrowser(wsBrowser(ctx), saved)) continue;
                    List<String> rest = pendingOpens.remove(saved);
                    if (rest != null) ctx.send(gson.toJson(Map.of("open", rest)));
                }
                // a checkpoint taken while it slept still wants its tabs
                if (owedFor(owedTabs, wsBrowser(ctx), System.nanoTime()) != null) ctx.send("capture");
                });
            // the heartbeat is echoed: it is the extension's only way to tell a hung app from a
            // quiet one, and it redials a socket that stops echoing.
            // {"opened":n} answers a resume's {"open":[urls]}; anything else is ignored
            ws.onMessage(ctx -> {
                if ("Heartbeat".equals(ctx.message())) {
                    ctx.send("Heartbeat");
                    return;
                }
                if (!ctx.message().startsWith("{")) return;
                Object opened = gson.fromJson(ctx.message(), Map.class).get("opened");
                CompletableFuture<Integer> reply = openReplies.remove(ctx);
                if (reply != null && opened instanceof Number n) reply.complete(n.intValue());
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
        // a second launch (HelloApplication.focusRunning) asks this one forward instead of failing
        // on the taken port; POST, so a web page's request carries an Origin and the gate stops it
        app.post("/focus", ctx -> {
            Platform.runLater(() -> {
                Stage st = (Stage) btnCheckpoint.getScene().getWindow();
                st.setIconified(false);
                st.toFront();
                // Windows only lets the foreground process raise a window; a moment on top gets past it
                st.setAlwaysOnTop(true);
                st.setAlwaysOnTop(false);
                st.requestFocus();
            });
            ctx.result("coldstart");
        });
        app.post("/tabs", ctx -> {
            System.out.println(ctx.body());
            // gson gives back Object values here - browser is a String, urls a List, so the
            // map cannot be typed as Map<String, ArrayList<String>> without a bad cast
            Map<String, Object> data = gson.fromJson(ctx.body(), Map.class);
            @SuppressWarnings("unchecked")
            List<String> incoming = (List<String>) data.get("urls");
            Object browser = data.get("browser");
            // browser is absent when getBrowserFromBrands() recognises no brand
            String brand = browser != null ? browser.toString() : DEFAULT_BROWSER;
            Object profile = data.get("profile"), pid = data.get("pid");
            String key = browserKey(brand, profilePart(brand, profile == null ? null : profile.toString(),
                    pid == null ? null : pid.toString()));
            // tabs a checkpoint has been owed since this extension slept through it go to that
            // checkpoint, in its mode; it never counted on them, so its wait is not ticked
            Map.Entry<String, Owed> owed = owedFor(owedTabs, key, System.nanoTime());
            if (owed != null) owedTabs.remove(owed.getKey());
            // the project whose checkpoint asked, even if another one is on screen by now
            Project target = owed != null ? (projects.contains(owed.getValue().project()) ? owed.getValue().project() : null)
                    : tabsTarget(tabTarget, tabTargetUntil, System.nanoTime(), activeProject);
            Checkpoint mode = owed != null ? owed.getValue().mode() : tabMode;
            if (target != null && incoming != null) {
                List<String> pages = incoming.stream().filter(HelloController::isPageUrl).toList();
                ArrayList<String> had = target.urlsByBrowser.getOrDefault(key, new ArrayList<>());
                // the brand-only bucket from before the extension sent its profile is this
                // profile's now - but only when the browser has just the one profile: with two,
                // the "Yash" profile's Pinterest tabs went into "ys"'s list this way
                if (!key.equals(brand) && profileCount(brand) <= 1
                        && wsSessions.stream().noneMatch(s -> wsBrowser(s).equals(brand))) {
                    List<String> legacy = target.urlsByBrowser.remove(brand);
                    if (legacy != null) had = mergeUrls(had, legacy);
                }
                target.urlsByBrowser.put(key, mode.merge() ? mergeUrls(had, pages) : new ArrayList<>(pages));
                // its tabs are current now, owed or "from last checkpoint" before
                owedTabs.entrySet().removeIf(e -> e.getValue().project() == target && sameBrowser(key, e.getKey()));
                saveProject(target);
                if (owed == null) tabReplies.countDown();
                // tabs arrive after the checkpoint button has already returned, so the
                // card has to be redrawn off the javalin thread - and only if it is this card
                if (isHud()) {
                    Platform.runLater(() -> {
                        if (owed != null && exitWaiting) owedSettled();   // the held quit may go now
                        if (target != activeProject) return;
                        renderDetail(target);
                        // a reply that missed the checkpoint's wait still corrects the "Saved" line
                        if (System.nanoTime() < ckBusyUntil) {
                            ckDoneSub.setText(resumeCountLine(countApps(target), countTabs(target)));
                        }
                    });
                }
            }
        });
    }

}
