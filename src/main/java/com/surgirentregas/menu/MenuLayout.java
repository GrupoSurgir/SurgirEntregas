package com.surgirentregas.menu;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parser de los archivos YAML de menus:
 * <ul>
 *   <li>{@code menus/entregas.yml} — menu del jugador.</li>
 *   <li>{@code menus/entregas_admin.yml} — menu admin.</li>
 * </ul>
 *
 * <p>Cada menu declara su nombre, tamano y una coleccion de botones. Un boton
 * puede tener uno o varios slots; los slots se resuelven a indices absolutos
 * de inventario.</p>
 */
public final class MenuLayout {

    private final Plugin plugin;
    private final Map<String, MenuDefinition> menus = new HashMap<>();

    public MenuLayout(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        menus.clear();
        menus.put("entregas", loadMenu("menus/entregas.yml"));
        menus.put("entregas_admin", loadMenu("menus/entregas_admin.yml"));
    }

    private MenuDefinition loadMenu(@NotNull String resourcePath) {
        File file = new File(plugin.getDataFolder(), resourcePath);
        if (!file.exists()) {
            try {
                file.getParentFile().mkdirs();
                plugin.saveResource(resourcePath, false);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Recurso no encontrado: " + resourcePath);
            }
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        String name = cfg.getString("name", "Menu");
        int size = Math.max(9, Math.min(54, cfg.getInt("size", 27)));

        Map<String, ButtonDefinition> buttons = new LinkedHashMap<>();
        ConfigurationSection itemsSection = cfg.getConfigurationSection("items");
        if (itemsSection != null) {
            for (String key : itemsSection.getKeys(false)) {
                ButtonDefinition def = parseButton(itemsSection.getConfigurationSection(key));
                if (def != null) buttons.put(key, def);
            }
        }
        return new MenuDefinition(name, size, buttons);
    }

    private ButtonDefinition parseButton(@Nullable ConfigurationSection section) {
        if (section == null) return null;
        ConfigurationSection itemSection = section.getConfigurationSection("item");
        if (itemSection == null) return null;
        String matName = itemSection.getString("material", "STONE");
        Material material = parseMaterial(matName);
        if (material == null) {
            plugin.getLogger().warning("Material invalido o no-item en menu YAML: '" + matName
                    + "' — boton omitido.");
            return null;
        }

        String name = itemSection.getString("name", null);
        int amount = Math.max(1, itemSection.getInt("amount", 1));
        List<String> lore = itemSection.getStringList("lore");
        if (lore.isEmpty()) {
            Object raw = itemSection.get("lore");
            if (raw instanceof String s) lore = List.of(s);
        }

        List<Integer> slots = new ArrayList<>();
        // Soporta 'slot:' (entero) y 'slots:' (lista de enteros o rangos "a-b").
        if (section.isInt("slot")) {
            slots.add(section.getInt("slot"));
        } else {
            List<?> rawSlots = section.getList("slots");
            if (rawSlots != null) {
                for (Object o : rawSlots) {
                    if (o instanceof Number n) {
                        slots.add(n.intValue());
                    } else if (o instanceof String s) {
                        expandRange(s, slots);
                    }
                }
            }
        }
        return new ButtonDefinition(key0(), material, name, amount, lore, Collections.unmodifiableList(slots));
    }

    private static final java.util.concurrent.atomic.AtomicInteger KEY_GEN = new java.util.concurrent.atomic.AtomicInteger();

    private static String key0() {
        return "k" + KEY_GEN.incrementAndGet();
    }

    private void expandRange(@NotNull String token, @NotNull List<Integer> out) {
        String t = token.trim();
        int dash = t.indexOf('-');
        if (dash > 0 && dash < t.length() - 1) {
            try {
                int a = Integer.parseInt(t.substring(0, dash).trim());
                int b = Integer.parseInt(t.substring(dash + 1).trim());
                int from = Math.min(a, b);
                int to = Math.max(a, b);
                for (int i = from; i <= to; i++) out.add(i);
            } catch (NumberFormatException ignored) {
            }
        } else {
            try {
                out.add(Integer.parseInt(t));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    @Nullable
    private Material parseMaterial(@NotNull String name) {
        try {
            Material mat = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
            if (mat == null) return null;
            // Materiales WALL_* (bloques) NO son items instanciables.
            if (!mat.isItem()) return null;
            return mat;
        } catch (Exception e) {
            return null;
        }
    }

    @NotNull
    public MenuDefinition menu(@NotNull String id) {
        MenuDefinition def = menus.get(id);
        if (def == null) {
            // Fallback vacio para no romper el plugin si falta un archivo.
            return new MenuDefinition(id, 27, Collections.emptyMap());
        }
        return def;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // VALUE OBJECTS
    // ══════════════════════════════════════════════════════════════════════════

    public static final class MenuDefinition {
        private final String name;
        private final int size;
        private final Map<String, ButtonDefinition> buttons;

        public MenuDefinition(@NotNull String name, int size, @NotNull Map<String, ButtonDefinition> buttons) {
            this.name = name;
            this.size = size;
            this.buttons = Collections.unmodifiableMap(buttons);
        }

        @NotNull
        public String name() {
            return name;
        }

        public int size() {
            return size;
        }

        @NotNull
        public Map<String, ButtonDefinition> buttons() {
            return buttons;
        }

        @Nullable
        public ButtonDefinition button(@NotNull String key) {
            return buttons.get(key);
        }
    }

    public static final class ButtonDefinition {
        private final String key;
        private final Material material;
        @Nullable
        private final String name;
        private final int amount;
        private final List<String> lore;
        private final List<Integer> slots;

        public ButtonDefinition(@NotNull String key,
                                @NotNull Material material,
                                @Nullable String name,
                                int amount,
                                @NotNull List<String> lore,
                                @NotNull List<Integer> slots) {
            this.key = key;
            this.material = material;
            this.name = name;
            this.amount = amount;
            this.lore = lore;
            this.slots = slots;
        }

        @NotNull
        public String key() {
            return key;
        }

        @NotNull
        public Material material() {
            return material;
        }

        @Nullable
        public String name() {
            return name;
        }

        public int amount() {
            return amount;
        }

        @NotNull
        public List<String> lore() {
            return lore;
        }

        @NotNull
        public List<Integer> slots() {
            return slots;
        }
    }
}
