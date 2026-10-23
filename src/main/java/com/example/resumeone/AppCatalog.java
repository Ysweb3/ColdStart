package com.example.resumeone;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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

    // the design folds at four categories and links to an inspector; that artboard is not
    // built, so every non-empty category is listed rather than hidden behind a dead link
    static final List<String> CATEGORY_ORDER = List.of(
            "IDE", "AI", "Terminal", "Browser", "Database", "API", "Containers",
            "VersionControl", "Design", "Video", "CAD", "Research", "Office", "Finance", "Communication",
            "Notes", "Planning", "Files", "Music", "Games", "Apps");

    // only where the exe name is not the app's name; everything else is the exe, capitalised
    private static final Map<String, String> APP_NAMES = Map.ofEntries(
            Map.entry("code", "VS Code"), Map.entry("code - insiders", "VS Code Insiders"),
            Map.entry("explorer", "File Explorer"), Map.entry("mscopilot", "Copilot"),
            Map.entry("idea", "IntelliJ IDEA"), Map.entry("pycharm", "PyCharm"), Map.entry("webstorm", "WebStorm"),
            Map.entry("phpstorm", "PhpStorm"), Map.entry("clion", "CLion"), Map.entry("goland", "GoLand"),
            Map.entry("rubymine", "RubyMine"), Map.entry("datagrip", "DataGrip"), Map.entry("studio", "Android Studio"),
            Map.entry("devenv", "Visual Studio"), Map.entry("sublime_text", "Sublime Text"),
            Map.entry("sublime_merge", "Sublime Merge"), Map.entry("notepad++", "Notepad++"),
            Map.entry("windowsterminal", "Windows Terminal"), Map.entry("wezterm-gui", "WezTerm"),
            Map.entry("conemu", "ConEmu"), Map.entry("msedge", "Edge"), Map.entry("githubdesktop", "GitHub Desktop"),
            Map.entry("sourcetree", "Sourcetree"), Map.entry("gitkraken", "GitKraken"), Map.entry("ms-teams", "Teams"),
            Map.entry("ssms", "SSMS"), Map.entry("pgadmin4", "pgAdmin"), Map.entry("mysqlworkbench", "MySQL Workbench"),
            Map.entry("mongodbcompass", "MongoDB Compass"), Map.entry("dbeaver", "DBeaver"),
            Map.entry("heidisql", "HeidiSQL"), Map.entry("tableplus", "TablePlus"), Map.entry("chatgpt", "ChatGPT"),
            Map.entry("afterfx", "After Effects"), Map.entry("resolve", "DaVinci Resolve"),
            Map.entry("capcut", "CapCut"), Map.entry("indesign", "InDesign"),
            Map.entry("mspaint", "Paint"), Map.entry("paintdotnet", "Paint.NET"), Map.entry("winword", "Word"),
            Map.entry("excel", "Excel"), Map.entry("powerpnt", "PowerPoint"), Map.entry("msaccess", "Access"),
            Map.entry("visio", "Visio"), Map.entry("mspub", "Publisher"), Map.entry("winproj", "Project"),
            Map.entry("acrord32", "Acrobat Reader"), Map.entry("foxitpdfreader", "Foxit PDF Reader"),
            Map.entry("wps", "WPS Writer"), Map.entry("et", "WPS Spreadsheets"), Map.entry("wpp", "WPS Presentation"),
            Map.entry("outlook", "Outlook"), Map.entry("olk", "Outlook"), Map.entry("onenote", "OneNote"),
            Map.entry("obs", "OBS Studio"), Map.entry("camtasiastudio", "Camtasia"), Map.entry("fl", "FL Studio"),
            Map.entry("itunes", "iTunes"), Map.entry("azuredatastudio", "Azure Data Studio"),
            Map.entry("xournalpp", "Xournal++"), Map.entry("aimp", "AIMP"), Map.entry("xd", "Adobe XD"),
            // AI
            Map.entry("codex-cli", "Codex CLI"), Map.entry("gemini-cli", "Gemini CLI"),
            Map.entry("copilot-cli", "Copilot CLI"), Map.entry("qwen-cli", "Qwen Code"),
            Map.entry("goose-cli", "Goose CLI"), Map.entry("kiro-cli", "Kiro CLI"),
            Map.entry("cursor-agent", "Cursor CLI"), Map.entry("opencode", "OpenCode"), Map.entry("deepl", "DeepL"),
            Map.entry("comfyui", "ComfyUI"), Map.entry("stabilitymatrix", "Stability Matrix"),
            // Design, Video
            Map.entry("gimp-2.10", "GIMP"), Map.entry("gimp-3.0", "GIMP"), Map.entry("gimp-3", "GIMP"),
            Map.entry("photo", "Affinity Photo"), Map.entry("designer", "Affinity Designer"),
            Map.entry("publisher", "Affinity Publisher"), Map.entry("coreldrw", "CorelDRAW"),
            Map.entry("clipstudiopaint", "Clip Studio Paint"), Map.entry("sai2", "PaintTool SAI"),
            Map.entry("medibangpaintpro", "MediBang Paint"), Map.entry("firealpaca", "FireAlpaca"),
            Map.entry("animate", "Adobe Animate"), Map.entry("photoshopelementseditor", "Photoshop Elements"),
            Map.entry("captureone", "Capture One"), Map.entry("dxo.photolab", "DxO PhotoLab"),
            Map.entry("protopie", "ProtoPie"), Map.entry("snagiteditor", "Snagit"), Map.entry("i_view", "IrfanView"),
            Map.entry("xnviewmp", "XnView MP"), Map.entry("digikam", "digiKam"), Map.entry("pdr", "PowerDirector"),
            Map.entry("avidmediacomposer", "Media Composer"), Map.entry("openshot-qt", "OpenShot"),
            Map.entry("streamlabs obs", "Streamlabs"), Map.entry("xsplit.core", "XSplit"), Map.entry("vmix", "vMix"),
            Map.entry("bdcam", "Bandicam"), Map.entry("mpc-hc", "MPC-HC"), Map.entry("potplayermini", "PotPlayer"),
            // Office, Communication, Notes, Planning
            Map.entry("soffice", "LibreOffice"), Map.entry("desktopeditors", "ONLYOFFICE"),
            Map.entry("pdfxedit", "PDF-XChange Editor"), Map.entry("nitropdf", "Nitro PDF"),
            Map.entry("pbidesktop", "Power BI"), Map.entry("memoq", "memoQ"),
            Map.entry("digitaleditions", "Adobe Digital Editions"), Map.entry("whatsapp.root", "WhatsApp"),
            Map.entry("ciscocollabhost", "Webex"), Map.entry("ringcentral", "RingCentral"),
            Map.entry("wechat", "WeChat"), Map.entry("weixin", "WeChat"), Map.entry("line", "LINE"),
            Map.entry("kakaotalk", "KakaoTalk"), Map.entry("qq", "QQ"), Map.entry("dingtalk", "DingTalk"),
            Map.entry("mailclient", "eM Client"), Map.entry("remnote", "RemNote"), Map.entry("upnote", "UpNote"),
            Map.entry("siyuan", "SiYuan"), Map.entry("microsoft.notes", "Sticky Notes"),
            Map.entry("focuswriter", "FocusWriter"), Map.entry("todo", "Microsoft To Do"),
            Map.entry("ticktick", "TickTick"), Map.entry("clickup", "ClickUp"),
            Map.entry("mindmanager", "MindManager"), Map.entry("toggltrack", "Toggl Track"),
            // CAD
            Map.entry("acad", "AutoCAD"), Map.entry("acadlt", "AutoCAD LT"), Map.entry("sketchup", "SketchUp"),
            Map.entry("fusion360", "Fusion"), Map.entry("sldworks", "SolidWorks"), Map.entry("freecad", "FreeCAD"),
            Map.entry("cnext", "CATIA"), Map.entry("xtop", "Creo"), Map.entry("ugraf", "Siemens NX"),
            Map.entry("keyshot", "KeyShot"), Map.entry("3dsmax", "3ds Max"), Map.entry("zbrush", "ZBrush"),
            Map.entry("houdinifx", "Houdini"), Map.entry("houdinicore", "Houdini"), Map.entry("kicad", "KiCad"),
            Map.entry("x2", "Altium Designer"), Map.entry("ltspice", "LTspice"),
            Map.entry("bambu-studio", "Bambu Studio"), Map.entry("prusa-slicer", "PrusaSlicer"),
            Map.entry("orca-slicer", "OrcaSlicer"), Map.entry("ultimaker-cura", "Cura"),
            // Research
            Map.entry("mendeley reference manager", "Mendeley"), Map.entry("endnote", "EndNote"),
            Map.entry("jabref", "JabRef"), Map.entry("stats", "SPSS Statistics"), Map.entry("statamp", "Stata"),
            Map.entry("statase", "Stata"), Map.entry("statabe", "Stata"), Map.entry("jasp", "JASP"),
            Map.entry("sas", "SAS"), Map.entry("mtb", "Minitab"), Map.entry("prism", "GraphPad Prism"),
            Map.entry("maplew", "Maple"), Map.entry("geogebra", "GeoGebra"), Map.entry("labview", "LabVIEW"),
            Map.entry("imagej-win", "Fiji"), Map.entry("fiji-windows-x", "Fiji"), Map.entry("snapgene", "SnapGene"),
            Map.entry("chemdraw", "ChemDraw"), Map.entry("qgis-bin", "QGIS"), Map.entry("qgis-ltr-bin", "QGIS"),
            Map.entry("arcgispro", "ArcGIS Pro"), Map.entry("googleearth", "Google Earth Pro"),
            Map.entry("nvivo", "NVivo"), Map.entry("texstudio", "TeXstudio"), Map.entry("texworks", "TeXworks"),
            Map.entry("lyx", "LyX"),
            // Finance
            Map.entry("qbw", "QuickBooks"), Map.entry("qbw32", "QuickBooks"), Map.entry("tally", "TallyPrime"),
            Map.entry("qw", "Quicken"), Map.entry("peachw", "Sage 50"), Map.entry("gnucash", "GnuCash"),
            Map.entry("tradingview", "TradingView"), Map.entry("tws", "Trader Workstation"),
            Map.entry("ninjatrader", "NinjaTrader"), Map.entry("ctrader", "cTrader"),
            Map.entry("terminal", "MetaTrader 5"),
            // Games
            Map.entry("epicgameslauncher", "Epic Games"), Map.entry("eadesktop", "EA app"),
            Map.entry("ubisoftconnect", "Ubisoft Connect"), Map.entry("upc", "Ubisoft Connect"),
            Map.entry("galaxyclient", "GOG Galaxy"), Map.entry("xboxpcapp", "Xbox"),
            Map.entry("riotclientux", "Riot Client"), Map.entry("minecraftlauncher", "Minecraft Launcher"),
            Map.entry("playnite.desktopapp", "Playnite"), Map.entry("curseforge", "CurseForge"),
            Map.entry("prismlauncher", "Prism Launcher"),
            // Music
            Map.entry("mediamonkey", "MediaMonkey"), Map.entry("protools", "Pro Tools"), Map.entry("lmms", "LMMS"),
            Map.entry("virtualdj", "VirtualDJ"));

    /** "idea64.exe" -> "IntelliJ IDEA", "WindowsTerminal.exe" -> "Windows Terminal",
        "obsidian.exe" -> "Obsidian": what a person calls the app, not what Windows does. */
    static String appName(String exe) {
        String base = exe.replaceFirst("(?i)\\.exe$", "");
        String key = base.toLowerCase().replaceFirst("[-_]?64$", "");
        String known = APP_NAMES.get(key);
        if (known != null) return known;
        String name = base.replaceFirst("[-_]?64$", "");
        return name.isEmpty() ? exe : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    // == AI CLIs and their desktop twins =====================================

    /** CLIs that run as their own exe, by process name: captured with or without a window. */
    static final List<String> NATIVE_CLIS = List.of("claude", "codex", "copilot", "goose", "opencode",
            "aider", "kiro-cli", "droid");

    // claude, codex, copilot and goose each ship a desktop app and a CLI under one exe name:
    // exe -> {desktop app, CLI}. claude keeps claude.exe for the CLI, which is what every
    // project saved before the desktop app was told apart already means by it
    static final Map<String, List<String>> TWINS = Map.of(
            "claude.exe", List.of("Claude Desktop.exe", "claude.exe"),
            "codex.exe", List.of("Codex.exe", "codex-cli.exe"),
            "copilot.exe", List.of("Copilot.exe", "copilot-cli.exe"),
            "goose.exe", List.of("Goose.exe", "goose-cli.exe"));
    // where the desktop twins install: the Store, Claude's old installer, Electron's per-user
    // folder. The CLIs land in .local\bin, npm, WinGet\Packages, cargo or scoop instead
    private static final List<String> DESKTOP_DIRS = List.of("\\windowsapps\\", "\\anthropicclaude\\",
            "\\appdata\\local\\programs\\");

    /** What a captured process is saved as. A desktop twin counts only with a window open:
        its helpers have none, nor does one left running in the tray. A window cannot tell
        the CLI apart - in a conhost console it owns one too - so the install folder does.
        Steam's window belongs to its web helper. */
    static String capturedApp(String name, boolean windowed, String path) {
        String lower = name.toLowerCase();
        if (lower.equals("steamwebhelper.exe")) return "steam.exe";
        List<String> twin = TWINS.get(lower);
        if (twin == null) return name;
        String dir = path.toLowerCase();
        if (DESKTOP_DIRS.stream().anyMatch(dir::contains)) return windowed ? twin.get(0) : null;
        return twin.get(1);
    }

    // node CLIs: the package in the command line -> the name they are saved as. The trailing
    // slash keeps @github/copilot-language-server (editors run it on node) from counting
    private static final Map<String, String> NODE_CLIS = Map.of(
            "/@google/gemini-cli/", "gemini-cli.exe",
            "/@github/copilot/", "copilot-cli.exe",
            "/@qwen-code/qwen-code/", "qwen-cli.exe",
            "/@sourcegraph/amp/", "amp.exe",
            "/cursor-agent/", "cursor-agent.exe",
            // npm installs of these wrap a native exe, which capturedApp already names
            "/@openai/codex/", "codex-cli.exe",
            "/opencode-ai/", "opencode.exe");

    /** A node process's command line -> the CLI it runs, or null for any other node process. */
    static String nodeCli(String commandLine) {
        String cmd = commandLine.replace('\\', '/').toLowerCase();
        return NODE_CLIS.entrySet().stream().filter(e -> cmd.contains(e.getKey()))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    // the AI CLIs, as captured -> the command that reopens their last chat in the project
    // folder (each keeps its sessions per directory). claude's agent view takes --cwd instead
    static final Map<String, List<String>> CLI_RESUME = Map.ofEntries(
            Map.entry("claude.exe", List.of("claude", "agents")),
            Map.entry("codex-cli.exe", List.of("codex", "resume", "--last")),
            Map.entry("gemini-cli.exe", List.of("gemini", "--resume")),
            Map.entry("copilot-cli.exe", List.of("copilot", "--continue")),
            Map.entry("qwen-cli.exe", List.of("qwen", "--continue")),
            Map.entry("goose-cli.exe", List.of("goose", "session", "--resume")),
            Map.entry("opencode.exe", List.of("opencode", "--continue")),
            Map.entry("aider.exe", List.of("aider", "--restore-chat-history")),
            Map.entry("kiro-cli.exe", List.of("kiro-cli", "chat", "--resume")),
            Map.entry("cursor-agent.exe", List.of("cursor-agent", "resume")),
            Map.entry("droid.exe", List.of("droid", "--resume")),
            // no "latest thread" flag, only thread ids: a new thread, in the project
            Map.entry("amp.exe", List.of("amp")));

    static boolean isCli(String app) {
        return CLI_RESUME.containsKey(app.toLowerCase());
    }

    static boolean hasCli(Project projectObj) {
        return projectObj.apps.values().stream().flatMap(Set::stream).anyMatch(AppCatalog::isCli);
    }

    /** "...\WindowsApps\Claude_2.4.0.0_x64__pzs8sxrjxfjjc\app\claude.exe" -> "Claude_pzs8sxrjxfjjc!Claude":
        the package family (the folder name's first and last parts) and the id of the manifest's
        Application that runs this exe. Null outside WindowsApps or when the manifest is gone.
        ponytail: an update between checkpoint and resume moves the folder, so that resume
        misses; the next checkpoint saves the new path */
    static String storeAppId(String exePath) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)(.*\\\\WindowsApps\\\\([^\\\\]+))\\\\(.+)")
                .matcher(exePath);
        if (!m.matches()) return null;
        String[] pkg = m.group(2).split("_");
        try {
            String manifest = Files.readString(Path.of(m.group(1), "AppxManifest.xml"));
            java.util.regex.Matcher app = java.util.regex.Pattern.compile("<Application\\s[^>]*").matcher(manifest);
            while (app.find()) {
                String id = xmlAttr(app.group(), "Id"), exe = xmlAttr(app.group(), "Executable");
                if (id != null && m.group(3).equalsIgnoreCase(exe)) return pkg[0] + "_" + pkg[pkg.length - 1] + "!" + id;
            }
        } catch (IOException | RuntimeException ex) {
            // unreadable: the exe path is all there is
        }
        return null;
    }

    private static String xmlAttr(String tag, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\s" + name + "=\"([^\"]*)\"").matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    /** exe -> what explorer.exe opens to start it, for apps `start` cannot find by name and
        whose saved exe is no use: a Store app's id (Get-StartApps lists them), a launcher's url.
        Other Store apps need no entry - storeAppId reads theirs from the saved exe's package. */
    static final Map<String, String> SHELL_APPS = Map.of(
            "mscopilot.exe", "shell:AppsFolder\\Microsoft.Copilot_8wekyb3d8bbwe!App",
            // capturedApp's name, so there is no exe saved under it
            // ponytail: the Store build only; the old AnthropicClaude installer would need its exe
            "claude desktop.exe", "shell:AppsFolder\\Claude_pzs8sxrjxfjjc!Claude",
            // no App Paths entry, and the window - so the saved exe - is steamwebhelper's
            "steam.exe", "steam://open/main");

    static boolean hasApp(Project projectObj, String exe) {
        return projectObj.apps.values().stream()
                .flatMap(Set::stream)
                .anyMatch(exe::equalsIgnoreCase);
    }
}
