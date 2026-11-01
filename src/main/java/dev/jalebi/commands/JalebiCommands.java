package dev.jalebi.commands;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.server.TabCompleteEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

public class JalebiCommands extends JavaPlugin implements Listener, CommandExecutor {

    private final Set<String> allowed = new HashSet<>();
    private final Set<String> blacklisted = new HashSet<>();
    private boolean includeAliases;
    private boolean whitelistEnabled;
    private String invalidMessage;
    private SimpleCommandMap commandMap;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        resolveCommandMap();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("jalebicommands").setExecutor(this);
        refreshPlayers();
        getLogger().info("Jalebi Commands enabled: " + allowed.size() + " allowed, "
                + blacklisted.size() + " blacklisted.");
    }

    @Override
    public void onDisable() {
        // Restore the normal command list for everyone
        allowed.clear();
        blacklisted.clear();
        whitelistEnabled = false;
        refreshPlayers();
    }

    // ------------------------------------------------------------------
    // Setup helpers
    // ------------------------------------------------------------------

    private void resolveCommandMap() {
        try {
            Object server = Bukkit.getServer();
            commandMap = (SimpleCommandMap) server.getClass().getMethod("getCommandMap").invoke(server);
        } catch (Exception e) {
            commandMap = null;
            getLogger().warning("Could not access the command map; alias support is limited.");
        }
    }

    private void loadSettings() {
        reloadConfig();
        loadSet("allowed-commands", allowed);
        loadSet("blacklisted-commands", blacklisted);
        includeAliases = getConfig().getBoolean("include-aliases", true);
        whitelistEnabled = getConfig().getBoolean("whitelist-enabled", true);
        invalidMessage = ChatColor.translateAlternateColorCodes('&',
                getConfig().getString("invalid-command-message", "&cInvalid command."));
    }

    private void loadSet(String key, Set<String> target) {
        target.clear();
        for (String s : getConfig().getStringList(key)) {
            String n = normalize(s);
            if (!n.isEmpty()) target.add(n);
        }
    }

    private void refreshPlayers() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.updateCommands();
        }
    }

    private static String normalize(String s) {
        if (s == null) return "";
        s = s.trim();
        while (s.startsWith("/")) s = s.substring(1);
        return s.trim().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // Core checks
    // ------------------------------------------------------------------

    private boolean isExempt(Player p) {
        return p.isOp() || p.hasPermission("jalebicommands.bypass");
    }

    /** True if the command, one of its aliases, or its un-namespaced name is in the given set. */
    private boolean matches(Set<String> set, String label, boolean useAliases) {
        if (set.isEmpty()) return false;
        if (set.contains(label)) return true;

        // "plugin:command" -> "command"
        int colon = label.indexOf(':');
        if (colon != -1 && set.contains(label.substring(colon + 1))) return true;

        if (useAliases && commandMap != null) {
            Command cmd = commandMap.getCommand(label);
            if (cmd != null) {
                if (set.contains(cmd.getName().toLowerCase(Locale.ROOT))) return true;
                for (String alias : cmd.getAliases()) {
                    if (set.contains(alias.toLowerCase(Locale.ROOT))) return true;
                }
            }
        }
        return false;
    }

    private boolean isAllowed(String label) {
        label = normalize(label);
        if (label.isEmpty()) return false;

        // Blacklist always wins, and always covers aliases / namespaced versions
        if (matches(blacklisted, label, true)) return false;

        if (!whitelistEnabled) return true;

        // Whitelist: exact match, or aliases if enabled (namespaced labels are NOT accepted here)
        if (allowed.contains(label)) return true;
        if (includeAliases && commandMap != null) {
            Command cmd = commandMap.getCommand(label);
            if (cmd != null) {
                if (allowed.contains(cmd.getName().toLowerCase(Locale.ROOT))) return true;
                for (String alias : cmd.getAliases()) {
                    if (allowed.contains(alias.toLowerCase(Locale.ROOT))) return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    /** Block commands that aren't allowed. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (isExempt(p)) return;

        String body = e.getMessage().trim();
        if (body.startsWith("/")) body = body.substring(1).trim();
        String label = body.split("\\s+", 2)[0];

        if (!isAllowed(label)) {
            e.setCancelled(true);
            p.sendMessage(invalidMessage);
        }
    }

    /** Hide disallowed commands from the client's command list (tab complete). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommandSend(PlayerCommandSendEvent e) {
        if (isExempt(e.getPlayer())) return;
        e.getCommands().removeIf(name -> !isAllowed(name));
    }

    /** Extra safety for servers/clients that use server-side tab completion. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTab(TabCompleteEvent e) {
        if (!(e.getSender() instanceof Player)) return;
        Player p = (Player) e.getSender();
        if (isExempt(p)) return;

        String buffer = e.getBuffer();
        if (!buffer.startsWith("/")) return; // normal chat completion

        String body = buffer.substring(1);
        int space = body.indexOf(' ');

        if (space == -1) {
            // Still typing the command name: only suggest allowed ones
            List<String> filtered = new ArrayList<>();
            for (String c : e.getCompletions()) {
                if (isAllowed(c)) filtered.add(c);
            }
            e.setCompletions(filtered);
        } else if (!isAllowed(body.substring(0, space))) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------
    // /jalebicommands
    // ------------------------------------------------------------------

    private void usage(CommandSender s, String label) {
        s.sendMessage(ChatColor.YELLOW + "Usage:");
        s.sendMessage(ChatColor.YELLOW + "/" + label + " reload");
        s.sendMessage(ChatColor.YELLOW + "/" + label + " list");
        s.sendMessage(ChatColor.YELLOW + "/" + label + " <add|remove> <command>  " + ChatColor.GRAY + "(allowed list)");
        s.sendMessage(ChatColor.YELLOW + "/" + label + " blacklist <add|remove> <command>");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            usage(sender, label);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload":
                loadSettings();
                refreshPlayers();
                sender.sendMessage(ChatColor.GREEN + "Jalebi Commands reloaded ("
                        + allowed.size() + " allowed, " + blacklisted.size() + " blacklisted).");
                break;

            case "list":
                sender.sendMessage(ChatColor.GOLD + "Allowed (" + allowed.size() + ")"
                        + (whitelistEnabled ? "" : " [whitelist OFF]") + ": "
                        + ChatColor.WHITE + String.join(", ", new TreeSet<>(allowed)));
                sender.sendMessage(ChatColor.RED + "Blacklisted (" + blacklisted.size() + "): "
                        + ChatColor.WHITE + String.join(", ", new TreeSet<>(blacklisted)));
                break;

            case "add":
            case "remove":
                if (args.length < 2) {
                    usage(sender, label);
                    break;
                }
                editList(sender, "allowed-commands", "allowed list", args[1], args[0].equalsIgnoreCase("add"));
                break;

            case "blacklist":
                if (args.length < 3
                        || !(args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove"))) {
                    usage(sender, label);
                    break;
                }
                editList(sender, "blacklisted-commands", "blacklist", args[2], args[1].equalsIgnoreCase("add"));
                break;

            default:
                usage(sender, label);
        }
        return true;
    }

    private void editList(CommandSender sender, String key, String niceName, String raw, boolean adding) {
        String name = normalize(raw);
        if (name.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Please give a command name.");
            return;
        }
        List<String> list = new ArrayList<>(getConfig().getStringList(key));
        list.removeIf(s -> normalize(s).equals(name));
        if (adding) list.add(name);
        getConfig().set(key, list);
        saveConfig();
        loadSettings();
        refreshPlayers();
        sender.sendMessage(ChatColor.GREEN + (adding ? "Added /" : "Removed /") + name
                + (adding ? " to the " : " from the ") + niceName + ".");
    }
}
