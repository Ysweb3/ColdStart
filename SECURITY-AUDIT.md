# Security audit before going public

Scanned on 2026-10-24. Covers the full history: 225 commits on all 33 remote branches
(the clone was unshallowed first). Checked every added line, every commit message and the
author/committer fields.

Looked for: cloud and API keys (AWS, GitHub, OpenAI/Anthropic, Google, Slack, npm, GitLab,
SendGrid), private keys, JWTs, webhook URLs, password/secret/token assignments, long
high-entropy strings, IP addresses, personal file paths (`C:\Users\...`, `/home/...`,
`/Users/...`), email addresses, and committed data files (`.env`, `.json`, `.log`, keys,
certificates, archives).

History was not changed. Anything listed here stays in history until it is rewritten.

## Summary

- No secrets, API keys, tokens or private keys anywhere in history.
- One real personal path (a Windows username) in an old test. It is gone from the current tree.
- A personal Gmail address in the author field of 186 commits and in one commit message.
- Everything else is placeholder test data.

## Findings

| Commit | File | What it is | Risk |
|---|---|---|---|
| 186 commits by `Ysweb3` (every non-merge commit from `e307ebc` to `22ed1fb`, plus 6 local merges) | commit metadata | Author and committer email `yssh88972@gmail.com`. Visible to anyone who runs `git log` on the public repo. The 39 merges made on GitHub use the noreply address. | **Medium.** Personal email becomes public and scrapeable. Fixing it means rewriting history. To stop it for new commits, set `git config user.email 166310520+Ysweb3@users.noreply.github.com` and turn on "Keep my email address private" in GitHub settings. |
| `47a44a3` (2026-10-08, "Name profiles in the status tip and Settings too") | commit message | The message quotes the UI as `Chrome · yssh88972@gmail.com, brave`. | **Low.** Same email as above, so no new exposure. |
| `ffd48ca` (2026-09-29), removed in `1e13bf0` (2026-10-02) | `src/test/java/com/example/resumeone/ClaudeSessionTest.java` | Real local path `C:\Users\yashs\OneDrive\Desktop\Projects` and its slug `C--Users-yashs-OneDrive-Desktop-Projects`. Shows a Windows username and that the machine syncs Desktop to OneDrive. | **Low.** Not in the current tree, but readable in history. |
| `8e7159f` (2026-10-08), still in HEAD | `src/test/java/com/example/resumeone/AppFoldersTest.java:52` | OneDrive URL `https://d.docs.live.net/9a3f0c12be4d7e61/...`. The hex part is a OneDrive account ID slot. | **Low, verify.** Looks made up. If it is the real account ID, replace it in the current tree. |
| `ffd48ca`, `9b227ee`, `b969999` | `Icons.java`, `Motion.java`, `hud.css`, `hud-view.fxml` | Comments citing private design projects: `claude.ai/design b78487db`, `design system (4d7c4f7e)`, `design_handoff_resume_hud`. | **Info.** IDs only. They need a logged-in owner to open. No content leaks. |
| `c1cf65f` (2026-10-06), still in HEAD | `src/test/java/com/example/resumeone/DataSafetyTest.java:50` | Chrome extension ID `kgmhnaigjjoknmiipbhokbkpieaclmpg`. | **Info.** Extension IDs are public by design. |
| many, `d4cf711` onward | test files | Fake user paths `C:\Users\y\...` and `C:\Users\me\...`. | **None.** Placeholders. |
| many | test files, `ARCHITECTURE.md`, `Browsers.java` | Placeholder emails `me@x.com`, `you@x.com`, `work@x.com`, `nobody@x.com`, `e2e@coldstart.test`. | **None.** Placeholders. |
| `e307ebc` | `mvnw`, `mvnw.cmd` | References to `MVNW_USERNAME` / `MVNW_PASSWORD`. | **None.** Stock Maven Wrapper code that reads env vars. No values. |
| `e307ebc` onward | `.idea/` | IDE files. Only `$PROJECT_DIR$` paths, no absolute paths. | **None.** |

## Not secrets, but fix before going public

- **Font licences.** `src/main/resources/.../fonts/` ships IBM Plex (and Sora and Space Mono
  in older commits). These are under the SIL Open Font License 1.1, which requires the licence
  text to ship with the fonts. Add `OFL.txt` next to them.
- **Extension repo.** CI (`.github/workflows/test.yml`) checks out `Ysweb3/ColdStartExtension`.
  It must be public too, or CI fails on forks and outside pull requests.
- **Store placeholders.** `ColdstartController.EXTENSION_URL` and `EDGE_EXTENSION_URL` point at
  `example.com`, and `packaging/AppxManifest.xml` has `TODO-PartnerCenter`. Fine for now; just
  not real yet.
