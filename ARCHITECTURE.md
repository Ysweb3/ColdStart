# coldstart — how it works

coldstart saves a "project" = the apps you have open + the browser tabs you have open + a
folder, and later brings all of it back with one click. It is two pieces that only talk over
`localhost:8765`:

```
┌─────────────────────────────┐   ws://localhost:8765/ws    ┌────────────────────────────┐
│  coldstart app              │◄───────────────────────────►│  coldstart extension       │
│  JavaFX window + Javalin    │   POST /tabs {browser,urls} │  MV3 service worker + popup│
│  server, Windows only       │◄────────────────────────────│  one per browser           │
│  (ResumeOne repo, this one) │                             │  (ResumeOneExtension repo) │
└─────────────────────────────┘                             └────────────────────────────┘
```

The repos and the Java package (`com.example.resumeone`) keep their old names: renaming the
package would orphan the saved preferences (they are keyed to it). Project data moved to
`%APPDATA%\Coldstart`; the old `%APPDATA%\ResumeWork` is no longer read or written.

## Files that matter

| File | What it is |
|---|---|
| `Launcher.java` | 3-line `main`. Exists only because JavaFX can't launch an `Application` subclass straight from the classpath. |
| `ColdstartApplication.java` | Window shell: loads fonts, loads `hud-view.fxml`, makes the stage undecorated and re-implements drag / resize / min / max / close. |
| `ColdstartController.java` | **Everything else.** UI controller, persistence, capture, resume, and the HTTP/WS server, in one class. Read it top-to-bottom; it is split into `// == Section ==` blocks in that order. |
| `AppCatalog.java` | Which category a captured exe lands in: `KNOWN_APPS`, `IGNORED_WINDOWS`, and the user's + / x choices for Other. Statically imported by the controller. |
| `Project.java` | The data class that is written to disk. Its public field names are the JSON keys. |
| `hud-view.fxml` | The UI tree. `fx:id`s here are injected into `@FXML` fields in the controller. `fx:controller="ColdstartController"` is what binds them. |
| `hud.css` | All styling. FXML nodes carry `styleClass="..."`, the css matches on `.that-class`. |
| `coldstart-view.fxml` | The old, plain UI. Still loadable (swap the filename in `ColdstartApplication`); every HUD-only field is null then, guarded by `isHud()`. |
| `module-info.java` | Java module declaration. `opens ... to javafx.fxml` is what lets FXMLLoader inject private-ish fields. |
| `src/test/...` | JUnit 5 tests for the pure helpers (categorising, persistence, launch commands). No UI tests. |
| `%APPDATA%\Coldstart\projects.json` | Where your projects actually live. Delete it to reset the app. |

Build/run: `mvnw.cmd clean javafx:run` (needs `JAVA_HOME` → a JDK 24+). Tests: `mvnw.cmd test`.

## The three things the app does

### 1. Checkpoint (capture)

Triggered by **Checkpoint** on the card, or by **Create + checkpoint** for a new project.

```
writeCheckpoint(project, mode)               FX thread; refused while resuming or already capturing
 ├─ wsSessions.forEach(send("capture"))       ask every connected browser for its tabs (async, see 3)
 ├─ block sub-line → "checkpointing…"
 └─ worker thread:
      captureRunningApps()                    PowerShell: every process with a visible window → {"Code.exe", "chrome.exe", ...}
      segregateApps(...)                      label them: {"IDE": [Code.exe], "Browser": [chrome.exe], "Other": [...]}
      Platform.runLater → finishCheckpoint()  apply, saveProject(), redraw, play "Saved"
                                              (on failure: put the old checkpoint back, say so, offer Retry)
```

A browser that does not answer the capture push keeps its saved tabs; nothing is cleared up front.
While a capture runs, Resume, undo, delete and New project wait for it.

`captureRunningApps()` is the only place that decides *what counts as an app*: a process
that owns a window (`MainWindowTitle != ''`), minus `IGNORED_WINDOWS` (shell junk), minus
our own PID. `claude.exe` is special-cased because it runs inside a terminal and never owns
the window itself.

`KNOWN_APPS` (category → exe names) is only for labelling. Unknown apps still get captured,
under `"Other"` — but `Other` is **not relaunched** on resume.

### 2. Resume

Triggered by **Resume** on the card.

```
resumeProject(project)
 ├─ running = captureRunningApps()            what is open right now
 ├─ for each browser in urlsByBrowser:
 │    extension connected?                    send {"open":[urls]}; it opens the missing ones, 4 at a time
 │    browserPath(browser)                    App Paths registry lookup → C:\...\chrome.exe
 │    profileDir(browser)                     "Chrome · me@x.com" → Local State → "Profile 2"
 │    ProcessBuilder(exe, --profile-directory, url1)   rest go to its extension when it connects,
 │                                            or all at once after 15s; no exe → Desktop.browse()
 └─ for each app in project.apps (named categories only, never Other):
      shouldLaunch(app, running, handled)?    skip if already open, skip browsers handled above
      claude  → cmd /c start [wt -d folder] claude agents [--cwd folder]   (claudeCommand())
      Code    → cmd /c code [folder]          (no folder: VS Code reopens its last workspace)
      wt      → cmd /c start wt              (skipped when claude launches inside it)
      others  → cmd /c start <name>          (only after onPath()/appPath() find the exe)
```

