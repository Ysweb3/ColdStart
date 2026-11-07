package com.example.resumeone;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Puts a resumed app's windows back, one launch at a time. One PowerShell runs for the whole
    resume; for each step it is told the exe and its saved places (expect), notes the windows
    already open, and once the step has launched (go) it waits for that exe's new windows,
    gives each the next place, then reads it back after it settles - putting it there once
    more if the app moved it. Windows that were open before are never moved. */
final class WindowPlacer implements AutoCloseable {

    /** How long a launch has to show its first window: IntelliJ can take this long to load. */
    static final int FIRST_SECONDS = 25;
    /** After a placed window, how long the next of the same launch may take (Chrome's second). */
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

    /** Starts the script, or null when PowerShell cannot: a resume never fails over a place. */
    static WindowPlacer start() {
        try {
            return new WindowPlacer(new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", script())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start());
        } catch (IOException ex) {
            System.err.println("window placement skipped: " + ex);
            return null;
        }
    }

    /** "Code|1,-8,-8,1936,1048;0,10,20,300,400": what the script is told before a launch, with
        places that are not AppFolders.PLACE (a hand-edited projects.json) left out. Null when
        none is left. It goes in on stdin, so nothing in it is ever run. */
    static String request(String exe, List<String> places) {
        List<String> ok = places.stream().filter(p -> AppFolders.PLACE.matcher(p).matches()).toList();
        String name = exe.replaceFirst("(?i)\\.exe$", "");
        return ok.isEmpty() || name.contains("|") ? null : name + "|" + String.join(";", ok);
    }

    /** Out of step with the script (a wait timed out, stop, or it died): no more places this run. */
    private boolean broken;

    /** Before the launch: returns once the script has noted the open windows, or false. */
    boolean expect(String exe, List<String> places) {
        String line = request(exe, places);
        if (broken || line == null) return false;
        in.println(line);
        broken = !"ready".equals(next(15, () -> false));
        return !broken;
    }

    /** After the launch, only when expect said yes: go (it opened windows to wait for) or skip,
        then the script's report, one line per window placed: "1 ok", "0 fixed", "2 off state 0".
        Empty when no window came. */
    List<String> finish(boolean go, BooleanSupplier stop) {
        in.println(go ? "go" : "skip");
        List<String> placed = new ArrayList<>();
        String line;
        while ((line = next(FIRST_SECONDS + 30, stop)) != null && !line.startsWith("done") && !line.equals("eof")) {
            if (line.startsWith("placed ")) placed.add(line.substring(7));
        }
        broken = line == null || line.equals("eof");
        return placed;
    }

    /** The script's next line, or null after `seconds` or once stop is true. */
    private String next(int seconds, BooleanSupplier stop) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        try {
            while (System.nanoTime() < end && !stop.getAsBoolean()) {
                String line = out.poll(200, TimeUnit.MILLISECONDS);
                if (line != null) return line;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    @Override
    public void close() {
        in.close();
        process.destroyForcibly();
    }

    /** Places are "state,x,y,w,h" (AppFolders.PLACE): 0 normal, 1 maximized, 2 minimized, 3
        minimized from maximized, the rect being where it goes back to.
        ponytail: an app's windows take its places in the order they appear, so two VS Code
        windows of one launch can swap; matching on the folder in the title would fix that.
        ponytail: a launch that opens no new window (a tab in a window already there) holds the
        resume for FIRST_SECONDS.
        ponytail: UI Automation coordinates; monitors at different scales may land a little off. */
    static String script() {
        return "$ErrorActionPreference = 'SilentlyContinue'; Add-Type -AssemblyName UIAutomationClient, System.Windows.Forms; "
                + "$root = [Windows.Automation.AutomationElement]::RootElement; "
                + "$kids = [Windows.Automation.TreeScope]::Children; $any = [Windows.Automation.Condition]::TrueCondition; "
                + "$wpat = [Windows.Automation.WindowPattern]::Pattern; $tpat = [Windows.Automation.TransformPattern]::Pattern; "
                // monitor bounds into UI Automation's pixels (1 once UIA has made the process DPI aware)
                + "$k = $root.Current.BoundingRectangle.Width / [Windows.Forms.SystemInformation]::VirtualScreen.Width; "
                + "function Say($m) { [Console]::Out.WriteLine($m); [Console]::Out.Flush() }; "
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
                + "while ($null -ne ($line = [Console]::In.ReadLine())) { "
                + "$exe, $list = $line -split '\\|', 2; $q = [Collections.ArrayList]@($list -split ';'); "
                + "$seen = @{}; foreach ($w in $root.FindAll($kids, $any)) { $seen[$w.Current.NativeWindowHandle] = 1 }; "
                + "Say 'ready'; "
                + "if ([Console]::In.ReadLine() -ne 'go') { Say 'done 0'; continue }; "
                + "$n = 0; $end = (Get-Date).AddSeconds(" + FIRST_SECONDS + "); "
                + "while ($q.Count -and (Get-Date) -lt $end) { "
                + "Start-Sleep -Milliseconds 300; $ids = @{}; Get-Process -Name $exe | ForEach-Object { $ids[$_.Id] = 1 }; "
                + "foreach ($w in $root.FindAll($kids, $any)) { "
                + "if (-not $q.Count) { break }; "
                + "$h = $w.Current.NativeWindowHandle; if ($seen[$h] -or -not $ids[$w.Current.ProcessId]) { continue }; "
                // untitled (still loading): looked at again next time round
                + "if (-not $w.Current.Name) { continue }; $seen[$h] = 1; "
                + "try { $wp = $w.GetCurrentPattern($wpat); "
                // a splash screen or a dialog cannot maximize, and takes no place
                + "if (-not $wp.Current.CanMaximize -or $wp.Current.IsModal) { continue }; "
                + "$s, $x, $y, $cx, $cy = ($q[0] -split ','); $q.RemoveAt(0); "
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
                + "$n++; Say ('placed ' + $s + ' ' + $how); $end = (Get-Date).AddSeconds(" + MORE_SECONDS + ") "
                + "} catch { } } }; "
                + "Say ('done ' + $n) }";
    }
}
