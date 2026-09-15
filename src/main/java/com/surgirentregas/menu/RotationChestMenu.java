package com.surgirentregas.menu;

import com.surgirentregas.core.SurgirEntregasPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Cofre virtual de rotacion — 54 slots, paginacion mediante flechas en
 * slots 45 (anterior) y 53 (siguiente).
 *
 * <p>Al abrirse carga en vivo los items almacenados en
 * {@code items_pool.yml} (pool serializado en Base64 con NBT preservado).
 * El administrador puede meter o sacar items libremente; al cerrar el
 * inventario se persiste el contenido en {@code items_pool.yml} de forma
 * asincrona.</p>
 *
 * <p>Slots 0-44 = contenido paginable (45 items por pagina).<br>
 * Slots 45 y 53 = flechas de navegacion.<br>
 * Slots 46-52 = paneles negros, slot 49 = indicador de pagina.</p>
 */
public final class RotationChestMenu implements InventoryHolder {

    /** Tamanio fijo del cofre virtual segun la especificacion. */
    public static final int SIZE = 54;
    public static final int CONTENT_PER_PAGE = 45;
    public static final int PREV_SLOT = 45;
    public static final int NEXT_SLOT = 53;
    public static final int INDICATOR_SLOT = 49;

    /** Slots reservados a navegacion / decoracion (no almacenan items). */
    public static final int[] NAV_SLOTS = {45, 46, 47, 48, 49, 50, 51, 52, 53};

    private final SurgirEntregasPlugin plugin;
    private final Player viewer;
    private Inventory inventory;
    private int page = 0;

    public RotationChestMenu(@NotNull SurgirEntregasPlugin plugin, @NotNull Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
    }

    public void open() {
        String title = plugin.getConfig().getString("chest.title",
                "<dark_gray><bold>SURGIR</bold></dark_gray> <gray>|</gray> <white>Cofre de Rotacion</white>");
        this.inventory = Bukkit.createInventory(this, SIZE, MenuItemBuilder.toComponent(title));
        render();
        viewer.openInventory(inventory);
    }

