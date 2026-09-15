package com.surgirentregas.core;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MaintenanceManager {
    private final SurgirEntregasPlugin plugin;
    private volatile boolean maintenanceMode = false;
    private final ConcurrentHashMap<UUID, Boolean> bypassCache = new ConcurrentHashMap<>();

    public MaintenanceManager(@NotNull SurgirEntregasPlugin plugin) {
        this.plugin = plugin;
        loadState();
    }

    public void loadState() {
        maintenanceMode = plugin.getConfig().getBoolean("maintenance.enabled", false);
        plugin.getLogger().info("[Maintenance] Estado cargado: " + (maintenanceMode ? "ACTIVO" : "INACTIVO"));
    }

    public void saveState() {
        plugin.getConfig().set("maintenance.enabled", maintenanceMode);
        plugin.saveConfig();
        plugin.getLogger().info("[Maintenance] Estado guardado: " + (maintenanceMode ? "ACTIVO" : "INACTIVO"));
    }

    public void setMaintenanceMode(boolean enabled) {
        maintenanceMode = enabled;
        saveState();
        if (enabled) {
            Bukkit.getOnlinePlayers().forEach(p -> {
                if (!hasBypass(p)) {
                    p.sendMessage(plugin.colorize(plugin.getConfig().getString("maintenance.messages.enabled-broadcast", "<red>[!] SurgirEntregas ha entrado en modo Mantenimiento.</red>")));
                }
            });
            plugin.getLogger().warning("[Maintenance] Modo mantenimiento ACTIVADO.");
        } else {
            Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(plugin.colorize(plugin.getConfig().getString("maintenance.messages.disabled-broadcast", "<green>[!] Modo Mantenimiento finalizado.</green>"))));
            plugin.getLogger().info("[Maintenance] Modo mantenimiento DESACTIVADO.");
        }
    }

    public boolean isMaintenanceMode() {
        return maintenanceMode;
    }

    public boolean hasBypass(@NotNull Player player) {
        if (player.isOp()) return true;
        return player.hasPermission(plugin.getConfig().getString("maintenance.bypass-permission", "surgirentregas.maintenance.bypass"));
    }

    public boolean canUse(@NotNull Player player) {
        return !maintenanceMode || hasBypass(player);
    }

    public String getStatus() {
        return maintenanceMode ? "ACTIVO" : "INACTIVO";
    }

    public boolean handleCommand(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 1) {
            sender.sendMessage(plugin.colorize("<gray>Uso: /entregasadmin maintenance <on|off|toggle|status></gray>"));
            return true;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "on" -> {
                setMaintenanceMode(true);
                sender.sendMessage(plugin.colorize("<green>⚙ Modo mantenimiento ACTIVADO.</green>"));
            }
            case "off" -> {
                setMaintenanceMode(false);
                sender.sendMessage(plugin.colorize("<green>⚙ Modo mantenimiento DESACTIVADO.</green>"));
            }
            case "toggle" -> {
                boolean newState = !maintenanceMode;
                setMaintenanceMode(newState);
                sender.sendMessage(plugin.colorize("<green>⚙ Modo mantenimiento " + (newState ? "ACTIVADO" : "DESACTIVADO") + ".</green>"));
            }
            case "status" -> sender.sendMessage(plugin.colorize("<gray>Estado del mantenimiento: <" + (maintenanceMode ? "red" : "green") + ">" + getStatus() + "</" + (maintenanceMode ? "red" : "green") + "></gray>"));
            default -> sender.sendMessage(plugin.colorize("<gray>Uso: /entregasadmin maintenance <on|off|toggle|status></gray>"));
        }
        return true;
    }
}