package com.surgirentregas.menu;

import com.surgirentregas.core.SurgirEntregasPlugin;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Configuracion de menus leida directamente desde config.yml.
 * Elimina la dependencia de la carpeta externa menus/.
 *
 * <p>Cada menu se define en {@code config.yml} bajo {@code menu-config.player-menu}
 * o {@code menu-config.admin-menu} con titulo, tamanio y lista de botones.</p>
 */
public final class MenuConfig {

    private final SurgirEntregasPlugin plugin;

    public MenuConfig(@NotNull SurgirEntregasPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Retorna la definicion de menu para el id dado.
     * Los ids soportados son "entregas" y "entregas_admin".
     */
    @NotNull
    public MenuDefinition menu(@NotNull String id) {
        String path = id.equals("entregas") ? "menu-config.player-menu" : "menu-config.admin-menu";
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(path);
        if (section == null) {
            return new MenuDefinition(id, 27, Collections.emptyMap());
        }
        String title = section.getString("title", "Menu");
        int size = Math.max(9, Math.min(54, section.getInt("size", 27)));
        Map<String, MenuButton> buttons = new LinkedHashMap<>();
        List<?> rawButtons = section.getList("buttons");
        if (rawButtons != null) {
            int idx = 0;
            for (Object entry : rawButtons) {
                if (!(entry instanceof Map<?, ?> map)) continue;
                MenuButton btn = parseButton(idx++, map);
                if (btn != null) buttons.put("btn-" + idx, btn);
            }
        }
        return new MenuDefinition(title, size, Collections.unmodifiableMap(buttons));
    }

    @Nullable
    private MenuButton parseButton(int idx, @NotNull Map<?, ?> map) {
        String matName = String.valueOf(map.get("material"));
        if (matName.isBlank() || "null".equals(matName)) return null;
        Material material;
        try {
            material = Material.valueOf(matName.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            plugin.getLogger().warning("[MenuConfig] Material invalido en menu: '" + matName + "'");
            return null;
        }
        if (!material.isItem()) return null;
        String name = map.containsKey("name") ? String.valueOf(map.get("name")) : null;
        int amount = Math.max(1, asInt(map.get("amount"), 1));
        List<String> lore = new ArrayList<>();
        Object rawLore = map.get("lore");
        if (rawLore instanceof List<?> loreList) {
            for (Object l : loreList) {
                if (l instanceof String s) lore.add(s);
            }
        } else if (rawLore instanceof String s) {
            lore.add(s);
        }
        int slot = asInt(map.get("slot"), -1);
        List<Integer> slots;
        if (slot >= 0) {
            slots = List.of(slot);
        } else {
            slots = new ArrayList<>();
            Object rawSlots = map.get("slots");
            if (rawSlots instanceof List<?> rawList) {
                for (Object o : rawList) {
                    if (o instanceof Number n) slots.add(n.intValue());
                    else if (o instanceof String s) expandRange(s, slots);
                }
            }
        }
        if (slots.isEmpty()) return null;
        return new MenuButton("btn-" + idx, material, name, amount, lore, Collections.unmodifiableList(slots));
    }

    private static void expandRange(@NotNull String token, @NotNull List<Integer> out) {
        String t = token.trim();
        int dash = t.indexOf('-');
        if (dash > 0 && dash < t.length() - 1) {
            try {
                int a = Integer.parseInt(t.substring(0, dash).trim());
                int b = Integer.parseInt(t.substring(dash + 1).trim());
                for (int i = Math.min(a, b); i <= Math.max(a, b); i++) out.add(i);
            } catch (NumberFormatException ignored) {}
        } else {
            try { out.add(Integer.parseInt(t)); } catch (NumberFormatException ignored) {}
        }
    }

    private static int asInt(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) { try { return Integer.parseInt(s.trim()); } catch (Exception ignored) {} }
        return def;
    }

    // ── Value Objects ──────────────────────────────────────────

    public static final class MenuDefinition {
        private final String name;
        private final int size;
        private final Map<String, MenuButton> buttons;

        public MenuDefinition(String name, int size, Map<String, MenuButton> buttons) {
            this.name = name;
            this.size = size;
            this.buttons = buttons;
        }

        @NotNull public String name() { return name; }
        public int size() { return size; }
        @NotNull public Map<String, MenuButton> buttons() { return buttons; }
        @Nullable public MenuButton button(@NotNull String key) { return buttons.get(key); }
    }

    public static final class MenuButton {
        private final String key;
        private final Material material;
        @Nullable private final String name;
        private final int amount;
        private final List<String> lore;
        private final List<Integer> slots;

        public MenuButton(String key, Material material, String name, int amount, List<String> lore, List<Integer> slots) {
            this.key = key;
            this.material = material;
            this.name = name;
            this.amount = amount;
            this.lore = lore;
            this.slots = slots;
        }

        @NotNull public String key() { return key; }
        @NotNull public Material material() { return material; }
        @Nullable public String name() { return name; }
        public int amount() { return amount; }
        @NotNull public List<String> lore() { return lore; }
        @NotNull public List<Integer> slots() { return slots; }
    }
}
