package com.surgirentregas.menu;

import com.surgirentregas.core.SurgirEntregasPlugin;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * Listener para el menu admin (45 slots) y para el cofre de rotacion (54 slots).
 */
public final class AdminMenuListener implements Listener {

    private final SurgirEntregasPlugin plugin;

    public AdminMenuListener(@NotNull SurgirEntregasPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onAdminClick(@NotNull InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof EntregasAdminMenu menu)) return;
        if (event.getClickedInventory() == null) return;
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        event.setCancelled(true);
        if (event.getWhoClicked() instanceof Player player
                && !player.hasPermission("entregas.admin")) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(
                    "<red>No tienes permiso para usar el menu admin.</red>"));
            player.closeInventory();
            return;
        }
        menu.handleClick(event.getSlot());
    }

    @EventHandler
    public void onAdminDrag(@NotNull InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof EntregasAdminMenu) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onChestClick(@NotNull InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof RotationChestMenu chest) {
            chest.onClick(event);
        }
    }

    @EventHandler
    public void onChestDrag(@NotNull InventoryDragEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof RotationChestMenu chest) {
            chest.onDrag(event);
        }
    }

    @EventHandler
    public void onChestClose(@NotNull InventoryCloseEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof RotationChestMenu chest) {
            chest.onClose(event);
        }
    }
}
