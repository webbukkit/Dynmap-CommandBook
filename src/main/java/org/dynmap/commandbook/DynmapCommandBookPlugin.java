package org.dynmap.commandbook;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Wolf;
import org.bukkit.event.Event.Priority;
import org.bukkit.event.Event.Type;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.ServerListener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.dynmap.DynmapAPI;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerIcon;
import org.dynmap.markers.MarkerSet;
import org.dynmap.markers.Marker;

import com.sk89q.commandbook.CommandBookPlugin;
import com.sk89q.commandbook.locations.NamedLocation;
import com.sk89q.commandbook.locations.RootLocationManager;

public class DynmapCommandBookPlugin extends JavaPlugin {
    private static final Logger log = Logger.getLogger("Minecraft");
    private static final String LOG_PREFIX = "[Dynmap-CommandBook] ";

    Plugin dynmap;
    DynmapAPI api;
    MarkerAPI markerapi;
    CommandBookPlugin commandbook;
    RootLocationManager<NamedLocation> homesmgr;
    RootLocationManager<NamedLocation> warpsmgr;
    
    FileConfiguration cfg;
    
    private class Layer {
        MarkerSet set;
        MarkerIcon deficon;
        String labelfmt;
        Set<String> visible;
        Set<String> hidden;
        Map<String, Marker> markers = new HashMap<String, Marker>();
        
        public Layer(String id, FileConfiguration cfg, String deflabel, String deficon, String deflabelfmt) {
            set = markerapi.getMarkerSet("commandbook." + id);
            if(set == null)
                set = markerapi.createMarkerSet("commandbook."+id, cfg.getString("layer."+id+".name", deflabel), null, false);
            else
                set.setMarkerSetLabel(cfg.getString("layer."+id+".name", deflabel));
            if(set == null) {
                severe("Error creating " + deflabel + " marker set");
                return;
            }
            set.setLayerPriority(cfg.getInt("layer."+id+".layerprio", 10));
            set.setHideByDefault(cfg.getBoolean("layer."+id+".hidebydefault", false));
            int minzoom = cfg.getInt("layer."+id+".minzoom", 0);
            if(minzoom > 0) /* Don't call if non-default - lets us work with pre-0.28 dynmap */
                set.setMinZoom(minzoom);
            String icon = cfg.getString("layer."+id+".deficon", deficon);
            this.deficon = markerapi.getMarkerIcon(icon);
            if(this.deficon == null) {
                info("Unable to load default icon '" + icon + "' - using default '"+deficon+"'");
                this.deficon = markerapi.getMarkerIcon(deficon);
            }
            labelfmt = cfg.getString("layer."+id+".labelfmt", deflabelfmt);
            List<String> lst = cfg.getStringList("layer."+id+".visiblemarkers");
            if(lst != null)
                visible = new HashSet<String>(lst);
            lst = cfg.getStringList("layer."+id+".hiddenmarkers");
            if(lst != null)
                hidden = new HashSet<String>(lst);
        }
        
        void cleanup() {
            if(set != null) {
                set.deleteMarkerSet();
                set = null;
            }
            markers.clear();
        }
        
        boolean isVisible(String id, String wname) {
            if((visible != null) && (visible.isEmpty() == false)) {
                if((visible.contains(id) == false) && (visible.contains("world:" + wname) == false))
                    return false;
            }
            if((hidden != null) && (hidden.isEmpty() == false)) {
                if(hidden.contains(id) || hidden.contains("world:" + wname))
                    return false;
            }
            return true;
        }
        
        void updateMarkerSet(RootLocationManager<NamedLocation> mgr) {
            Map<String, Marker> newmap = new HashMap<String, Marker>(); /* Build new map */
            /* For each world */
            for(World w : getServer().getWorlds()) {
                String wname = w.getName();
                List<NamedLocation> loclist = mgr.getLocations(w);  /* Get locations in this world */
                if(loclist == null) continue;
                
                for(NamedLocation nl : loclist) {
                    int i;
                    /* Get name */
                    String name = nl.getName();
                    /* Skip if not visible */
                    if(isVisible(name, wname) == false)
                        continue;
                    /* Get location */
                    Location loc = nl.getLocation();
                    String id = wname + "/" + name;

                    String label = labelfmt.replaceAll("%name%", name);
                    
                    /* See if we already have marker */
                    Marker m = markers.remove(id);
                    if(m == null) { /* Not found?  Need new one */
                        m = set.createMarker(id, label, wname, loc.getX(), loc.getY(), loc.getZ(), deficon, false);
                    }
                    else {  /* Else, update position if needed */
                        m.setLocation(wname, loc.getX(), loc.getY(), loc.getZ());
                        m.setLabel(label);
                        m.setMarkerIcon(deficon);
                    }
                    newmap.put(id, m);    /* Add to new map */
                }
            }
            /* Now, review old map - anything left is gone */
            for(Marker oldm : markers.values()) {
                oldm.deleteMarker();
            }
            /* And replace with new map */
            markers.clear();
            markers = newmap;
        }
    }
    
