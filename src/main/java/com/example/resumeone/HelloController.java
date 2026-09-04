package com.example.resumeone;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;
import kotlin.reflect.KTypeProjection;

import java.net.URI;
import java.awt.*;
import java.io.File;
import java.io.*;
import java.net.URISyntaxException;
import java.util.*;
import java.util.List; // explicit: java.awt.* also exports List
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;


public class HelloController {
    @FXML
    public Button btnNewProject;

    private WsContext wsSession;

    @FXML
    public VBox projectList;

    public String projectFolder;

    public Gson gson = new Gson();

    private Project activeProject;
    public ArrayList<String> urls;

    public ArrayList<Project> projects = new ArrayList<>();

    @FXML
    public void initialize() throws IOException {

        for (Project project : getProjectsJson()) {
            createProjectUI(project, false);
        }
        segregateApps(captureRunningApps());
        startServer();
    }
    @FXML
    public ArrayList<Project> getProjectsJson() {
        String dirPath = System.getenv("APPDATA") + "\\ResumeWork";
        new File(dirPath).mkdirs();
        String filePath = dirPath + "\\projects.json";

        File file = new File(filePath);
        if (!file.exists()) {
            return new ArrayList<>();
        }

        try (Reader reader = new FileReader(file)) {
            ArrayList<Project> loaded = gson.fromJson(reader, new TypeToken<ArrayList<Project>>(){}.getType());
            return loaded != null ? loaded : new ArrayList<>();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    @FXML
    public void onNewProjectClick() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("New project");

       TextField nameField = new TextField();
       TextField folderField = new TextField();

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        grid.add(new Label("Project Name"),0,0);
        grid.add(nameField,1,0);

        grid.add(new Label("Project Folder"),0,1);

        Button selectFolder = new Button();
        selectFolder.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-weight:bold;");
        selectFolder.setText("Select Folder");
        selectFolder.setOnAction(e -> {
            projectFolder = (openFolder(e));
            selectFolder.setText(projectFolder);
                });
        grid.add(selectFolder,1,1);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {

            String name = nameField.getText();
            Project projectObj = new Project(name, projectFolder, new HashMap<>(), new ArrayList<>());
            createProjectUI(projectObj, true);

        };

    }
    //TODO:IDEA:Also add a CLI to start this thing
    public void createProjectUI(Project projectObj, boolean isNew) {

        String name = projectObj.projectName;
        HBox projectRow = new HBox(10);
        Label projectLabel = new Label(name);
        projectLabel.setStyle("-fx-text-fill: white; -fx-padding: 8px;");
        System.out.println("PROJECT NAME:"+name);

        if (isNew) {
            //initial capture when new project is made
            HashSet<String> currentapps = null;
            try {
                currentapps = captureRunningApps();

            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
            try {
                projectObj.apps = segregateApps(currentapps);
                saveProject(projectObj);

            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        } else {
            projects.add(projectObj);
        }

        Button resumeBtn = new Button();
        resumeBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-weight:bold;");
        resumeBtn.setText("Resume");
        resumeBtn.setOnAction(e ->{
            try {

                resumeProject(projectObj);
                activeProject = projectObj;

            } catch (IOException | URISyntaxException ex) {
                throw new RuntimeException(ex);
            }
        });

        Button checkpointBtn = new Button();
        checkpointBtn.setStyle("-fx-background-color: #FFFFFF; -fx-text-fill: black; -fx-font-weight:bold;");
        checkpointBtn.setText("Add Checkpoint");
        checkpointBtn.setOnAction(e -> {
            HashSet<String> runningApps = null;
            activeProject = projectObj;
            if (wsSession != null) {
                wsSession.send("capture");
            }
            try {
                runningApps = captureRunningApps();

            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
            try {
                projectObj.apps = segregateApps(runningApps);
                saveProject(projectObj);

            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }

        });

        Button deleteBtn = new Button();
        deleteBtn.setStyle("-fx-background-color: #d60630; -fx-text-fill: white; -fx-font-weight:bold;");
        deleteBtn.setText("Delete");

        projectRow.getChildren().addAll(projectLabel,resumeBtn,deleteBtn,checkpointBtn);
        projectList.getChildren().add(projectRow);

    }

    // shell/system windows that are never a user's "app" (PowerToys filters the same set)
    private static final Set<String> IGNORED_WINDOWS = Set.of(
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
        return IGNORED_WINDOWS.contains(lower) || lower.startsWith("powertoys.");
    }

    public HashSet<String> captureRunningApps() throws IOException {
        // only processes that own a visible main window count as apps the user opened.
        // .NET's MainWindowTitle is EnumWindows + GetWindowThreadProcessId under the hood,
        // single quotes only: a double quote inside this argument breaks ProcessBuilder's
        // windows quoting and powershell ends up echoing the rest of the script back
        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command",
                "Get-Process | Where-Object { $_.MainWindowTitle -ne '' } | " +
                        "ForEach-Object { $_.Id.ToString() + '|' + $_.ProcessName + '.exe' }");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
        );
        String line;
        HashSet<String> appset = new HashSet<>();
        long ownPid = ProcessHandle.current().pid();
        while ((line = reader.readLine()) != null) {
            String[] parts = line.split("\\|", 2);
            if (parts.length != 2) continue;
            // our own JavaFX window is owned by java.exe and would otherwise relaunch itself
            if (parts[0].trim().equals(String.valueOf(ownPid))) continue;
            String name = parts[1].trim();
            if (name.isBlank() || isIgnoredWindow(name)) continue;
            appset.add(name);
        }
        return appset;
    }

    static final String OTHER = "Other";

    // purely for labelling: an app missing from this map is still captured, under OTHER
    private static final Map<String, List<String>> KNOWN_APPS = Map.ofEntries(
            Map.entry("Browser", List.of("chrome.exe", "brave.exe", "firefox.exe", "msedge.exe",
                    "opera.exe", "vivaldi.exe", "arc.exe", "zen.exe", "librewolf.exe", "chromium.exe")),
            Map.entry("IDE", List.of("Code.exe", "Code - Insiders.exe", "cursor.exe", "windsurf.exe",
                    "zed.exe", "trae.exe", "fleet.exe", "idea64.exe", "devenv.exe", "eclipse.exe",
                    "sublime_text.exe", "notepad++.exe", "pycharm64.exe", "webstorm64.exe",
                    "phpstorm64.exe", "clion64.exe", "rider64.exe", "goland64.exe", "rubymine64.exe",
                    "studio64.exe", "netbeans64.exe", "qtcreator.exe", "gvim.exe", "emacs.exe")),
            Map.entry("Terminal", List.of("WindowsTerminal.exe", "alacritty.exe", "wezterm-gui.exe",
                    "Hyper.exe", "ConEmu64.exe", "mintty.exe", "Tabby.exe", "warp.exe")),
            Map.entry("AI", List.of("claude.exe", "ChatGPT.exe", "Perplexity.exe", "LM Studio.exe",
                    "ollama app.exe", "Copilot.exe")),
            Map.entry("Database", List.of("dbeaver.exe", "datagrip64.exe", "pgAdmin4.exe", "Ssms.exe",
                    "MySQLWorkbench.exe", "TablePlus.exe", "MongoDBCompass.exe", "redisinsight.exe",
                    "HeidiSQL.exe")),
            Map.entry("API", List.of("Postman.exe", "Insomnia.exe", "Bruno.exe", "Hoppscotch.exe")),
            Map.entry("Containers", List.of("Docker Desktop.exe", "Rancher Desktop.exe",
                    "podman-desktop.exe", "VirtualBox.exe", "vmware.exe")),
            Map.entry("VersionControl", List.of("GitHubDesktop.exe", "SourceTree.exe", "gitkraken.exe",
                    "Fork.exe", "smartgit.exe", "sublime_merge.exe")),
            Map.entry("Design", List.of("Figma.exe", "Photoshop.exe", "Illustrator.exe", "blender.exe",
                    "inkscape.exe", "krita.exe", "Canva.exe")),
            Map.entry("Communication", List.of("discord.exe", "slack.exe", "teams.exe", "ms-teams.exe",
                    "Zoom.exe", "Telegram.exe", "WhatsApp.exe", "Signal.exe", "Skype.exe", "Element.exe")),
            Map.entry("Notes", List.of("notion.exe", "obsidian.exe", "onenote.exe", "Logseq.exe",
                    "Typora.exe", "Joplin.exe", "anytype.exe")),
            Map.entry("Music", List.of("spotify.exe", "amazonmusic.exe", "AppleMusic.exe", "Tidal.exe",
                    "MusicBee.exe", "foobar2000.exe", "vlc.exe"))
    );

    public HashMap<String, HashSet<String>> segregateApps(HashSet<String> appset){
        // KNOWN_APPS only labels an app's category; capture no longer depends on it, so anything
        // unrecognised still gets kept under "Other" instead of being dropped
        HashMap<String, HashSet<String>> result = new HashMap<>();
        for (String category : KNOWN_APPS.keySet()) {
            result.put(category, new HashSet<>());
        }
        result.put(OTHER, new HashSet<>());

        for (String app : appset) {
            result.get(categoryOf(app)).add(app);
        }
        for (String item : result.keySet()) {
            System.out.println(item + " -> " + result.get(item));
        }
        return result;
    }

    // windows reports real executable casing (WindowsTerminal.exe, Spotify.exe), so match lowercased
    static String categoryOf(String app) {
        for (Map.Entry<String, List<String>> entry : KNOWN_APPS.entrySet()) {
            for (String known : entry.getValue()) {
                if (known.equalsIgnoreCase(app)) {
                    return entry.getKey();
                }
            }
        }
        return OTHER;
    }
    public void saveProject(Project projectObj) throws IOException {

        activeProject = projectObj;
        System.out.println("urls: "+projectObj.urls);

        upsertByName(projects, projectObj);

        String dirPath = System.getenv("APPDATA") + "\\ResumeWork";
        new File(dirPath).mkdirs();
        String filePath = dirPath + "\\projects.json";

        ArrayList<Project> onDisk = getProjectsJson();
        upsertByName(onDisk, projectObj);

        try (Writer writer = new FileWriter(filePath, false)) {
            gson.toJson(onDisk, writer);
        }
    }

    private void upsertByName(ArrayList<Project> list, Project projectObj) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).projectName.equals(projectObj.projectName)) {
                list.set(i, projectObj);
                return;
            }
        }
        list.add(projectObj);
    }
    public String getAppPath(String appName) throws IOException {
        ProcessBuilder pb = new  ProcessBuilder("cmd.exe","/c", "where", appName);
        Process process = pb.start();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
        );
        String path = reader.readLine();
        return path != null ? path.trim() : null;
    }
    // claude keeps transcripts at ~/.claude/projects/<folder with : and \ swapped for ->/<uuid>.jsonl
    static String claudeSessionSlug(String folderPath) {
        return folderPath.replace(":", "-").replace("\\", "-");
    }

    boolean hasClaudeSession(String folderPath) {
        File dir = new File(System.getProperty("user.home"),
                ".claude/projects/" + claudeSessionSlug(folderPath));
        String[] transcripts = dir.list((d, name) -> name.endsWith(".jsonl"));
        return transcripts != null && transcripts.length > 0;
    }

    // an app that is already open should be left alone rather than opened a second time.
    // browsers are skipped whenever the project has urls: Desktop.browse hands them to the
    // running browser as new tabs, so launching it as well is what creates the duplicate window.
    static boolean shouldLaunch(String app, Set<String> running, boolean hasUrls) {
        for (String open : running) {
            if (open.equalsIgnoreCase(app)) {
                return false;
            }
        }
        return !(hasUrls && "Browser".equals(categoryOf(app)));
    }

    public void resumeProject(Project projectObj) throws IOException, URISyntaxException {
        //TODO:add multiple apps checking for path list
        System.out.println("projectName:"+projectObj.projectName);
        HashSet<String> running = captureRunningApps();
        boolean hasUrls = !projectObj.urls.isEmpty();
        //Opens urls in the default browser for windows
        for (String url: projectObj.urls){
            //TODO:fix chrome settings urls not working
            System.out.println(url);
            Desktop.getDesktop().browse(new URI(url));
        }

            for (String category : projectObj.apps.keySet()) {
                for (String app : projectObj.apps.get(category)) {
                    String appName = app.replace(".exe", "");

                    if (!shouldLaunch(app, running, hasUrls)) {
                        System.out.println("skipping (already open or handled): " + app);
                        continue;
                    }

                    if (projectObj.folderPath != null) {
                        if (appName.equalsIgnoreCase("claude")) {
                            // open claude CLI in project folder; --continue reopens the most recent
                            // conversation, but it errors out when the folder has no history yet
                            ProcessBuilder pb = hasClaudeSession(projectObj.folderPath)
                                    ? new ProcessBuilder("cmd.exe", "/c", "start", "claude", "--continue")
                                    : new ProcessBuilder("cmd.exe", "/c", "start", "claude");
                            pb.directory(new File(projectObj.folderPath));
                            pb.start();

                        } else if (appName.equalsIgnoreCase("Code")) {
                            // open VS Code in project folder
                            System.out.println("opening VS Code in: " + projectObj.folderPath);
                            new ProcessBuilder("cmd.exe", "/c", "code", projectObj.folderPath).start();

                        } else {
                            new ProcessBuilder("cmd.exe", "/c", "start", appName).start();
                        }
                    }
                }
            }
    }

    public String openFolder(ActionEvent event) {

        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Select Application Folder");

        // Get the Window reference from the action event
        Window ownerWindow = ((Node) event.getSource()).getScene().getWindow();


        File selectedDirectory = directoryChooser.showDialog(ownerWindow);

        if (selectedDirectory != null) {
           return selectedDirectory.getAbsolutePath() ;
        } else {
            System.out.println("No folder chosen.");
        }
        return "NULL";
    }
    public void startServer(){
        System.out.println("server starting...");
        Javalin app = Javalin.create(config -> {
            config.bundledPlugins.enableCors(cors -> {
                cors.addRule(rule -> {
                    rule.anyHost();
                });
            });
        }).start(8765);
        app.ws("/ws", ws -> {
            System.out.println("WEB starting...");

            ws.onConnect(ctx -> {
                System.out.println(ctx);
                wsSession = ctx;
                System.out.println("extension connected");
                // ping every 30 seconds
                });
            ws.onClose(ctx -> {
                wsSession = null;
                System.out.println("extension disconnected");
//                System.out.println("disconnected, code: " + ctx.status());

            });
        });
        app.post("/tabs", ctx -> {
            System.out.println(ctx.body());
            Map<String , ArrayList<String>> data = gson.fromJson(ctx.body(),Map.class);
            urls = data.get("urls");
            if (activeProject != null) {
                activeProject.urls = new ArrayList<>(urls);
                saveProject(activeProject);
            }
        });
    }

}
