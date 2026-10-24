// Full stack on a clean slate: the real coldstart app, the real extension in Chrome for Testing,
// the popup's Send clicked, and projects.json read back from disk. No mocks on either side.
// Run from ResumeOne/ with JAVA_HOME on a JDK 24, after `npm ci --prefix e2e`:
//   node e2e/full-stack.mjs ../ResumeOneExtension
// Its own port and a temp APPDATA keep it apart from a coldstart already open on this machine.
import puppeteer from "puppeteer";
import { spawn, execSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import assert from "node:assert/strict";
import net from "node:net";

// whatever port is free right now: a fixed one met an old test copy still holding it
const PORT = await new Promise(r => { const s = net.createServer().listen(0, "127.0.0.1", () => { const { port } = s.address(); s.close(() => r(port)); }); });
const APP = `http://127.0.0.1:${PORT}`;
const extSrc = path.resolve(process.argv[2] ?? "../ResumeOneExtension");
const repo = path.resolve(import.meta.dirname, "..");
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "coldstart-e2e-"));
const sleep = ms => new Promise(r => setTimeout(r, ms));
let log = "";

async function until(what, cond, ms) {
  for (const t0 = Date.now(); Date.now() - t0 < ms && app.exitCode === null; await sleep(250)) {
    try { if (await cond()) return; } catch {}
  }
  const why = app.exitCode === null ? "timed out" : `app exited (${app.exitCode})`;
  throw new Error(`${why} waiting for ${what}\n--- app log ---\n${log.slice(-4000)}`);
}

// a project already saved, so the app opens on it and the popup has somewhere to send
fs.mkdirSync(path.join(tmp, "Coldstart"));
const projectsJson = path.join(tmp, "Coldstart", "projects.json");
fs.writeFileSync(projectsJson, JSON.stringify([{ projectName: "e2e", apps: {}, urls: [] }]));

// the extension, unmodified but for the port it dials
const ext = path.join(tmp, "ext");
fs.cpSync(extSrc, ext, { recursive: true, filter: s => !/^[\\/]?(\.git|\.claude|test)\b/.test(s.slice(extSrc.length)) });
for (const f of ["background.js", "popup.js"]) {
  const p = path.join(ext, f), src = fs.readFileSync(p, "utf8");
  assert.ok(src.includes("localhost:8765"), `${f} no longer dials localhost:8765; update this script`);
  fs.writeFileSync(p, src.replaceAll("localhost:8765", `localhost:${PORT}`));
}

const app = spawn("cmd.exe", ["/c", path.join(repo, "mvnw.cmd"), "-q", "javafx:run"], {
  cwd: repo,
  env: { ...process.env, APPDATA: tmp, JAVA_TOOL_OPTIONS: `-Dcoldstart.port=${PORT}` },
});
app.stdout.on("data", d => log += d);
app.stderr.on("data", d => log += d);
let browser;
try {
  // first run compiles, so this one is long
  await until("the app to serve its project", async () => (await (await fetch(`${APP}/project`)).text()) === "e2e", 300_000);
  console.log("app up");

  browser = await puppeteer.launch({ enableExtensions: [ext], pipe: true });
  const worker = await browser.waitForTarget(t => t.type() === "service_worker" && t.url().endsWith("/background.js"));
  const id = new URL(worker.url()).host;
  await until("the extension's socket", () => /extension connected/.test(log), 30_000);
  console.log("extension connected");

  const urls = ["https://example.com/", "https://example.org/"];
  for (const u of urls) await (await browser.newPage()).goto(u);

  // the popup as a tab: same page, same chrome.* access, and clickable
  const popup = await browser.newPage();
  await popup.goto(`chrome-extension://${id}/popup.html`);
  await popup.waitForFunction(() => document.querySelector("#lede").textContent.includes("e2e")
      && !document.querySelector("#send").disabled, { timeout: 15_000 });
  await popup.click("#send");
  await popup.waitForFunction(() => document.querySelector("#status").textContent.startsWith("sent"), { timeout: 15_000 });

  // the browser's own pages (about:blank, the popup) are dropped, the two sites kept
  const saved = JSON.parse(fs.readFileSync(projectsJson, "utf8")).find(p => p.projectName === "e2e");
  assert.deepEqual(Object.values(saved.urlsByBrowser).flat().sort(), urls);
  console.log("full stack: ok");
} catch (err) {
  console.error(err);
  process.exitCode = 1;
} finally {
  await browser?.close();
  // only the tree this script started
  try { execSync(`taskkill /pid ${app.pid} /T /F`, { stdio: "ignore" }); } catch {}
  // the killed tree can still hold a file for a moment; a stray temp folder is no failure
  try { fs.rmSync(tmp, { recursive: true, force: true, maxRetries: 5 }); } catch {}
}
