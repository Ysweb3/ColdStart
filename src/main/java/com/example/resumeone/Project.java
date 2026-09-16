package com.example.resumeone;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;

/**
 * One saved working context. Plain data: Gson serialises these public fields straight into
 * projects.json, so a field name here IS the JSON key - renaming one silently drops that
 * value for every already-saved project.
 *
 * <pre>
 * {
 *   "projectName":   "ColdStart project",             //primary key
 *   "lastCheckpoint": "2026-09-16T14:25:42Z",     // ISO instant (older files: a bare date), shown as "2h" / "3d"
 *   "active":        false,                         // unused; the active project is runtime state
 *   "folderPath":    "C:\...\ResumeOne",           // null when no folder was chosen
 *   "apps":          {"IDE": ["Code.exe"], "AI": ["claude.exe"], ...},   // category -> exe names
 *   "urls":          [],                            // legacy, see below
 *   "urlsByBrowser": {"Chrome": ["https://..."], "brave": [...]}       // extension brand -> tabs
 * }
 * </pre>
 */
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
        lastCheckpoint = Instant.now().toString();
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


