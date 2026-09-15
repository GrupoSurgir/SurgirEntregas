package com.surgirentregas.menu;

import com.surgirentregas.core.SurgirEntregasPlugin;
import com.surgirentregas.delivery.DeliveryConfig;
import com.surgirentregas.delivery.DeliveryDefinition;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Menu admin — 45 slots. Paleta gris/blanca exclusiva.
 * Slot 32: esmeralda economia (reemplaza cabezas de jugador).
 */
public final class EntregasAdminMenu implements InventoryHolder {

    private final SurgirEntregasPlugin plugin;
    private final AdminMenuHolder holder;
    private final MenuConfig.MenuDefinition layout;
    private Inventory inventory;

    public EntregasAdminMenu(@NotNull SurgirEntregasPlugin plugin, @NotNull AdminMenuHolder holder) {
        this.plugin = plugin;
        this.holder = holder;
        this.layout = plugin.menuConfig().menu("entregas_admin");
    }

    public void open() {
        this.inventory = Bukkit.createInventory(this, layout.size(),
                MenuItemBuilder.toComponent(plugin.getConfig().getString("admin-menu.title", "Admin Entregas")));
        build();
        holder.viewer().openInventory(inventory);
    }

    private void build() {
        inventory.clear();
        for (MenuConfig.MenuButton btn : layout.buttons().values()) {
            ItemStack item = MenuItemBuilder.build(btn);
            for (int slot : btn.slots()) if (slot >= 0 && slot < layout.size()) inventory.setItem(slot, item);
        }
        applyDynamicItems();
    }

    private void applyDynamicItems() {
        var deliveries = plugin.deliveryConfig().currentCycle();
        setDeliveryPreview(11, deliveries, 0);
        setDeliveryPreview(13, deliveries, 1);
        setDeliveryPreview(15, deliveries, 2);
        updateForceRotationDisplay();
        updateTimeDisplay();
        updateOrderDisplay();
        updatePriceDisplay();
        updateEconomyDisplay();
    }

    private void updateForceRotationDisplay() {
        List<Component> lore = new ArrayList<>();
        lore.add(MiniMessage.miniMessage().deserialize("<gray>Regenera las 3 entregas</gray>"));
        lore.add(MiniMessage.miniMessage().deserialize("<gray>para todos los jugadores</gray>"));
        lore.add(MiniMessage.miniMessage().deserialize("<dark_gray>Desbloquea el progreso del ciclo</dark_gray>"));
        lore.add(MiniMessage.miniMessage().deserialize("<white>Haz clic para forzar</white>"));
        inventory.setItem(18, MenuItemBuilder.build(Material.COMMAND_BLOCK, 1,
                MiniMessage.miniMessage().deserialize("<white><bold>Forzar reinicio global</bold></white>"), lore));
    }

    private void updateEconomyDisplay() {
        String type = plugin.getConfig().getString("economy.type", "VAULT");
        List<Component> lore = new ArrayList<>();
        lore.add(MiniMessage.miniMessage().deserialize("<gray>Tipo actual: <white>" + type + "</white></gray>"));
        lore.add(MiniMessage.miniMessage().deserialize("<gray>Global: <white>" + plugin.economy().format(plugin.deliveryConfig().globalReward()) + "</white></gray>"));
        lore.add(MiniMessage.miniMessage().deserialize("<gray>Final (3/3): <white>" + plugin.economy().format(plugin.deliveryConfig().finalReward()) + "</white></gray>"));
        lore.add(MiniMessage.miniMessage().deserialize("<dark_gray>Clic para cambiar</dark_gray>"));
        lore.add(MiniMessage.miniMessage().deserialize("<dark_gray>VAULT / PLAYERPOINTS / COMMAND</dark_gray>"));
        ItemStack icon = MenuItemBuilder.build(Material.EMERALD, 1,
                MiniMessage.miniMessage().deserialize("<white>Gestion de economia</white>"), lore);
        inventory.setItem(32, icon);
        if (layout.size() > 35) inventory.setItem(35, MenuItemBuilder.build(Material.BLACK_STAINED_GLASS_PANE, 1, "<dark_gray>-</dark_gray>", List.of()));
    }

