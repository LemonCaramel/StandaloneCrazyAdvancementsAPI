package eu.endercentral.crazy_advancements;

import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.MinecraftKey;
import eu.endercentral.crazy_advancements.events.AdvancementScreenCloseEvent;
import eu.endercentral.crazy_advancements.events.AdvancementTabChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Warning;
import org.bukkit.craftbukkit.v1_15_R1.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import eu.endercentral.crazy_advancements.manager.AdvancementManager;
import net.minecraft.server.v1_15_R1.PacketPlayOutAdvancements;
import net.minecraft.server.v1_15_R1.PacketPlayOutSelectAdvancementTab;

public class CrazyAdvancements implements Listener {

    private AdvancementManager fileAdvancementManager;

    public static JavaPlugin plugin;
    private static ArrayList<Player> initiatedPlayers = new ArrayList<>();
    private static ArrayList<AdvancementManager> managers = new ArrayList<>();
    private static boolean announceAdvancementMessages = true;
    private static HashMap<String, NameKey> openedTabs = new HashMap<>();
    private static ProtocolManager protocolManager;
    public enum Status {
        OPENED_TAB,
        CLOSED_SCREEN;
    }

    /**
     * Create new instance of CrazyAdvancements
     *
     * @param pl The instance of the JavaPlugin using this API
     */
    public CrazyAdvancements(JavaPlugin pl) {}
    public CrazyAdvancements(JavaPlugin pl, ProtocolManager manager, boolean loadFile) {
        plugin = pl;
        fileAdvancementManager = new AdvancementManager();
        protocolManager = manager;

        protocolManager.addPacketListener(
                new PacketAdapter(pl, ListenerPriority.NORMAL, PacketType.Play.Client.ADVANCEMENTS) {
                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        PacketContainer container = event.getPacket();
                        Player player = event.getPlayer();
                        if (container.getEnumModifier(Status.class, 0).readSafely(0) == Status.OPENED_TAB) {
                            MinecraftKey key = container.getMinecraftKeys().readSafely(0);
                            NameKey name = new NameKey(key.getPrefix(), key.getKey());
                            AdvancementTabChangeEvent changeEvent = new AdvancementTabChangeEvent(event.getPlayer(), name);
                            Bukkit.getPluginManager().callEvent(changeEvent);

                            if (event.isCancelled()) {
                                CrazyAdvancements.clearActiveTab(player);
                            } else {
                                if (!changeEvent.getTabAdvancement().equals(name))
                                    CrazyAdvancements.setActiveTab(player, changeEvent.getTabAdvancement());
                                else
                                    CrazyAdvancements.setActiveTab(player, name, false);
                            }
                        } else {
                            Bukkit.getPluginManager().callEvent(new AdvancementScreenCloseEvent(player));
                        }
                    }
                }
        );

        //Registering Players
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (loadFile) {
                String path = plugin.getDataFolder().getAbsolutePath() + File.separator + "advancements" + File.separator + "main" + File.separator;
                File saveLocation = new File(path);
                loadAdvancements(saveLocation);
            }

            for (Player player : Bukkit.getOnlinePlayers()) {
                fileAdvancementManager.addPlayer(player);
                initiatedPlayers.add(player);
            }
        }, 5);
        //Registering Events
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /**
     * Disables the API
     */
    public void disable() {
        for (AdvancementManager manager : managers) {
            for (Advancement advancement : manager.getAdvancements()) {
                manager.removeAdvancement(advancement);
            }
        }
        PacketPlayOutAdvancements packet = new PacketPlayOutAdvancements(true, new ArrayList<>(), new HashSet<>(), new HashMap<>());
        for (Player p : Bukkit.getOnlinePlayers()) {
            ((CraftPlayer) p).getHandle().playerConnection.sendPacket(packet);
        }
    }

    private void loadAdvancements(File location) {
        location.mkdirs();
        File[] files = location.listFiles();
        Arrays.sort(files);
        for (File file : files) {
            if (file.isDirectory()) {
                loadAdvancements(file);
            } else if (file.getName().endsWith(".json")) {
                try {
                    FileReader os = new FileReader(file);

                    JsonParser parser = new JsonParser();
                    JsonElement element = parser.parse(os);
                    os.close();

                    Advancement add = Advancement.fromJSON(element);
                    fileAdvancementManager.addAdvancement(add);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Creates a new instance of an advancement manager
     *
     * @param players All players that should be in the new manager from the start, can be changed at any time
     * @return the generated advancement manager
     */
    public static AdvancementManager getNewAdvancementManager(Player... players) {
        return AdvancementManager.getNewAdvancementManager(players);
    }

    /**
     * Clears the active tab
     *
     * @param player The player whose Tab should be cleared
     */
    public static void clearActiveTab(Player player) {
        setActiveTab(player, null, true);
    }

    /**
     * Sets the active tab
     *
     * @param player          The player whose Tab should be changed
     * @param rootAdvancement The name of the tab to change to
     */
    public static void setActiveTab(Player player, String rootAdvancement) {
        setActiveTab(player, new NameKey(rootAdvancement));
    }

    /**
     * Sets the active tab
     *
     * @param player          The player whose Tab should be changed
     * @param rootAdvancement The name of the tab to change to
     */
    public static void setActiveTab(Player player, NameKey rootAdvancement) {
        setActiveTab(player, rootAdvancement, true);
    }

    static void setActiveTab(Player player, NameKey rootAdvancement, boolean update) {
        if (update) {
            PacketPlayOutSelectAdvancementTab packet = new PacketPlayOutSelectAdvancementTab(rootAdvancement == null ? null : rootAdvancement.getMinecraftKey());
            ((CraftPlayer) player).getHandle().playerConnection.sendPacket(packet);
        }
        openedTabs.put(player.getName(), rootAdvancement);
    }

    /**
     * @param player Player to check
     * @return The active Tab
     */
    public static NameKey getActiveTab(Player player) {
        return openedTabs.get(player.getName());
    }


    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {

            @Override
            public void run() {
                fileAdvancementManager.addPlayer(player);
                initiatedPlayers.add(player);
            }
        }, 5);
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        if (initiatedPlayers.contains(e.getPlayer())) initiatedPlayers.remove(e.getPlayer());
    }

    @Warning(reason = "Unsafe")
    public static ArrayList<Player> getInitiatedPlayers() {
        return initiatedPlayers;
    }

    /**
     * @return <b>true</b> if advancement messages should be shown by default<br><b>false</b> if all advancement messages will be hidden
     */
    public static boolean isAnnounceAdvancementMessages() {
        return announceAdvancementMessages;
    }

    /**
     * Changes if advancement messages should be shown by default
     *
     * @param announceAdvancementMessages Boolean that defines whether messages should be shown by default
     */
    public static void setAnnounceAdvancementMessages(boolean announceAdvancementMessages) {
        CrazyAdvancements.announceAdvancementMessages = announceAdvancementMessages;
    }

    private final List<String> selectors = Arrays.asList("@a", "@p", "@s", "@r");

    private boolean startsWithSelector(String arg) {
        for (String selector : selectors) {
            if (arg.startsWith(selector)) return true;
        }
        return false;
    }

    private Material getMaterial(String input) {
        for (Material mat : Material.values()) {
            if (mat.name().equalsIgnoreCase(input)) {
                return mat;
            }
        }
        return null;
    }
}
