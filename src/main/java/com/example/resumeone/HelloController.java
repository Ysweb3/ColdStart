package com.example.resumeone;

import com.google.gson.Gson;
import io.javalin.Javalin;
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
import java.net.URI;
import java.awt.*;
import java.io.File;
import java.io.*;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;


public class HelloController {
    @FXML
    public Button btnNewProject;

    @FXML
    public VBox projectList;

    public String projectFolder;

    public Gson gson = new Gson();

    private Project activeProject;
    public ArrayList<String> urls;
    public ArrayList<Project> projects = new ArrayList<>();

    @FXML
    public void initialize() throws IOException {

        segregateApps(captureRunningApps());
        startServer();
    }

    @FXML
    public void onNewProjectClick(){
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


            HBox projectRow = new HBox(10);
            Label projectLabel = new Label(name);
            projectLabel.setStyle("-fx-text-fill: white; -fx-padding: 8px;");
            System.out.println("PROJECT NAME:"+name);

            //empty project object
            Project projectObj = new Project(name, projectFolder, new HashMap<>(), new ArrayList<>());
            projects.add(projectObj);

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

        };

    }
    //TODO:IDEA:Also add a CLI to start this thing

    public HashSet<String> captureRunningApps() throws IOException {
        //runs tasklist, returns HashSet of running processes
        ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", "tasklist");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
        );
        String line;
        HashSet<String> appset = new HashSet<>();
        while ((line = reader.readLine()) != null) {
            String[] parts = line.split("\\s+");
            System.out.println(parts[0]);
            appset.add(parts[0]);
        }
        return appset;
    }
    public HashMap<String, HashSet<String>> segregateApps(HashSet<String> appset){
        //takes appset and categorizes it into results hashmap with hashset of item
        HashMap<String, String> knownApps = new HashMap();
        HashMap<String, HashSet<String>> result = new HashMap<>();
        knownApps.put("brave.exe", "Browser");
        knownApps.put("chrome.exe", "Browser");
        knownApps.put("firefox.exe", "Browser");
        knownApps.put("msedge.exe", "Browser");

        knownApps.put("Code.exe", "IDE");
        knownApps.put("Visual Studio Code.exe", "IDE");
        knownApps.put("idea64.exe", "IDE");
        knownApps.put("devenv.exe", "IDE");
        knownApps.put("eclipse.exe", "IDE");

        knownApps.put("spotify.exe", "Music");
        knownApps.put("amazonmusic.exe", "Music");

        knownApps.put("claude.exe", "AI");

        knownApps.put("discord.exe", "Communication");
        knownApps.put("slack.exe", "Communication");
        knownApps.put("teams.exe", "Communication");

        knownApps.put("notion.exe", "Notes");
        knownApps.put("obsidian.exe", "Notes");
        knownApps.put("onenote.exe", "Notes");

//        knownApps.put("windowsterminal.exe", "Terminal");
//        knownApps.put("cmd.exe", "Terminal");
//        knownApps.put("powershell.exe", "Terminal");

        result.put("IDE", new HashSet<>());
        result.put("Browser",new HashSet<>());
        result.put("Music",new HashSet<>());
        result.put("Communication",new HashSet<>());
        result.put("Notes",new HashSet<>());
        result.put("Terminal",new HashSet<>());
        result.put("AI",new HashSet<>());

        for (String item: knownApps.keySet()){
            if (appset.contains(item)){
               result.get(knownApps.get(item)).add(item);
            }
        }
        for (String item : result.keySet()) {
            System.out.println(item + " -> " + result.get(item));
        }
        return result;
    }
    public void saveProject(Project projectObj) throws IOException {

        ArrayList<String> objFolderPath = null;
        ArrayList<String> objUrls = new ArrayList<>();

        projects.add(projectObj);
        activeProject = projectObj;
        System.out.println("urls: "+projectObj.urls);

        String dirPath = System.getenv("APPDATA") + "\\ResumeWork";
        new File(dirPath).mkdirs();
        String filePath = dirPath + "\\projects.json";

        String jsonObj = gson.toJson(projectObj);

        //TODO:adding multiple projects and fix the json formatting
        BufferedWriter writer = new BufferedWriter(new FileWriter(filePath));
        writer.write(jsonObj);
        writer.newLine();
        writer.close();
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
    public void resumeProject(Project projectObj) throws IOException, URISyntaxException {
        //TODO:add multiple apps checking for path list
        System.out.println("projectName:"+projectObj.projectName);
        //Opens urls in the default browser for windows
        for (String url: projectObj.urls){
            Desktop.getDesktop().browse(new URI(url));
        }

            for (String category : projectObj.apps.keySet()) {
                for (String app : projectObj.apps.get(category)) {
                    String appName = app.replace(".exe", "");

                    if (appName.equalsIgnoreCase("claude")) {
                        // open claude CLI in project folder
                            ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", "start", "claude");
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
        Javalin app = Javalin.create().start(8765);
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
