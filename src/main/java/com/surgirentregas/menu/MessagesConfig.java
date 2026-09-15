package com.surgirentregas.menu;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Carga los mensajes configurables (Action Bar y otros) desde config.yml.
 * Si una clave no existe, devuelve el fallback para que el plugin siempre
 * tenga texto que mostrar.
 */
public final class MessagesConfig {

    private final Plugin plugin;
    private final Map<String, String> actionBars = new HashMap<>();

    public MessagesConfig(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        actionBars.clear();
        File file = new File(plugin.getDataFolder(), "config.yml");
        if (!file.exists()) {
            plugin.saveResource("config.yml", false);
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        var section = cfg.getConfigurationSection("action-bar");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                actionBars.put(key, section.getString(key, ""));
            }
        }
    }

    /**
     * Devuelve el texto de un mensaje del Action Bar. Si la clave no esta
     * registrada, devuelve el fallback para evitar NPE en runtime.
     */
    @NotNull
    public String actionBar(@NotNull String key, @NotNull String fallback) {
        String value = actionBars.get(key);
        if (value == null || value.isBlank()) return fallback;
        return value;
    }

    public void reload() {
        load();
    }
}
