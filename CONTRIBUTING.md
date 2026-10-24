# Contributing

Thanks for helping. coldstart is a Windows-only JavaFX app, so you need Windows to run it.

## Setup

- Windows 10 or 11
- JDK 24 or newer, with `JAVA_HOME` set to it
- Node 24 (only for the full-stack test)
- A clone of [Ysweb3/ColdStartExtension](https://github.com/Ysweb3/ColdStartExtension) next to
  this repo (only for the full-stack test)

Read [ARCHITECTURE.md](ARCHITECTURE.md) first. It explains where things live.

## Build and run

```
mvnw.cmd clean javafx:run
```

## Tests

Unit tests and the server end-to-end test:

```
mvnw.cmd test
```

Maven points `APPDATA` at `target/test-appdata` and the server at port 18765, so tests never
touch your real projects or a coldstart you already have open.

Full stack (real app, real extension in Chrome for Testing):

```
npm ci --prefix e2e
node e2e/full-stack.mjs ..\ColdStartExtension
```

CI runs both on a fresh Windows VM for every pull request. Please run them before you open one.

## Commits

Match the style in `git log`:

- One change per commit.
- Subject in the imperative, sentence case, no trailing period, about 50 characters or less.
  For example: `Listen on 127.0.0.1 only`, `Move resume planning into ResumePlanner`.
- Say what the user sees, not how the code does it, when you can:
  `Stop resume duplicating tabs of an open browser`.
- An optional area prefix is fine for a run of related commits: `Empty state: one equal beat at every step`.
- Use the body for the why, wrapped at about 72 characters.

## Pull requests

- Branch from `main`. Keep each pull request to one topic.
- Add or update tests for behaviour you change.
- If you change the port, the `/tabs` or `/ws` messages, or browser names, the extension repo
  needs the matching change.