    /* Homes layer settings */
    private Layer homelayer;
    
    /* Warps layer settings */
    private Layer warplayer;
    
    long updperiod;
    boolean stop;
    
    public static void info(String msg) {
        log.log(Level.INFO, LOG_PREFIX + msg);
    }
    public static void severe(String msg) {
        log.log(Level.SEVERE, LOG_PREFIX + msg);
    }

    private class MarkerUpdate implements Runnable {
        public void run() {
            if(!stop)
                updateMarkers();
        }
    }
    
    /* Update mob population and position */
    private void updateMarkers() {
        if(homesmgr != null) {
            homelayer.updateMarkerSet(homesmgr);
        }
        if(warpsmgr != null) {
            warplayer.updateMarkerSet(warpsmgr);
        }
        getServer().getScheduler().scheduleSyncDelayedTask(this, new MarkerUpdate(), updperiod);
    }

    private class OurServerListener extends ServerListener {
        @Override
        public void onPluginEnable(PluginEnableEvent event) {
            Plugin p = event.getPlugin();
            String name = p.getDescription().getName();
            if(name.equals("dynmap") || name.equals("CommandBook")) {
                if(dynmap.isEnabled() && commandbook.isEnabled())
                    activate();
            }
        }
    }
    
    public void onEnable() {
        info("initializing");
        PluginManager pm = getServer().getPluginManager();
        /* Get dynmap */
        dynmap = pm.getPlugin("dynmap");
        if(dynmap == null) {
            severe("Cannot find dynmap!");
            return;
        }
        api = (DynmapAPI)dynmap; /* Get API */
        /* Get CommandBook */
        Plugin p = pm.getPlugin("CommandBook");
        if(p == null) {
            severe("Cannot find CommandBook!");
            return;
        }
        commandbook = (CommandBookPlugin)p;
        /* If both enabled, activate */
        if(dynmap.isEnabled() && commandbook.isEnabled())
            activate();
        else
            getServer().getPluginManager().registerEvent(Type.PLUGIN_ENABLE, new OurServerListener(), Priority.Monitor, this);        
    }

    private void activate() {
        /* Now, get markers API */
        markerapi = api.getMarkerAPI();
        if(markerapi == null) {
            severe("Error loading Dynmap marker API!");
            return;
        }
        /* Now, get the commandbook homes API */
        homesmgr = commandbook.getHomesManager();
        /* If not found, signal disabled */
        if(homesmgr == null)
            info("CommandBook Homes not found - support disabled");
        /* Get the commandbook warps API */
        warpsmgr = commandbook.getWarpsManager();
        if(warpsmgr == null)
            info("CommandBook Warps not found - support disabled");
            
        /* Load configuration */
        FileConfiguration cfg = getConfig();
        cfg.options().copyDefaults(true);   /* Load defaults, if needed */
        this.saveConfig();  /* Save updates, if needed */
        
        /* Check which is enabled */
        if(cfg.getBoolean("layer.homes.enable", true) == false)
            homesmgr = null;
        if(cfg.getBoolean("layer.warps.enable", true) == false)
            warpsmgr = null;
        
        /* Now, add marker set for homes */
        if(homesmgr != null)
            homelayer = new Layer("homes", cfg, "Homes", "house", "%name%(home)");
        /* Now, add marker set for warps */
        if(warpsmgr != null)
            warplayer = new Layer("warps", cfg, "Warps", "portal", "[%name%]");
        
        /* Set up update job - based on periond */
        double per = cfg.getDouble("update.period", 5.0);
        if(per < 2.0) per = 2.0;
        updperiod = (long)(per*20.0);
        stop = false;
        getServer().getScheduler().scheduleSyncDelayedTask(this, new MarkerUpdate(), 5*20);
        
        info("version " + this.getDescription().getVersion() + " is activated");
    }

    public void onDisable() {
        if(homelayer != null) {
            homelayer.cleanup();
            homelayer = null;
        }
        if(warplayer != null) {
            warplayer.cleanup();
            warplayer = null;
        }
        stop = true;
    }

}