Every launch is wrapped in its own try/catch and logged, so one missing exe never stops the
rest. `start` reports nothing back when Windows cannot find a program, so a plain app is looked
up first (PATH, then the App Paths keys) and a miss is a failed row with Retry. All of this is
`cmd.exe`/`start`-based, i.e. Windows only.

The HUD runs this plan on a worker, one step per `LAUNCH_GAP_MS`, and the done screen names how
it ended: **RESUMED**, **STOPPED** (Stop with steps still queued; "Resume the rest" continues
from the first queued step) or **NOT RESUMED** (the capture failed, so nothing was launched).
The RESUME block's count comes from `launchableApps()`, which mirrors this plan, and the done
screen lists what was already open, so the two numbers reconcile.

### 3. Tabs come from the extension

The desktop app cannot see browser tabs. The extension can, so:

```
background.js (service worker, one per browser profile)
 ├─ connectWS()        opens ws://localhost:8765/ws?browser=Chrome&profile=me@x.com, retries every 3s
 ├─ on "capture"       chrome.tabs.query → POST /tabs {browser:"Chrome", profile, urls:[...]}
 ├─ heartbeat 20s      keeps the socket alive
 └─ chrome.alarms 30s  MV3 kills idle workers; the alarm wakes it and reconnects

popup.js (click the toolbar icon)
 ├─ GET /project       "is the app running, and which project will these land on?"
 └─ POST /tabs         same shape, but the user can untick tabs first
```

On the app side (`startServer()`):

- `/ws` — connect/close maintain `wsSessions` and the green dot in the title bar; a connect also
  takes any tabs a resume left in `pendingOpens` for that browser.
- `POST /tabs` — stores `urls` under `activeProject.urlsByBrowser["Chrome · me@x.com"]` (brand alone when no
  profile is signed in), saves, and redraws
  the card via `Platform.runLater` (Javalin runs on its own thread; JavaFX must be touched from
  the FX thread).

This is why a checkpoint's tab count can update a moment *after* the "saved" screen appears:
the POST arrives asynchronously.

## The UI model

Everything visible lives in `hud-view.fxml`. The centre of the window is a `StackPane` with
two layers, toggled purely with `visible`/`managed` — nothing is created at runtime except
sidebar rows and card chips:

```
base views   (exactly one)   detailPane | emptyPane | settingsPane        → showBase()
overlays     (at most one)   savingPane | deletePane | newProjectPane     → showOverlay()
```

Buttons are wired in `initialize()` with `setOnMouseClicked` / `setOnAction` (the two
"New Project" buttons use `onAction="#onNewProjectClick"` in the FXML instead).

State that matters:

| Field | Meaning |
|---|---|
| `projects` | in-memory copy of `projects.json`, sidebar order |
| `activeProject` | the one shown on the card **and the one `POST /tabs` writes into** |
| `rowsByProject` | name → sidebar `HBox`, so delete can remove the right row |
| `wsSessions` | live extension sockets; drives the status dot and the `"capture"` push |

## Persistence

`projects.json` is a JSON array of `Project`. `writeProjects()` always rewrites the whole
file (write `.tmp`, then atomic rename) so a crash mid-save can't empty it.
`saveProject()` upserts by `projectName`; `deleteProject()` removes by name.

`Project.urls` is a leftover from before tabs were grouped by browser. `migrateLegacyUrls()`
moves it into `urlsByBrowser["default"]` on load, so old files keep working.

## Gotchas worth knowing

- **Project name is the primary key.** Two projects with the same name overwrite each other.
- **Only Windows.** `cmd.exe`, `powershell.exe`, `reg.exe`, `%APPDATA%`.
- **Port 8765 is hardcoded** in both repos. Change it in `startServer()`, `background.js`
  and `popup.js` together.
- **Brand names are case-sensitive keys**: the extension sends `"Chrome"`, `"Edge"`, `"brave"`
  (lower-case, deliberately). `BROWSER_EXE` lower-cases on lookup; the JSON keeps what was sent.
- **Uncaught `IOException`s become `RuntimeException`s** in the button handlers — an error
  during checkpoint/resume shows up in the console, not in the UI.
- **Tests construct `ColdstartController` directly**, so nothing that needs the JavaFX toolkit may
  run in a field initialiser or the constructor — put it in `initialize()`.
