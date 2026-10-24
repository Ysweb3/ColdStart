# Changelog

## 1.0.0 - 2026-10-24

First public release. Built from the git history since July 2026.

### Added

- Projects: save the apps you have open, your browser tabs and an optional folder, then resume
  them later. Projects live in `%APPDATA%\Coldstart\projects.json`.
- Checkpoint captures every app with a visible window, with a progress bar while it runs.
  After a save, coldstart offers to close the project's apps for you.
- Resume relaunches what is not already open and shows each step as its own row, with Retry on
  a failed step and Stop / Resume the rest.
- Tabs come from the ColdStartExtension over `localhost:8765`, for several browsers and
  profiles at once. Tabs reopen in the profile they came from, paced so the browser keeps up.
  Tabs for an extension that is asleep wait and catch up when it reconnects.
- IDEs reopen on the folder they had open. Editors, Explorer, Unity, Office and Photoshop
  reopen their files.
- Claude Code reopens in its agent view for the project. Other AI CLIs, Claude Desktop, the
  Copilot app, Paint, Office and other common apps relaunch too.
- Resumed windows go back where they were.
- Apps under Other can be relaunched or hidden per project. Apps and tabs can be removed from
  a project.
- A "where was I" note on each project.
- First-run onboarding and an empty state for no projects.
- Settings: motion off-switch, start with Windows, close to tray, pin on top while resuming,
  and a link to the extension.
- Keyboard: Ctrl+R to resume, Ctrl+1..9 to switch projects.
- App icon and an MSIX manifest for a Store build.
- Errors are written to `error.log` next to `projects.json`.
- Tests: unit tests, a server test with a fake extension over real sockets, and a full-stack
  test with the real extension in Chrome for Testing. CI runs them on a fresh Windows VM.

### Changed

- Renamed the app to coldstart. Data moved from `%APPDATA%\ResumeWork` to
  `%APPDATA%\Coldstart`.
- New HUD look: square corners, IBM Plex type, higher contrast, every control reachable by
  keyboard and named.
- The project folder is optional and editable from the card.
- A second launch brings the running window forward instead of starting a new one.
- Quitting coldstart exits the process.
- Split the controller into `AppCatalog`, `Browsers` and `ResumePlanner`.

### Fixed

- Checkpoints no longer overwrite each other or race; saves are serialized and written
  atomically.
- Checkpoints run off the UI thread and wait for tabs before saving.
- Late tab replies no longer end the next wait.
- Resume no longer duplicates tabs in a browser that is already open, or counts open browsers
  as launched.
- Background Claude sessions are ignored on resume.
- The title bar no longer says "connecting" when no browser is open.
- Right-click no longer triggers a checkpoint.

### Security

- The local server listens on `127.0.0.1` only.
- Only the extension can reach the local server; requests from web pages are refused.
