package com.example.resumeone;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;

public class Project {
    public String projectName;
    public String lastCheckpoint;
    public boolean active;
    public String folderPath;
    public HashMap<String, HashSet<String>> apps;
    // legacy: url list from before capture recorded which browser the tabs came from.
    // still read so an existing projects.json is not silently emptied - see getProjectsJson().
    public ArrayList<String> urls;
    public HashMap<String, ArrayList<String>> urlsByBrowser = new HashMap<>();


public Project(String projectName,String folderPath, HashMap<String, HashSet<String>> apps,ArrayList<String> urls){
        active = false;
        lastCheckpoint = LocalDate.now().toString();
        this.projectName = projectName;
        this.folderPath = folderPath;
        this.apps = apps;
        this.urls = urls;
}
public String getProjectName() {
    return projectName;
}
public String getLocalDate(){
    return lastCheckpoint;
}
public boolean getActive(){
    return active;
}

public String getFolderPath() {
    return folderPath;
}

public HashMap<String, HashSet<String>> getApps() {
    return apps;
}
public ArrayList<String> getUrls() {
    return urls;
}
public HashMap<String, ArrayList<String>> getUrlsByBrowser() {
    return urlsByBrowser;
}

}


