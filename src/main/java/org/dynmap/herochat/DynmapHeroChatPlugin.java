package org.dynmap.herochat;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.dynmap.DynmapAPI;
import org.dynmap.DynmapWebChatEvent;
import org.dynmap.markers.MarkerAPI;

import com.dthielke.herochat.Channel;
import com.dthielke.herochat.ChannelChatEvent;
import com.dthielke.herochat.ChannelManager;
import com.dthielke.herochat.Chatter.Result;
import com.dthielke.herochat.Herochat;

public class DynmapHeroChatPlugin extends JavaPlugin {
    private static Logger log;

    Plugin dynmap;
    DynmapAPI api;
    MarkerAPI markerapi;
    Herochat herochat;
    ChannelManager channelmgr;
    Channel from_web_channel;
    Set<String> channel_to_web_list;
    String  webmsgformat = "[WEB] %sender%: %message%";
    boolean enabled;
    
    FileConfiguration cfg;
    
    boolean stop;
    
    @Override
    public void onLoad() {
        log = this.getLogger();
    }
    
    public static void info(String msg) {
        log.log(Level.INFO, msg);
    }
    public static void severe(String msg) {
        log.log(Level.SEVERE, msg);
    }

    private class OurListener implements Listener {
        @EventHandler(priority=EventPriority.MONITOR)
        public void onPluginEnable(PluginEnableEvent event) {
            Plugin p = event.getPlugin();
            String name = p.getDescription().getName();
            if(name.equals("dynmap") || name.equals("Herochat")) {
                if(dynmap.isEnabled() && herochat.isEnabled())
                    activate();
            }
        }
        @EventHandler(priority=EventPriority.MONITOR)
        public void onHeroChatMessage(ChannelChatEvent event) {
            if(!enabled) return;
            String cname = event.getChannel().getName();
            String pname = event.getSender().getName();
            if(event.getResult() == Result.ALLOWED) {
                if(channel_to_web_list.contains(cname)) {
                    Player p = getServer().getPlayerExact(pname);
                    if(p != null) {
                        api.postPlayerMessageToWeb(p.getName(), p.getDisplayName(), event.getMessage());
                    }
                    else {
                        api.sendBroadcastToWeb(pname, event.getMessage());
                    }
                }
            }
        }
        @SuppressWarnings("unused")
        @EventHandler
        public void onDynmapWebChatMessage(DynmapWebChatEvent event) {
            if(!enabled) return;
            if(event.isCancelled()) return;
            event.setProcessed();   /* Mark event as handled - we'll send it to right channels */
            if(from_web_channel != null) {
                String s = webmsgformat;
                s = s.replace("&color;", "\u00A7");
                s = s.replace("%playername%", event.getName());
                s = s.replace("%message%", event.getMessage());
                from_web_channel.announce(s);
            }
        }
        @SuppressWarnings("unused")
        @EventHandler(priority=EventPriority.MONITOR)
        public void onPlayerJoined(PlayerJoinEvent event) {
            if(!enabled) return;
            api.postPlayerJoinQuitToWeb(event.getPlayer().getName(), event.getPlayer().getDisplayName(), true);
        }
        @SuppressWarnings("unused")
        @EventHandler(priority=EventPriority.MONITOR)
        public void onPlayerQuit(PlayerQuitEvent event) {
            if(!enabled) return;
            api.postPlayerJoinQuitToWeb(event.getPlayer().getName(), event.getPlayer().getDisplayName(), false);
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
        /* Get Herochat */
        Plugin p = pm.getPlugin("Herochat");
        if(p == null) {
            severe("Cannot find Herochat!");
            return;
        }
        herochat = (Herochat)p;
        channelmgr = Herochat.getChannelManager();
        if(channelmgr == null) {
            severe("Cannot find Herochat channel manager");
            return;
        }
        /* Register our listener */
        getServer().getPluginManager().registerEvents(new OurListener(), this);
        
        /* If both enabled, activate */
        if(dynmap.isEnabled() && herochat.isEnabled())
            activate();
        
        try {
            MetricsLite ml = new MetricsLite(this);
            ml.start();
        } catch (IOException iox) {
        }
    }

    private boolean prev_chat_to_web = false;
    private boolean reload = false;
    
    private void activate() {
        /* Now, get markers API */
        markerapi = api.getMarkerAPI();
        if(markerapi == null) {
            severe("Error loading Dynmap marker API!");
            return;
        }
            
        /* Load configuration */
        if(reload) {
            reloadConfig();
        }
        else {
            reload = true;
        }
        FileConfiguration cfg = getConfig();
        cfg.options().copyDefaults(true);   /* Load defaults, if needed */
        this.saveConfig();  /* Save updates, if needed */

        /* Get web to channel ID */
        String web_to_channel = cfg.getString("web-to-channel", null);
        if(web_to_channel != null) {
            from_web_channel = channelmgr.getChannel(web_to_channel);
            if(from_web_channel == null) {
                severe("Cannot find web-to-channel channel: " + web_to_channel);
            }
        }
        /* Get list of channel to web channels */
        List<String> channel_list = cfg.getStringList("channel-to-web-list");
        if(channel_list != null) {
            channel_to_web_list = new HashSet<String>(channel_list);
            for(String id : channel_to_web_list) {
                Channel c = channelmgr.getChannel(id);
                if(c == null) {
                    severe("Cannot find channel-to-web channel: " + id);
                }
            }
        }
        /* Get format message */
        webmsgformat = cfg.getString("webmsgformat", "&color;2[WEB] %playername%: &color;f%message%");
        /* Disable default chat-to-web processing */
        if(api.setDisableChatToWebProcessing(true))
            prev_chat_to_web = true;
        
        info("version " + this.getDescription().getVersion() + " is activated");
        enabled = true;
        
    }

    public void onDisable() {
        enabled = false;
        stop = true;
        if(prev_chat_to_web)    /* Enable if we were the one to disable it */
            api.setDisableChatToWebProcessing(false);
    }

}
