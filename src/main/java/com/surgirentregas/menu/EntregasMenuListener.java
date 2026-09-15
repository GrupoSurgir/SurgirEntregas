package com.surgirentregas.menu;

import com.surgirentregas.core.SurgirEntregasPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * Listener para el menu del jugador (27 slots).
 *
 * <p>Bloquea drags en el inventario del menu y delega los clicks al holder
 * correspondiente.</p>
 */
public final class EntregasMenuListener implements Listener {

    private final SurgirEntregasPlugin plugin;

    public EntregasMenuListener(@NotNull SurgirEntregasPlugin plugin) {
        this.plugin = plugin;
    }

@EventHandler
    public void onClick(@NotNull InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof EntregasMenu menu)) return;
        if (event.getClickedInventory() == null) return;
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        // FIRST-LINE CANCELLATION: cancelar INMEDIATAMENTE para evitar movimento/duplicacao fisica
        event.setCancelled(true);
        // Mantenimiento: bloquea la interaccion con las GUIs de entrega sin expulsar.
        if (event.getWhoClicked() instanceof org.bukkit.entity.Player player) {
            if (!plugin.maintenanceManager().canUse(player)) {
                player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(
                        plugin.getConfig().getString("maintenance.messages.blocked-access",
                                "<gray>El sistema de entregas se encuentra en mantenimiento. Intenta mas tarde.</gray>")));
                return;
            }
            int slot = event.getSlot();
            int idx = -1;
            if (slot == 11) idx = 0;
            else if (slot == 13) idx = 1;
            else if (slot == 15) idx = 2;
            if (idx >= 0) {
                var deliveries = plugin.deliveryService().currentDeliveries();
                if (idx < deliveries.size()) {
                    var d = deliveries.get(idx);
                    if (!d.locked() && plugin.stats().hasCompleted(player.getUniqueId(), d.key())) {
                        player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(
                                plugin.getConfig().getString("prefix", "") + "<gray>Entrega ya completada. Bloqueada hasta reinicio global.</gray>"));
                        // Menu se refrescara en handleClick
                    }
                }
            }
        }
        menu.handleClick(event.getSlot(), event.getClick());
    }

    @EventHandler
    public void onDrag(@NotNull InventoryDragEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof EntregasMenu) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(@NotNull InventoryCloseEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof EntregasMenu menu) {
            menu.cancelClockTask();
        }
    }
}
