package org.dynmap.commandbook;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    /* Homes layer settings */
    MarkerSet homesset;
    MarkerIcon homedef;
    String homelabelfmt;
    /* Warps layer settings */
    MarkerSet warpsset;
    MarkerIcon warpsdef;
    String warplabelfmt;
    
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
    
    private Map<String, Marker> homes = new HashMap<String, Marker>();
    private Map<String, Marker> warps = new HashMap<String, Marker>();
    
    /* Update mob population and position */
    private void updateMarkers() {
        if(homesmgr != null) {
            updateMarkerSet(homes, homesset, homesmgr, homedef, homelabelfmt);
        }
        if(warpsmgr != null) {
            updateMarkerSet(warps, warpsset, warpsmgr, warpsdef, warplabelfmt);
        }
        getServer().getScheduler().scheduleSyncDelayedTask(this, new MarkerUpdate(), updperiod);
    }
    
    private void updateMarkerSet(Map<String, Marker> markers, MarkerSet set, RootLocationManager<NamedLocation> mgr, MarkerIcon deficon, String labelfmt) {
        Map<String, Marker> newmap = new HashMap<String, Marker>(); /* Build new map */
        /* For each world */
        for(World w : getServer().getWorlds()) {
            List<NamedLocation> loclist = mgr.getLocations(w);  /* Get locations in this world */
            if(loclist == null) continue;
            
            for(NamedLocation nl : loclist) {
                int i;
                /* Get name and location */
                String name = nl.getName();
                Location loc = nl.getLocation();
                String id = w.getName() + "/" + name;

                String label = labelfmt.replaceAll("%name%", name);
                
                /* See if we already have marker */
                Marker m = markers.remove(id);
                if(m == null) { /* Not found?  Need new one */
                    m = set.createMarker(id, label, w.getName(), loc.getX(), loc.getY(), loc.getZ(), deficon, false);
                }
                else {  /* Else, update position if needed */
                    m.setLocation(w.getName(), loc.getX(), loc.getY(), loc.getZ());
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
        markers.clear();
        /* And replace with new map */
        markers.clear();
        markers.putAll(newmap);
    }

    private class OurServerListener extends ServerListener {
        @Override
        public void onPluginEnable(PluginEnableEvent event) {
            Plugin p = event.getPlugin();
            String name = p.getDescription().getName();
            if(name.equals("dynmap")) {
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
        if(homesmgr != null) {
            homesset = markerapi.getMarkerSet("commandbook.homes");
            if(homesset == null)
                homesset = markerapi.createMarkerSet("commandbook.homes", cfg.getString("layer.homes.name", "Homes"), null, false);
            else
                homesset.setMarkerSetLabel(cfg.getString("layer.homes.name", "Homes"));
            if(homesset == null) {
                severe("Error creating homes marker set");
                return;
            }
            homesset.setLayerPriority(cfg.getInt("layer.homes.layerprio", 10));
            homesset.setHideByDefault(cfg.getBoolean("layer.homes.hidebydefault", false));
            int minzoom = cfg.getInt("layer.homes.minzoom", 0);
            if(minzoom > 0) /* Don't call if non-default - lets us work with pre-0.28 dynmap */
                homesset.setMinZoom(minzoom);
            String deficon = cfg.getString("layer.homes.deficon", "house");
            homedef = markerapi.getMarkerIcon(deficon);
            if(homedef == null) {
                info("Unable to load default icon '" + deficon + "' - using default 'house'");
                homedef = markerapi.getMarkerIcon("house");
            }
            homelabelfmt = cfg.getString("layer.homes.labelfmt", "%name%(home)");
        }
        /* Now, add marker set for warps */
        if(warpsmgr != null) {
            warpsset = markerapi.getMarkerSet("commandbook.warps");
            if(warpsset == null)
                warpsset = markerapi.createMarkerSet("commandbook.warps", cfg.getString("layer.warps.name", "Warps"), null, false);
            else
                warpsset.setMarkerSetLabel(cfg.getString("layer.warps.name", "Warps"));
            if(warpsset == null) {
                severe("Error creating warps marker set");
                return;
            }
            warpsset.setLayerPriority(cfg.getInt("layer.warps.layerprio", 10));
            warpsset.setHideByDefault(cfg.getBoolean("layer.warps.hidebydefault", false));
            int minzoom = cfg.getInt("layer.warps.minzoom", 0);
            if(minzoom > 0) /* Don't call if non-default - lets us work with pre-0.28 dynmap */
                warpsset.setMinZoom(minzoom);
            String deficon = cfg.getString("layer.warps.deficon", "portal");
            warpsdef = markerapi.getMarkerIcon(deficon);
            if(warpsdef == null) {
                info("Unable to load default icon '" + deficon + "' - using default 'portal'");
                warpsdef = markerapi.getMarkerIcon("portal");
            }
            warplabelfmt = cfg.getString("layer.warps.labelfmt", "[%name%]");
        }
        
        /* Set up update job - based on periond */
        double per = cfg.getDouble("update.period", 5.0);
        if(per < 2.0) per = 2.0;
        updperiod = (long)(per*20.0);
        stop = false;
        getServer().getScheduler().scheduleSyncDelayedTask(this, new MarkerUpdate(), updperiod);
        
        info("version " + this.getDescription().getVersion() + " is activated");
    }

    public void onDisable() {
        if(homesset != null) {
            homesset.deleteMarkerSet();
            homesset = null;
        }
        if(warpsset != null) {
            warpsset.deleteMarkerSet();
            warpsset = null;
        }
        stop = true;
    }

}
