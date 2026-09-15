package com.surgirentregas.menu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builder de {@link ItemStack} a partir de definiciones YAML.
 *
 * <p>El nombre y el lore admiten tanto codigos '&' clasicos como MiniMessage
 * (etiquetas {@code <...>}). El builder detecta cual usar y aplica el
 * serializador correspondiente.</p>
 *
 * <p>Por defecto oculta los flags vanilla de tooltip (atributos, encantamientos
 * adicionales, etc.) para que el lore se vea limpio y estilizado sin
 * informacion tecnica del item (ej. "15 componente(s)", "minecraft:nether_star").</p>
 */
public final class MenuItemBuilder {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** Flags que se ocultan SIEMPRE en cualquier item construido por el builder. */
    private static final Set<ItemFlag> HIDDEN_FLAGS = EnumSet.of(
            ItemFlag.HIDE_ADDITIONAL_TOOLTIP,
            ItemFlag.HIDE_ATTRIBUTES,
            ItemFlag.HIDE_ENCHANTS,
            ItemFlag.HIDE_UNBREAKABLE
    );

    private MenuItemBuilder() {}

    /**
     * Construye un ItemStack a partir de un boton YAML.
     * <p>Si el material no es un item valido (ej. bloque WALL_*), devuelve
     * un fallback de {@link Material#BARRIER} para evitar
     * {@code IllegalArgumentException}.</p>
     */
    @NotNull
    public static ItemStack build(@NotNull MenuLayout.ButtonDefinition def) {
        Material safe = ensureItem(def.material());
        ItemStack item = new ItemStack(safe, def.amount());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        applyNameAndLore(meta, def.name(), def.lore());
        applyHiddenFlags(meta);

        item.setItemMeta(meta);
        return item;
    }

    /**
     * Construye un ItemStack basico (material + amount + name + lore).
     */
    @NotNull
    public static ItemStack build(@NotNull Material material, int amount,
                                  @Nullable String name, @Nullable List<String> lore) {
        Material safe = ensureItem(material);
        ItemStack item = new ItemStack(safe, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        applyNameAndLore(meta, name, lore);
        applyHiddenFlags(meta);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Construye un ItemStack con un Component ya pre-formateado (no se aplica
     * conversion MiniMessage/legacy).
     */
    @NotNull
    public static ItemStack build(@NotNull Material material, int amount,
                                  @Nullable Component displayName,
                                  @Nullable List<Component> loreLines) {
        Material safe = ensureItem(material);
        ItemStack item = new ItemStack(safe, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        if (displayName != null) meta.displayName(displayName);
        if (loreLines != null && !loreLines.isEmpty()) {
            meta.lore(new ArrayList<>(loreLines));
        }
        applyHiddenFlags(meta);
        item.setItemMeta(meta);
        return item;
    }

    private static void applyNameAndLore(@NotNull ItemMeta meta,
                                         @Nullable String name,
                                         @Nullable List<String> lore) {
        if (name != null) {
            meta.displayName(toComponent(name));
        }
        if (lore != null && !lore.isEmpty()) {
            List<Component> loreLines = new ArrayList<>(lore.size());
            for (String line : lore) loreLines.add(toComponent(line));
            meta.lore(loreLines);
        }
    }

    private static void applyHiddenFlags(@NotNull ItemMeta meta) {
        for (ItemFlag flag : HIDDEN_FLAGS) {
            if (meta.hasItemFlag(flag)) continue;
            meta.addItemFlags(flag);
        }
    }

    /**
     * Aplica los flags ocultos a un {@link ItemStack} ya construido (util para
     * items que vienen del pool o del inventario del jugador y deben
     * mostrarse sin informacion tecnica vanilla).
     *
     * <p>Devuelve el MISMO ItemStack (mutado) para encadenar llamadas.</p>
     */
    @NotNull
    public static ItemStack applyHiddenFlags(@NotNull ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        applyHiddenFlags(meta);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Garantiza que el material sea instanciable como item. Si el material
     * pertenece a una familia de bloques no-item (ej. {@code *_WALL_HEAD},
     * {@code WALL_TORCH}, {@code WALL_SIGN}), lo reemplaza por un equivalente
     * item o por {@link Material#BARRIER}.
     */
    @NotNull
    public static Material ensureItem(@NotNull Material material) {
        if (material.isItem()) return material;
        String name = material.name().toUpperCase(Locale.ROOT);
        // Mapeo de reemplazos para variantes WALL_* que NO son items.
        Material replacement = switch (name) {
            case "WITHER_SKELETON_WALL_SKULL" -> Material.WITHER_SKELETON_SKULL;
            case "SKELETON_WALL_SKULL" -> Material.SKELETON_SKULL;
            case "PLAYER_WALL_HEAD" -> Material.PLAYER_HEAD;
            case "ZOMBIE_WALL_HEAD" -> Material.ZOMBIE_HEAD;
            case "CREEPER_WALL_HEAD" -> Material.CREEPER_HEAD;
            case "DRAGON_WALL_HEAD" -> Material.DRAGON_HEAD;
            case "PIGLIN_WALL_HEAD" -> Material.PIGLIN_HEAD;
            case "WALL_TORCH" -> Material.TORCH;
            case "REDSTONE_WALL_TORCH" -> Material.REDSTONE_TORCH;
            case "SOUL_WALL_TORCH" -> Material.SOUL_TORCH;
            case "WALL_SIGN" -> Material.OAK_SIGN;
            case "WALL_HANGING_SIGN" -> Material.OAK_HANGING_SIGN;
            case "WALL_BANNER" -> Material.WHITE_BANNER;
            case "WALL_CORAL" -> Material.BRAIN_CORAL;
            default -> Material.BARRIER;
        };
        if (replacement.isItem()) return replacement;
        return Material.BARRIER;
    }

    /**
     * Convierte una cadena a Component, detectando si usa MiniMessage o
     * codigos '&' clasicos.
     */
    @NotNull
    public static Component toComponent(@NotNull String text) {
        if (text.contains("<") && text.contains(">")) {
            try {
                return MINI.deserialize(text);
            } catch (Exception ignored) {
                return LEGACY.deserialize(text);
            }
        }
        return LEGACY.deserialize(text);
    }
}