    private void setDeliveryPreview(int slot, List<DeliveryDefinition> deliveries, int idx) {
        if (idx >= deliveries.size()) return;
        DeliveryDefinition d = deliveries.get(idx);
        if (d.locked()) {
            inventory.setItem(slot, MenuItemBuilder.build(DeliveryDefinition.LOCKED_MATERIAL, 1,
                    Component.text("Bloqueado"),
                    List.of(MiniMessage.miniMessage().deserialize("<gray>No hay items en el cofre</gray>"),
                            MiniMessage.miniMessage().deserialize("<dark_gray>Configura el cofre de rotacion</dark_gray>"))));
            return;
        }
        Component name = Component.text(pretty(d.material()));
        List<Component> lore = new ArrayList<>();
        lore.add(MiniMessage.miniMessage().deserialize("<gray>Cantidad: <white>" + d.amount() + "</white></gray>"));
        lore.add(MiniMessage.miniMessage().deserialize("<gray>Recompensa: <white>" + plugin.economy().format(d.reward()) + "</white></gray>"));
        lore.add(MiniMessage.miniMessage().deserialize("<dark_gray>Slot de rotacion " + (idx + 1) + "</dark_gray>"));
        inventory.setItem(slot, MenuItemBuilder.build(d.material(), Math.max(1, d.amount()), name, lore));
    }

    private void updateTimeDisplay() {
        AdminMenuHolder.TimeUnit unit = holder.timeUnit();
        long current = plugin.deliveryConfig().rotationSeconds();
        long h = current / 3600L;
        long m = (current % 3600L) / 60L;
        long s = current % 60L;
        String name = "TIEMPO  H" + pad(h) + ":"
                + (unit == AdminMenuHolder.TimeUnit.MINUTOS ? "<white>" : "<gray>")
                + "M" + pad(m) + (unit == AdminMenuHolder.TimeUnit.MINUTOS ? "</white>" : "</gray>") + ":"
                + (unit == AdminMenuHolder.TimeUnit.SEGUNDOS ? "<white>" : "<gray>")
                + "S" + pad(s) + (unit == AdminMenuHolder.TimeUnit.SEGUNDOS ? "</white>" : "</gray>");
        // Encabezado blanco, unidad activa resaltada en blanco brillante.
        List<Component> lore = new ArrayList<>();
        lore.add(MiniMessage.miniMessage().deserialize("<gray>Unidad activa: <white>" + unit.shortName + "</white></gray>"));
        lore.add(MiniMessage.miniMessage().deserialize("<dark_gray>Clic para cambiar unidad</dark_gray>"));
        lore.add(MiniMessage.miniMessage().deserialize("<dark_gray>Usa + / - para ajustar</dark_gray>"));
        inventory.setItem(30, MenuItemBuilder.build(Material.RECOVERY_COMPASS, 1,
                MiniMessage.miniMessage().deserialize("<white>" + name + "</white>"), lore));
    }

    private static String pad(long n) { return n < 10 ? "0" + n : Long.toString(n); }

    private void updateOrderDisplay() {
        DeliveryConfig.OrderMode mode = plugin.deliveryConfig().orderMode();
        String modeName = switch (mode) {
            case POR_MATERIALES -> "Por materiales";
            case ORDEN_DEL_COFRE -> "Orden del cofre";
            case ALEATORIO -> "Aleatorio";
        };
        List<Component> lore = new ArrayList<>();
        lore.add(MiniMessage.miniMessage().deserialize("<gray>Modo actual: <white>" + modeName + "</white></gray>"));
        lore.add(MiniMessage.miniMessage().deserialize("<dark_gray>Clic para alternar</dark_gray>"));
        inventory.setItem(33, MenuItemBuilder.build(Material.REPEATER, 1,
                MiniMessage.miniMessage().deserialize("<white>Orden de items</white>"), lore));
    }

