# coldstart

coldstart saves what you are working on as a project: the apps you have open, the tabs open in
your browsers, and an optional folder. Later, one click on **Resume** brings it all back.

It is a Windows desktop app (JavaFX). A small browser extension,
[ColdStartExtension](https://github.com/Ysweb3/ColdStartExtension), reads and reopens the tabs.

> Screenshots coming soon.

## What it does

- **Checkpoint** records every app with a visible window, and asks each connected browser for
  its open tabs. Tabs are kept per browser and per profile.
- **Resume** relaunches what is not already running. IDEs reopen on the folder they had,
  AI CLIs such as Claude Code reopen their last session in a terminal, and each browser gets its
  tabs back in the right profile. Apps it does not know go under "Other" and are not relaunched
  unless you ask.
- Each step of a resume shows as its own row, so a missing app is a failed row with Retry
  instead of a silent miss.
- Projects are stored in `%APPDATA%\Coldstart\projects.json`. Errors go to `error.log` in the
  same folder. Delete the folder to reset the app.

## Requirements

- Windows 10 or 11. coldstart uses `cmd.exe`, `powershell.exe`, `reg.exe` and `%APPDATA%`, so
  it does not run on macOS or Linux.
- JDK 24 or newer, with `JAVA_HOME` pointing at it.
- For tabs: a Chromium browser (Chrome, Edge, Brave or Opera) with the extension installed.
  Firefox is not supported yet.

## Install

There is no release build yet. Build it from source:

```
git clone https://github.com/Ysweb3/ColdStart.git
cd ColdStart
mvnw.cmd clean javafx:run
```

The first run walks you through setup.

## Pair it with the extension

The app and the extension talk over `localhost:8765` (the app listens on `127.0.0.1` only and
turns away requests from web pages).

1. Clone [Ysweb3/ColdStartExtension](https://github.com/Ysweb3/ColdStartExtension).
2. Open `chrome://extensions` (or `edge://extensions`), turn on Developer mode, and choose
   **Load unpacked** on the cloned folder.
3. Start coldstart. The dot in the title bar turns green when a browser connects.

Do this for each browser you want tabs from. Port 8765 is fixed in both repos.

## Build and test

```
mvnw.cmd clean javafx:run   # build and run
mvnw.cmd test               # unit tests and the server end-to-end test
```

The full-stack test runs the real app and the real extension in Chrome for Testing:

```
npm ci --prefix e2e
node e2e/full-stack.mjs ..\ColdStartExtension
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for more, and [ARCHITECTURE.md](ARCHITECTURE.md) for how
it works inside.

## License

[MIT](LICENSE)
