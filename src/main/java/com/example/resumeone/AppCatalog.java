package com.example.resumeone;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Which category a captured exe belongs to: the known-apps table, the windows that are never
    apps, and the user's own + / x choices for Other. No UI and no I/O, so tests call it as is. */
final class AppCatalog {
    private AppCatalog() {}

    static final String OTHER = "Other";
    static final String USER_APPS = "Apps";
    // read by the capture worker while the FX thread edits them
    static final Set<String> relaunched = ConcurrentHashMap.newKeySet(), hidden = ConcurrentHashMap.newKeySet();

    // shell/system windows that are never a user's "app" (PowerToys filters the same set)
    static final Set<String> IGNORED_WINDOWS = Set.of(
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

    // purely for labelling: an app missing from this map is still captured, under OTHER.
    // An entry ending in * matches by prefix, for exes named after their version
    static final Map<String, List<String>> KNOWN_APPS = Map.ofEntries(
            Map.entry("Browser", List.of("chrome.exe", "brave.exe", "firefox.exe", "msedge.exe",
                    "opera.exe", "vivaldi.exe", "arc.exe", "zen.exe", "librewolf.exe", "chromium.exe",
                    "comet.exe", "floorp.exe", "waterfox.exe", "thorium.exe", "mullvadbrowser.exe",
                    "palemoon.exe", "whale.exe", "DuckDuckGo.exe")),
            Map.entry("IDE", List.of("Code.exe", "Code - Insiders.exe", "cursor.exe", "windsurf.exe",
                    "zed.exe", "trae.exe", "fleet.exe", "idea64.exe", "devenv.exe", "eclipse.exe",
                    "sublime_text.exe", "notepad++.exe", "pycharm64.exe", "webstorm64.exe",
                    "phpstorm64.exe", "clion64.exe", "rider64.exe", "goland64.exe", "rubymine64.exe",
                    "studio64.exe", "netbeans64.exe", "qtcreator.exe", "gvim.exe", "emacs.exe",
                    "Kiro.exe", "Antigravity.exe", "Positron.exe", "dataspell64.exe", "neovide.exe",
                    "rstudio.exe", "MATLAB.exe", "Arduino IDE.exe", "Unity.exe", "Unity Hub.exe",
                    "UnrealEditor.exe", "Godot*", "GameMaker.exe", "RobloxStudioBeta.exe",
                    // the ones schools teach with
                    "thonny.exe", "bluej.exe", "codeblocks.exe", "devcpp.exe", "Scratch 3.exe",
                    "processing.exe", "spyder.exe")),
            // powershell/cmd stay Other on purpose: with claude in a conhost window they'd come
            // back as an empty shell beside claude, the case the wt special-case already avoids
            Map.entry("Terminal", List.of("WindowsTerminal.exe", "alacritty.exe", "wezterm-gui.exe",
                    "Hyper.exe", "ConEmu64.exe", "mintty.exe", "Tabby.exe", "warp.exe")),
            Map.entry("AI", List.of("claude.exe", "ChatGPT.exe", "Perplexity.exe", "LM Studio.exe",
                    "ollama app.exe", "Copilot.exe", "mscopilot.exe", "Msty.exe", "Jan.exe", "AnythingLLM.exe",
                    "Cherry Studio.exe", "Claude Desktop.exe", "Codex.exe", "Goose.exe", "Poe.exe",
                    "Chatbox.exe", "Pinokio.exe", "ComfyUI.exe", "StabilityMatrix.exe", "Granola.exe",
                    "DeepL.exe",
                    // CLI_RESUME's: reopened in a terminal on their last chat
                    "codex-cli.exe", "gemini-cli.exe", "copilot-cli.exe", "qwen-cli.exe", "goose-cli.exe",
                    "opencode.exe", "aider.exe", "kiro-cli.exe", "cursor-agent.exe", "droid.exe", "amp.exe")),
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
                    "mspaint.exe", "PaintDotNet.exe", "XD.exe", "draw.io.exe", "Lunacy.exe", "Affinity.exe",
                    "gimp-*", "Photo.exe", "Designer.exe", "Publisher.exe", "CorelDRW.exe",
                    "Corel PaintShop Pro.exe", "CLIPStudioPaint.exe", "sai2.exe", "MediBangPaintPro.exe",
                    "FireAlpaca.exe", "Aseprite.exe", "Animate.exe", "Adobe Bridge.exe",
                    "PhotoshopElementsEditor.exe", "CaptureOne.exe", "darktable.exe", "rawtherapee.exe",
                    "Luminar Neo.exe", "DxO.PhotoLab.exe", "Adobe Substance 3D Painter.exe", "Framer.exe",
                    "ProtoPie.exe", "Balsamiq Wireframes.exe", "SnagitEditor.exe", "i_view64.exe",
                    "xnviewmp.exe", "digikam.exe")),
            Map.entry("Video", List.of("Adobe Premiere Pro.exe", "AfterFX.exe", "Resolve.exe", "CapCut.exe",
                    "Adobe Media Encoder.exe", "shotcut.exe", "kdenlive.exe", "obs64.exe",
                    "CamtasiaStudio.exe", "HandBrake.exe", "Descript.exe", "Clipchamp.exe", "Filmora.exe",
                    "Wondershare Filmora*", "vegas*", "PDR.exe", "AvidMediaComposer.exe", "lightworks.exe",
                    "openshot-qt.exe", "Adobe Character Animator.exe", "Topaz Video AI.exe",
                    // streaming and recording
                    "Streamlabs OBS.exe", "XSplit.Core.exe", "vMix64.exe", "Loom.exe", "bdcam.exe", "Medal.exe",
                    // players
                    "mpc-hc64.exe", "PotPlayerMini64.exe", "Plex.exe")),
            Map.entry("Office", List.of("WINWORD.exe", "EXCEL.exe", "POWERPNT.exe", "MSACCESS.exe",
                    "VISIO.exe", "MSPUB.exe", "WINPROJ.exe", "Acrobat.exe", "AcroRd32.exe",
                    "SumatraPDF.exe", "FoxitPDFReader.exe", "wps.exe", "et.exe", "wpp.exe",
                    // LibreOffice's soffice.bin, whose ProcessName drops the .bin
                    "soffice.exe", "DesktopEditors.exe", "PDFXEdit.exe", "NitroPDF.exe", "okular.exe",
                    "PBIDesktop.exe", "tableau.exe", "Scrivener.exe", "Final Draft*", "MemoQ.exe",
                    // reading
                    "Kindle.exe", "calibre.exe", "DigitalEditions.exe")),
            Map.entry("Communication", List.of("discord.exe", "slack.exe", "teams.exe", "ms-teams.exe",
                    "Zoom.exe", "Telegram.exe", "WhatsApp.exe", "Signal.exe", "Skype.exe", "Element.exe",
                    "OUTLOOK.exe", "olk.exe", "thunderbird.exe", "Mailspring.exe", "Mattermost.exe",
                    "Viber.exe", "Messenger.exe", "Zulip.exe", "Beeper.exe",
                    // the Store WhatsApp's process today
                    "WhatsApp.Root.exe", "CiscoCollabHost.exe", "RingCentral.exe", "Lark.exe", "Feishu.exe",
                    "WeChat.exe", "Weixin.exe", "LINE.exe", "KakaoTalk.exe", "QQ.exe", "DingTalk.exe",
                    "Rocket.Chat.exe", "Wire.exe", "Ferdium.exe", "Rambox.exe", "MailClient.exe",
                    "Mailbird.exe", "Proton Mail.exe", "Superhuman.exe")),
            Map.entry("Notes", List.of("notion.exe", "obsidian.exe", "onenote.exe", "Logseq.exe",
                    "Typora.exe", "Joplin.exe", "anytype.exe", "notepad.exe", "Evernote.exe",
                    "Zettlr.exe", "AFFiNE.exe", "xournalpp.exe", "Simplenote.exe", "Standard Notes.exe",
                    "Craft.exe", "Capacities.exe", "Heptabase.exe", "Tana.exe", "RemNote.exe", "UpNote.exe",
                    "SiYuan.exe", "Microsoft.Notes.exe", "anki.exe", "FocusWriter.exe", "iA Writer.exe")),
            Map.entry("Planning", List.of("Todoist.exe", "Todo.exe", "TickTick.exe", "Trello.exe", "Asana.exe",
                    "ClickUp.exe", "Linear.exe", "Airtable.exe", "Notion Calendar.exe", "Morgen.exe",
                    "Sunsama.exe", "Akiflow.exe", "Miro.exe", "Xmind.exe", "MindManager.exe", "TogglTrack.exe")),
            // engineering, architecture, 3D modelling and printing
            Map.entry("CAD", List.of("acad.exe", "acadlt.exe", "Revit.exe", "Inventor.exe", "SketchUp.exe",
                    "Fusion360.exe", "SLDWORKS.exe", "Rhino.exe", "FreeCAD.exe", "Archicad.exe",
                    "Vectorworks*", "CNEXT.exe", "xtop.exe", "ugraf.exe", "Shapr3D.exe", "Plasticity.exe",
                    "Lumion.exe", "Twinmotion.exe", "keyshot.exe", "Cinema 4D.exe", "maya.exe", "3dsmax.exe",
                    "ZBrush.exe", "houdini.exe", "houdinifx.exe", "houdinicore.exe", "kicad.exe", "X2.EXE",
                    "LTspice.exe", "bambu-studio.exe", "prusa-slicer.exe", "orca-slicer.exe",
                    "UltiMaker-Cura.exe")),
            // references, statistics, maths, GIS, qualitative analysis, the lab, LaTeX
            Map.entry("Research", List.of("zotero.exe", "Mendeley Reference Manager.exe", "EndNote.exe",
                    "JabRef.exe", "Citavi.exe", "stats.exe", "Stata*", "JASP.exe", "jamovi.exe", "sas.exe",
                    "Mtb.exe", "Origin64.exe", "prism.exe", "Mathematica.exe", "maplew.exe", "GeoGebra.exe",
                    "LabVIEW.exe", "ImageJ-win64.exe", "fiji-windows-x64.exe", "SnapGene.exe", "ChemDraw.exe",
                    "qgis-bin.exe", "qgis-ltr-bin.exe", "ArcGISPro.exe", "googleearth.exe", "NVivo.exe",
                    "ATLAS.ti.exe", "MAXQDA*", "texstudio.exe", "texworks.exe", "lyx.exe")),
            // accounting, tax, trading
            Map.entry("Finance", List.of("QBW.exe", "QBW32.exe", "tally.exe", "qw.exe", "Peachw.exe",
                    "gnucash.exe", "Moneydance.exe", "TurboTax*", "TradingView.exe", "thinkorswim.exe",
                    "tws.exe", "NinjaTrader.exe", "cTrader.exe", "terminal64.exe", "Ledger Live.exe",
                    "Exodus.exe")),
            // launchers only: a game itself is left to them
            Map.entry("Games", List.of("steam.exe", "EpicGamesLauncher.exe", "Battle.net.exe", "EADesktop.exe",
                    "UbisoftConnect.exe", "upc.exe", "GalaxyClient.exe", "XboxPcApp.exe", "Riot Client.exe",
                    "RiotClientUx.exe", "MinecraftLauncher.exe", "Playnite.DesktopApp.exe", "itch.exe",
                    "heroic.exe", "Amazon Games UI.exe", "Overwolf.exe", "CurseForge.exe", "Modrinth App.exe",
                    "prismlauncher.exe")),
            // only its folder windows count (AppFolders); with none saved it is always running,
            // so resume never starts a bare one
            Map.entry("Files", List.of("explorer.exe")),
            Map.entry("Music", List.of("spotify.exe", "amazonmusic.exe", "AppleMusic.exe", "Tidal.exe",
                    "MusicBee.exe", "foobar2000.exe", "vlc.exe", "iTunes.exe", "Deezer.exe", "AIMP.exe",
                    "Audacity.exe", "reaper.exe", "FL64.exe", "Qobuz.exe", "winamp.exe", "MediaMonkey.exe",
                    "Plexamp.exe", "Cider.exe", "YouTube Music.exe", "Pocket Casts.exe",
                    // making it
                    "Ableton Live*", "Cubase*", "Studio One.exe", "ProTools.exe", "Bitwig Studio.exe",
                    "Reason.exe", "Cakewalk.exe", "lmms.exe", "Adobe Audition.exe", "ocenaudio.exe",
                    "iZotope RX*", "Melodyne.exe", "MuseScore*", "Sibelius.exe", "Dorico*", "GuitarPro*",
                    "Serato DJ Pro.exe", "rekordbox.exe", "virtualdj.exe", "Traktor.exe"))
    );

    /** Buckets captured exe names by category: {"IDE": [Code.exe], "Browser": [chrome.exe], ...}.
        Every category key is always present (possibly empty) so the UI can iterate blindly. */
    static HashMap<String, HashSet<String>> segregateApps(HashSet<String> appset){
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
                boolean prefix = known.endsWith("*");
                if (prefix ? app.toLowerCase().startsWith(known.substring(0, known.length() - 1).toLowerCase())
                        : known.equalsIgnoreCase(app)) {
                    return entry.getKey();
                }
            }
        }
        // a known category wins, so an app that KNOWN_APPS learns later gets its real row
        return relaunched.contains(app.toLowerCase()) ? USER_APPS : OTHER;
    }
}
