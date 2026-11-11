package com.example.resumeone;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/** Puts resumed apps' windows back while the resume goes on launching. One PowerShell runs
    for the whole resume: it notes the windows open before it (never moved), and after each
    launch it is told the app and its saved places (launched). From then on every new window
    of that app takes the saved place whose title is most like its own, is read back once it
    settles, and is put there once more if the app moved it - while the launches after it
    carry on. When the last step has launched (drain) it finishes what it is waiting for. */
final class WindowPlacer {

    /** How long a cold start has to show its first window: IntelliJ can take this long to load. */
    static final int FIRST_SECONDS = 25;
    /** How long an app already running has: its new window comes at once or not at all (the
        launch became a tab in a window already there). Also how long a cold start may go
        without any process of the exe showing up before it is given up on. */
    static final int WARM_SECONDS = 6;
    /** After a placed window, how long the next of the same app may take (Chrome's second). */
    static final int MORE_SECONDS = 2;

    private final Process process;
    private final PrintWriter in;
    private final BlockingQueue<String> out = new LinkedBlockingQueue<>();

    private WindowPlacer(Process process) {
        this.process = process;
        in = new PrintWriter(process.getOutputStream(), true);
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                for (String line; (line = r.readLine()) != null; ) out.add(line.strip());
            } catch (IOException ex) {
                // the process went; the eof below tells every wait
            }
            out.add("eof");
        }, "window-placer");
        reader.setDaemon(true);
        reader.start();
    }

    /** Starts the script and returns once it has noted the open windows, so no launch can open
        one it would take for one that was already there. Null when it cannot: a resume never
        fails over a place. */
    static WindowPlacer start() {
        try {
            WindowPlacer placer = new WindowPlacer(new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", script())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start());
            if ("ready".equals(placer.next(15))) return placer;
            placer.process.destroyForcibly();
        } catch (IOException ex) {
            System.err.println("window placement skipped: " + ex);
        }
        return null;
    }

    /** The places worth sending: AppFolders.PLACE ones (not a hand-edited projects.json's). */
    static List<String> sendable(List<String> places) {
        return places.stream().filter(p -> AppFolders.PLACE.matcher(p).matches()).toList();
    }

    /** "Code|1,-8,-8,1936,1048<tab>a - proj - Visual Studio Code;0,10,20,300,400": an app and
        its places as the script takes them. Null when no place is left. It goes in on stdin,
        so nothing in it is ever run. */
    static String request(String exe, List<String> places) {
        List<String> ok = sendable(places);
        String name = exe.replaceFirst("(?i)\\.exe$", "");
        return ok.isEmpty() || name.contains("|") ? null : name + "|" + String.join(";", ok);
    }

    /** Was any process of exe up before its launch: an app already running gets the short wait. */
    static boolean running(String exe) {
        String tail = "\\" + exe.toLowerCase();
        return ProcessHandle.allProcesses().anyMatch(p -> p.info().command()
                .map(c -> c.toLowerCase().endsWith(tail)).orElse(false));
    }

    /** Right after a step launched exe: its windows are watched for from now. The places count
        the first time an app is named; a second launch of it (VS Code's next folder) only gives
        its windows longer to come. */
    void launched(String exe, boolean wasRunning, List<String> places) {
        String line = request(exe, places);
        if (line != null) in.println("want " + (wasRunning ? 1 : 0) + " " + line);
    }

    /** After the last launch: reports each window as it is placed - (app, "1 ok" / "0 fixed" /
        "2 off state 0") - and (app, null) for an app none of whose windows came, then stops the
        script. Blocks until everything waited for has come or timed out. */
    void drain(BiConsumer<String, String> report) {
        in.println("end");
        try {
            for (String line; (line = next(FIRST_SECONDS + MORE_SECONDS + 30)) != null && !line.equals("done") && !line.equals("eof"); ) {
                // "placed <app> <state> <how>" and "none <app>"; an app's name can hold spaces
                if (line.startsWith("placed ")) {
                    String[] f = line.substring(7).split(" ");
                    int at = 0;
                    while (at < f.length && !f[at].matches("[0-3]")) at++;
                    if (at == 0 || at == f.length) continue;
                    report.accept(String.join(" ", List.of(f).subList(0, at)), String.join(" ", List.of(f).subList(at, f.length)));
                } else if (line.startsWith("none ")) {
                    report.accept(line.substring(5), null);
                }
            }
        } finally {
            in.close();
            process.destroyForcibly();
        }
    }

    /** The script's next line, or null after `seconds`. */
    private String next(int seconds) {
        try {
            return out.poll(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Places are "state,x,y,w,h" (AppFolders.PLACE): 0 normal, 1 maximized, 2 minimized, 3
        minimized from maximized, the rect being where it goes back to; a tab and the title the
        window had may follow. A new window takes the place whose title shares the most words
        (3+ letters) with its own - the folder in VS Code's, the project in IntelliJ's - and the
        first of them on a tie, which is capture order. Lines in: "want <1 if it was running>
        <app>|<places>", then "end". Stdin is read without blocking, so windows are placed
        while the resume is still launching.
        ponytail: two windows whose titles share nothing that tells them apart (two Chrome
        windows on other tabs now) still go in the order they appear.
        ponytail: windows are placed one after another inside the script, so one that has to
        be put back twice holds the next for its 1.6s.
        ponytail: UI Automation coordinates; monitors at different scales may land a little off. */
    static String script() {
        return "$ErrorActionPreference = 'SilentlyContinue'; Add-Type -AssemblyName UIAutomationClient, System.Windows.Forms; "
                + "$root = [Windows.Automation.AutomationElement]::RootElement; $tab = [char]9; "
                + "$kids = [Windows.Automation.TreeScope]::Children; $any = [Windows.Automation.Condition]::TrueCondition; "
                + "$wpat = [Windows.Automation.WindowPattern]::Pattern; $tpat = [Windows.Automation.TransformPattern]::Pattern; "
                // monitor bounds into UI Automation's pixels (1 once UIA has made the process DPI aware)
                + "$k = $root.Current.BoundingRectangle.Width / [Windows.Forms.SystemInformation]::VirtualScreen.Width; "
                + "function Say($m) { [Console]::Out.WriteLine($m); [Console]::Out.Flush() }; "
                + "function Words($t) { if (-not $t) { return @() }; @($t.ToLower() -split '[^\\p{L}\\p{N}]+' | Where-Object { $_.Length -ge 3 }) }; "
                + "function Put($w, $s, $x, $y, $cx, $cy) { $wp = $w.GetCurrentPattern($wpat); $t = $w.GetCurrentPattern($tpat); "
                + "$wp.SetWindowVisualState('Normal'); "
                // maximized: moved just inside the monitor it was on, then maximized there; 3 carries
                // the size it had before maximizing, so un-maximizing goes back there too
                + "if ($s -eq '1') { $t.Move([double]$x + 40, [double]$y + 40) } "
                + "else { $t.Move([double]$x, [double]$y); $t.Resize([double]$cx, [double]$cy) }; "
                + "if ($s -eq '1' -or $s -eq '3') { $wp.SetWindowVisualState('Maximized') }; "
                // minimized: put back first, so restoring it from the taskbar lands there
                + "if ($s -eq '2' -or $s -eq '3') { $wp.SetWindowVisualState('Minimized') } }; "
                // '' when the window is where it was put, else what it is instead
                + "function Off($w, $s, $x, $y, $cx, $cy) { $v = [int]$w.GetCurrentPattern($wpat).Current.WindowVisualState; "
                + "$want = @{ '0' = 0; '1' = 1; '2' = 2; '3' = 2 }[$s]; if ($v -ne $want) { return 'state ' + $v }; "
                + "if ($s -eq '0') { $r = $w.Current.BoundingRectangle; "
                + "if ([Math]::Abs($r.X - $x) -gt 2 -or [Math]::Abs($r.Y - $y) -gt 2 -or [Math]::Abs($r.Width - $cx) -gt 2 -or [Math]::Abs($r.Height - $cy) -gt 2) { "
                + "return '' + [int]$r.X + ',' + [int]$r.Y + ',' + [int]$r.Width + ',' + [int]$r.Height } }; '' }; "
                + "$in = New-Object IO.StreamReader([Console]::OpenStandardInput()); $next = $in.ReadLineAsync(); $closed = $false; "
                + "$pool = @{}; "
                + "$seen = @{}; foreach ($w in $root.FindAll($kids, $any)) { $seen[$w.Current.NativeWindowHandle] = 1 }; "
                + "Say 'ready'; "
                + "while ($true) { "
                // every line that has come in: an app just launched, or the end of the launches
                + "while (-not $closed -and $next.IsCompleted) { $line = $next.Result; "
                + "if ($null -eq $line -or $line -eq 'end') { $closed = $true; break }; "
                + "$null, $was, $rest = $line -split ' ', 3; $ex, $list = $rest -split '\\|', 2; "
                + "$wait = " + FIRST_SECONDS + "; if ($was -eq '1') { $wait = " + WARM_SECONDS + " }; $until = (Get-Date).AddSeconds($wait); "
                + "$e = $pool[$ex]; "
                + "if (-not $e) { $all = @($list -split ';' | Where-Object { $_ }); "
                + "if ($all.Count) { $pool[$ex] = @{ all = $all; q = [Collections.ArrayList]@(0..($all.Count - 1)); t0 = Get-Date; was = ($was -eq '1'); end = $until; n = 0 } } } "
                + "else { $e.t0 = Get-Date; $e.was = ($was -eq '1'); if ($until -gt $e.end) { $e.end = $until } }; "
                + "$next = $in.ReadLineAsync() }; "
                + "$now = Get-Date; $p = @{}; $names = @{}; Get-Process | ForEach-Object { $p[$_.Id] = $_.ProcessName; $names[$_.ProcessName] = 1 }; "
                // a cold start with none of it running by now: it handed off and quit, or never started
                + "foreach ($ex in @($pool.Keys)) { $e = $pool[$ex]; "
                + "if (-not $e.was -and -not $names[$ex] -and ($now - $e.t0).TotalSeconds -gt " + WARM_SECONDS + ") { $e.end = $now } }; "
                + "$live = @($pool.Values | Where-Object { $_.q.Count -and $now -lt $_.end }).Count; "
                + "if ($closed -and -not $live) { break }; "
                + "Start-Sleep -Milliseconds 300; if (-not $live) { continue }; "
                + "foreach ($w in $root.FindAll($kids, $any)) { "
                + "$h = $w.Current.NativeWindowHandle; if ($seen[$h]) { continue }; $ex = $p[$w.Current.ProcessId]; $e = $pool[$ex]; "
                // not one being waited for, or untitled (still loading): looked at again next time round
                + "if (-not $e -or -not $e.q.Count -or (Get-Date) -gt $e.end -or -not $w.Current.Name) { continue }; "
                + "$seen[$h] = 1; "
                + "try { $wp = $w.GetCurrentPattern($wpat); "
                // a splash screen or a dialog cannot maximize, and takes no place
                + "if (-not $wp.Current.CanMaximize -or $wp.Current.IsModal) { continue }; "
                // the place whose saved title is most like this one's; the first on a tie
                + "$q = $e.q; $all = $e.all; $nw = Words $w.Current.Name; $bi = 0; $bs = -1; "
                + "for ($i = 0; $i -lt $q.Count; $i++) { $tt = ($all[$q[$i]] -split $tab, 2)[1]; "
                + "$sc = @(Words $tt | Where-Object { $nw -contains $_ }).Count; "
                + "if ($sc -gt $bs) { $bs = $sc; $bi = $i } }; "
                + "$pk = $q[$bi]; $q.RemoveAt($bi); $s, $x, $y, $cx, $cy = (($all[$pk] -split $tab, 2)[0] -split ','); "
                // a normal place over a whole monitor (full screen, or a desktop-sized window) would
                // come back over the taskbar, not maximized: it is maximized on that monitor instead
                + "if ($s -eq '0') { foreach ($b in [Windows.Forms.Screen]::AllScreens.Bounds) { "
                + "if ([double]$x -le $b.X * $k -and [double]$y -le $b.Y * $k -and [double]$x + [double]$cx -ge $b.Right * $k "
                + "-and [double]$y + [double]$cy -ge $b.Bottom * $k) { $s = '1' } } }; "
                // settled, then checked: an app that sizes itself after showing (its own saved
                // bounds) is put back once more
                + "Put $w $s $x $y $cx $cy; Start-Sleep -Milliseconds 800; $o = Off $w $s $x $y $cx $cy; $how = 'ok'; "
                + "if ($o) { Put $w $s $x $y $cx $cy; Start-Sleep -Milliseconds 800; $o = Off $w $s $x $y $cx $cy; "
                + "$how = 'fixed'; if ($o) { $how = 'off ' + $o } }; "
                + "$e.n++; Say ('placed ' + $ex + ' ' + $s + ' ' + $how); "
                + "$more = (Get-Date).AddSeconds(" + MORE_SECONDS + "); if ($more -gt $e.end) { $e.end = $more } "
                + "} catch { } } }; "
                + "foreach ($ex in $pool.Keys) { if (-not $pool[$ex].n) { Say ('none ' + $ex) } }; "
                + "Say 'done'";
    }
}