    /**
     * Carga los items del pool desde disco y los pinta en el inventario.
     * Reaplica los flags ocultos a cada item para que el lore del cofre
     * se vea limpio (sin atributos vanilla ni informacion tecnica).
     */
    private void render() {
        inventory.clear();

        var pool = plugin.deliveryConfig().poolItems();
        int total = pool.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) total / CONTENT_PER_PAGE));
        if (page < 0) page = 0;
        if (page >= totalPages) page = totalPages - 1;
        int start = page * CONTENT_PER_PAGE;

        // Slots 0..44 = items del pool con lore mejorado para el admin
        for (int s = 0; s < CONTENT_PER_PAGE; s++) {
            int idx = start + s;
            if (idx < total) {
                ItemStack clone = pool.get(idx).clone();
                // Mejora de lore del cofre: muestra cantidad exacta, recompensa y estado
                var m = clone.getItemMeta();
                if (m != null) {
                    List<net.kyori.adventure.text.Component> lore = m.lore() != null ? new ArrayList<>(m.lore()) : new ArrayList<>();
                    if (!lore.isEmpty()) lore.add(net.kyori.adventure.text.Component.empty());
                    double reward = plugin.deliveryConfig().rewardFor(clone.getType());
                    lore.add(MenuItemBuilder.toComponent("<gray>Cantidad requerida: <white>" + clone.getAmount() + "</white></gray>"));
                    lore.add(MenuItemBuilder.toComponent("<gray>Recompensa: <white>$" + String.format(java.util.Locale.US, "%,.0f", reward) + "</white></gray>"));
                    lore.add(MenuItemBuilder.toComponent("<dark_gray>Slot cofre: " + idx + "</dark_gray>"));
                    lore.add(MenuItemBuilder.toComponent("<gray>Rotacion: entrega " + (idx % 3 + 1) + "</gray>"));
                    m.lore(lore);
                    // Preservar nombre vanilla si no es custom
                    clone.setItemMeta(m);
                }
                inventory.setItem(s, MenuItemBuilder.applyHiddenFlags(clone));
            }
        }

        // Fila de navegacion: slots 45 y 53 son flechas; resto = paneles.
        inventory.setItem(PREV_SLOT, page > 0 ? navIcon(true, page, totalPages) : filler());
        inventory.setItem(NEXT_SLOT, page < totalPages - 1 ? navIcon(false, page, totalPages) : filler());
        for (int s = 46; s <= 52; s++) {
            if (s == INDICATOR_SLOT) {
                inventory.setItem(s, pageIndicator(page, totalPages));
            } else {
                inventory.setItem(s, filler());
            }
        }
    }

    private ItemStack filler() {
        return MenuItemBuilder.build(Material.BLACK_STAINED_GLASS_PANE, 1, " ", List.of());
    }

    private ItemStack navIcon(boolean prev, int page, int total) {
        String label = prev ? "<white><bold>ANTERIOR</bold></white>"
                : "<white><bold>SIGUIENTE</bold></white>";
        List<String> lore = List.of(
                "<gray>Pagina <white>" + (page + 1) + "</white>/<white>" + total + "</white></gray>");
        return MenuItemBuilder.build(Material.ARROW, 1, label, lore);
    }

    private ItemStack pageIndicator(int page, int total) {
        String label = "<white>PAGINA <gray>" + (page + 1)
                + " <dark_gray>/</dark_gray> <gray>" + total + "</gray></white>";
        List<String> lore = List.of(
                "<gray>Coloca items para anadirlos al pool</gray>",
                "<gray>Total items: <white>" + plugin.deliveryConfig().poolSize() + "</white></gray>",
                "<gray>Entregas activas: <white>" + Math.min(3, plugin.deliveryConfig().poolSize()) + "/3</white></gray>",
                "<dark_gray>Cierra para guardar y re-escanear</dark_gray>");
        return MenuItemBuilder.build(Material.PAPER, 1, label, lore);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // EVENTOS — gestionados por AdminMenuListener
    // ══════════════════════════════════════════════════════════════════════════

    public void onClick(@NotNull InventoryClickEvent event) {
        if (event.getClickedInventory() == null) return;
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            // Permitimos al jugador interactuar con su propio inventario
            // (mover items desde su inventario al cofre).
            return;
        }
        // Por defecto dejamos al jugador colocar/sacar items en los slots 0..44.
        event.setCancelled(false);

        int slot = event.getSlot();
        if (slot == PREV_SLOT) {
            event.setCancelled(true);
            if (page > 0) {
                page--;
                render();
            }
            return;
        }
        if (slot == NEXT_SLOT) {
            event.setCancelled(true);
            int total = plugin.deliveryConfig().poolSize();
            int totalPages = Math.max(1, (int) Math.ceil((double) total / CONTENT_PER_PAGE));
            if (page < totalPages - 1) {
                page++;
                render();
            }
            return;
        }
        if (slot >= PREV_SLOT && slot <= 53) {
            // Slots decorativos / indicador: no se permite modificarlos.
            event.setCancelled(true);
        }
    }

    public void onDrag(@NotNull InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() != this) return;
        // Cancelar drag en la fila de navegacion (45..53).
        for (int slot : event.getRawSlots()) {
            if (slot >= 45 && slot <= 53) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * Persiste el contenido del cofre al cerrar:
     * toma todos los items de los slots 0..44 y los guarda en
     * {@code items_pool.yml} serializados en Base64 (asincrono).
     */
    public void onClose(@NotNull InventoryCloseEvent event) {
        if (event.getView().getTopInventory().getHolder() != this) return;
        var snapshot = new ArrayList<ItemStack>();
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < CONTENT_PER_PAGE; i++) {
            ItemStack item = contents[i];
            if (item != null && !item.getType().isAir()) {
                snapshot.add(item.clone());
            }
        }
        plugin.deliveryConfig().setPool(snapshot);
        plugin.getLogger().info("[Cofre] Cofre de rotacion cerrado. " + snapshot.size() + " items persistidos en pool.");
    }

    @NotNull
    @Override
    public Inventory getInventory() {
        if (inventory == null) {
            throw new IllegalStateException("Cofre de rotacion aun no abierto.");
        }
        return inventory;
    }
}
