package com.example.resumeone;

import com.google.gson.Gson;
import io.javalin.websocket.WsContext;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Which browser and profile a tab bucket belongs to: the "Chrome · me@x.com" keys, the
    profile folders in each brand's Local State, and the exe each brand runs as. Reads files
    only; the live extension sockets stay with the server in ColdstartController. */
final class Browsers {
    private Browsers() {}

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
    // browser gives its email, else by its name, "Chrome · Personal" (profilePart). Only one the
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

    /** The name the profile goes by in the browser ("Work", "Personal"); null when unknown, so the
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
}