    private void updatePriceDisplay() {
        DeliveryConfig.PriceMode mode = plugin.deliveryConfig().priceMode();
        List<Component> lore = new ArrayList<>();
        lore.add(MiniMessage.miniMessage().deserialize("<gray>Modo actual: <white>" + mode.name() + "</white></gray>"));
        lore.add(MiniMessage.miniMessage().deserialize("<gray>Global: <white>" + plugin.economy().format(plugin.deliveryConfig().globalReward()) + "</white></gray>"));
        lore.add(MiniMessage.miniMessage().deserialize("<gray>Final (3/3): <white>" + plugin.economy().format(plugin.deliveryConfig().finalReward()) + "</white></gray>"));
        lore.add(MiniMessage.miniMessage().deserialize("<dark_gray>Clic para alternar GLOBAL / UNICO</dark_gray>"));
        inventory.setItem(34, MenuItemBuilder.build(Material.GOLD_INGOT, 1,
                MiniMessage.miniMessage().deserialize("<white>Precio por entrega</white>"), lore));
    }

    public void handleClick(int slot) {
        switch (slot) {
            case 18 -> {
                plugin.deliveryConfig().clearCache();
                plugin.forceRotation();
                feedbackAdmin();
                build();
                holder.viewer().sendMessage(MiniMessage.miniMessage().deserialize(
                        plugin.getConfig().getString("prefix", "") + "<gray>Reinicio global forzado.</gray>"));
                holder.viewer().playSound(holder.viewer().getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.2f);
            }
            case 28 -> new RotationChestMenu(plugin, holder.viewer()).open();
            case 29 -> { holder.incrementTime(); feedbackAdmin(); build(); }
            case 30 -> { holder.toggleTimeUnit(); feedbackAdmin(); build(); }
            case 31 -> { holder.decrementTime(); feedbackAdmin(); build(); }
            case 32 -> {
                String cur = plugin.getConfig().getString("economy.type", "VAULT").toUpperCase(Locale.ROOT);
                String next = switch (cur) {
                    case "VAULT" -> "PLAYERPOINTS";
                    case "PLAYERPOINTS" -> "COMMAND";
                    case "COMMAND" -> "VAULT";
                    default -> "VAULT";
                };
                plugin.getConfig().set("economy.type", next);
                plugin.saveConfig();
                feedbackAdmin();
                build();
            }
            case 33 -> {
                DeliveryConfig.OrderMode cur = plugin.deliveryConfig().orderMode();
                DeliveryConfig.OrderMode next = switch (cur) {
                    case POR_MATERIALES -> DeliveryConfig.OrderMode.ORDEN_DEL_COFRE;
                    case ORDEN_DEL_COFRE -> DeliveryConfig.OrderMode.ALEATORIO;
                    case ALEATORIO -> DeliveryConfig.OrderMode.POR_MATERIALES;
                };
                plugin.deliveryConfig().setOrderMode(next);
                feedbackAdmin(); build();
            }
            case 34 -> {
                DeliveryConfig.PriceMode cur = plugin.deliveryConfig().priceMode();
                DeliveryConfig.PriceMode next = cur == DeliveryConfig.PriceMode.GLOBAL ? DeliveryConfig.PriceMode.UNICO : DeliveryConfig.PriceMode.GLOBAL;
                plugin.deliveryConfig().setPriceMode(next);
                feedbackAdmin(); build();
            }
            default -> {}
        }
    }

    private void feedbackAdmin() {
        holder.viewer().sendActionBar(MenuItemBuilder.toComponent(
                plugin.messages().actionBar("admin-changed", "<gray>Configuracion actualizada.</gray>")));
    }

    private static String pretty(@NotNull Material material) {
        StringBuilder sb = new StringBuilder();
        String[] parts = material.name().toLowerCase(Locale.ROOT).split("_");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(' ');
            if (parts[i].isEmpty()) continue;
            sb.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        }
        return sb.toString();
    }

    @NotNull @Override public Inventory getInventory() {
        if (inventory == null) throw new IllegalStateException("Menu admin aun no abierto.");
        return inventory;
    }
}
