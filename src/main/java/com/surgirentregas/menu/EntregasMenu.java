package com.surgirentregas.menu;

import com.surgirentregas.core.SurgirEntregasPlugin;
import com.surgirentregas.delivery.DeliveryDefinition;
import com.surgirentregas.delivery.DeliveryService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Menu del jugador — 27 slots.
 * Paleta: exclusivamente &8 (dark_gray), &7 (gray), &f (white).
 * Slot 18: indicador dinamico LIME/PURPLE/RED + CLOCK con tiempo real.
 */
public final class EntregasMenu implements InventoryHolder {

    private final SurgirEntregasPlugin plugin;
    private final Player viewer;
    private Inventory inventory;
    private final MenuLayout.MenuDefinition layout;
    private final AtomicReference<Runnable> clockTaskRef = new AtomicReference<>();

    public EntregasMenu(@NotNull SurgirEntregasPlugin plugin, @NotNull Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.layout = plugin.menuLayout().menu("entregas");
    }

    public void open() {
        this.inventory = Bukkit.createInventory(this, layout.size(),
                MenuItemBuilder.toComponent(plugin.getConfig().getString("menu.title", "Entregas")));
        build();
        viewer.openInventory(inventory);
        startClockTask();
        viewer.sendActionBar(MenuItemBuilder.toComponent(
                plugin.messages().actionBar("open", "<gray>Bienvenido a <white>Entregas</white>.</gray>")));
    }

    private void build() {
        inventory.clear();
        for (MenuLayout.ButtonDefinition btn : layout.buttons().values()) {
            ItemStack item = MenuItemBuilder.build(btn);
            for (int slot : btn.slots()) if (slot >= 0 && slot < layout.size()) inventory.setItem(slot, item);
        }
        applyDynamicItems();
    }

    private void applyDynamicItems() {
        List<DeliveryDefinition> deliveries = plugin.deliveryService().currentDeliveries();
        if (deliveries.size() == 1) {
            setBlockedSlot(11);
            setDeliveryButton(13, deliveries.get(0));
            setBlockedSlot(15);
        } else if (deliveries.size() == 2) {
            setDeliveryButton(11, deliveries.get(0));
            setDeliveryButton(13, deliveries.get(1));
            setBlockedSlot(15);
        } else {
            if (!deliveries.isEmpty()) setDeliveryButton(11, deliveries.get(0)); else setBlockedSlot(11);
            if (deliveries.size() > 1) setDeliveryButton(13, deliveries.get(1)); else setBlockedSlot(13);
            if (deliveries.size() > 2) setDeliveryButton(15, deliveries.get(2)); else setBlockedSlot(15);
        }
        setBottomSummary(deliveries);
        setTopIcon();
    }

    private void setBlockedSlot(int slot) {
        inventory.setItem(slot, MenuItemBuilder.build(Material.BLACK_STAINED_GLASS_PANE, 1,
                "<dark_gray>-</dark_gray>", List.of()));
    }

    private void startClockTask() {
        Runnable prev = clockTaskRef.getAndSet(() -> {});
        if (prev != null) try { prev.run(); } catch (Exception ignored) {}
        int taskId = Bukkit.getScheduler().runTaskTimer(plugin, this::updateClockItem, 20L, 20L).getTaskId();
        clockTaskRef.set(() -> Bukkit.getScheduler().cancelTask(taskId));
    }

    private void updateClockItem() {
        if (viewer.getOpenInventory().getTopInventory() != inventory) return;
        long remaining = plugin.remainingSeconds();
        ItemStack slot18 = inventory.getItem(18);
        if (slot18 == null || slot18.getType() != Material.CLOCK) return;
        var meta = slot18.getItemMeta();
        if (meta == null) return;
        meta.displayName(MenuItemBuilder.toComponent("<white>Tiempo restante</white>"));
        List<Component> lore = new ArrayList<>();
        lore.add(MenuItemBuilder.toComponent("<gray>Tiempo para el proximo ciclo</gray>"));
        lore.add(MenuItemBuilder.toComponent("<white>" + formatTime(Math.max(0L, remaining)) + "</white>"));
        if (remaining <= 0) {
            lore.add(MenuItemBuilder.toComponent("<dark_gray>Reiniciando ciclo...</dark_gray>"));
        } else {
            lore.add(MenuItemBuilder.toComponent("<dark_gray>Se reiniciara automaticamente</dark_gray>"));
        }
        meta.lore(lore);
        slot18.setItemMeta(meta);
        // Si expiro, el CycleTask del plugin regenerara en <1s; el menu se refrescara al siguiente tick si el jugador sigue con el inventario abierto
        if (remaining <= 0) {
            // No bloquear: deja el reloj en 0:00 visible y evita congelamiento
        }
    }

    public void cancelClockTask() {
        Runnable r = clockTaskRef.getAndSet(null);
        if (r != null) try { r.run(); } catch (Exception ignored) {}
    }

    private static String formatTime(long seconds) {
        return String.format("%d:%02d", seconds / 60L, seconds % 60L);
    }

    private static String formatCount(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) return String.format("%d", (long) value);
        return String.format("%.1f", value);
    }

    private boolean isTopEnabled() {
        return plugin.getConfig().getBoolean("top.enabled", true);
    }

    private void setDeliveryButton(int slot, DeliveryDefinition d) {
        if (d == null) { setBlockedSlot(slot); return; }
        if (d.locked()) {
            inventory.setItem(slot, MenuItemBuilder.build(Material.BARRIER, 1, "<white>Bloqueado</white>",
                    List.of("<gray>No hay items en el cofre</gray>", "<dark_gray>El administrador debe configurarlo</dark_gray>")));
            return;
        }
        boolean completed = plugin.stats().hasCompleted(viewer.getUniqueId(), d.key());
        if (completed) {
            inventory.setItem(slot, MenuItemBuilder.build(Material.BARRIER, 1, "<white>Completada</white>",
                    List.of("<gray>Entrega finalizada con exito</gray>", "<dark_gray>Espera al proximo ciclo</dark_gray>")));
            return;
        }
        int required = d.amount();
        ItemStack icon;
        if (d.template() != null) {
            icon = d.template().clone();
            icon.setAmount(Math.max(1, required));
        } else {
            icon = new ItemStack(d.material(), Math.max(1, required));
        }
        int has = plugin.deliveryService().countInInventory(viewer, d);
        var meta = icon.getItemMeta();
        if (meta != null) {
            List<Component> lore = new ArrayList<>();
            lore.add(MenuItemBuilder.toComponent("<gray>Requiere: <white>" + required + "</white></gray>"));
            lore.add(MenuItemBuilder.toComponent("<gray>En inventario: <white>" + has + "</white></gray>"));
            lore.add(MenuItemBuilder.toComponent("<dark_gray>" + deliveryState(has, required) + "</dark_gray>"));
            meta.lore(lore);
            icon.setItemMeta(meta);
        }
        MenuItemBuilder.applyHiddenFlags(icon);
        inventory.setItem(slot, icon);
    }

    private String deliveryState(int has, int required) {
        if (has <= 0) return "No tienes este material";
        if (has < required) return "Te falta cantidad";
        return "Listo para entregar";
    }

    private void setBottomSummary(List<DeliveryDefinition> deliveries) {
        long completed = deliveries.stream().filter(d -> !d.locked() && plugin.stats().hasCompleted(viewer.getUniqueId(), d.key())).count();
        if (completed >= 3 || (deliveries.stream().filter(d -> !d.locked()).count() > 0 && completed >= deliveries.stream().filter(d -> !d.locked()).count())) {
            long remaining = plugin.remainingSeconds();
            List<Component> lore = new ArrayList<>();
            lore.add(MenuItemBuilder.toComponent("<gray>Tiempo para el proximo ciclo</gray>"));
            lore.add(MenuItemBuilder.toComponent("<white>" + formatTime(remaining) + "</white>"));
            lore.add(MenuItemBuilder.toComponent("<dark_gray>Se reiniciara automaticamente</dark_gray>"));
            inventory.setItem(18, MenuItemBuilder.build(Material.CLOCK, 1,
                    MenuItemBuilder.toComponent("<white>Tiempo restante</white>"), lore));
            return;
        }
        int activeCount = (int) deliveries.stream().filter(d -> !d.locked()).count();
        if (activeCount == 0) {
            inventory.setItem(18, MenuItemBuilder.build(Material.BLACK_STAINED_GLASS_PANE, 1, "<dark_gray>-</dark_gray>", List.of()));
            return;
        }
        boolean hasFull = false;
        boolean hasPartial = false;
        for (DeliveryDefinition d : deliveries) {
            if (d.locked()) continue;
            if (plugin.stats().hasCompleted(viewer.getUniqueId(), d.key())) continue;
            int has = plugin.deliveryService().countInInventory(viewer, d);
            if (has >= d.amount()) hasFull = true;
            else if (has > 0) hasPartial = true;
        }
        if (hasFull) {
            inventory.setItem(18, MenuItemBuilder.build(Material.LIME_DYE, 1, "<white>Disponible</white>",
                    List.of("<gray>Tienes los materiales</gray>", "<gray>Haz clic para entregar</gray>", "<dark_gray>Recompensa proporcional</dark_gray>")));
        } else if (hasPartial) {
            inventory.setItem(18, MenuItemBuilder.build(Material.PURPLE_DYE, 1, "<white>En progreso</white>",
                    List.of("<gray>Sigues recolectando</gray>", "<gray>Te faltan items</gray>", "<dark_gray>Entrega parcial disponible</dark_gray>")));
        } else {
            inventory.setItem(18, MenuItemBuilder.build(Material.RED_DYE, 1, "<white>Sin items</white>",
                    List.of("<gray>No tienes materiales</gray>", "<gray>Consigue los del cofre</gray>", "<dark_gray>Revisa las ranuras superiores</dark_gray>")));
        }
    }

    private void setTopIcon() {
        if (!isTopEnabled()) {
            inventory.setItem(26, MenuItemBuilder.build(Material.BLACK_STAINED_GLASS_PANE, 1, "<dark_gray>-</dark_gray>", List.of()));
            return;
        }
        MenuLayout.ButtonDefinition topBtn = layout.button("btn-26");
        String displayName = topBtn != null && topBtn.name() != null ? topBtn.name() : "TOP ENTREGAS";
        var top = plugin.stats().top(7);
        List<Component> loreLines = new ArrayList<>();
        if (top.isEmpty()) {
            loreLines.add(MenuItemBuilder.toComponent("<gray>Sin entregas completadas</gray>"));
            loreLines.add(MenuItemBuilder.toComponent("<dark_gray>Se el primero en entregar</dark_gray>"));
        } else {
            loreLines.add(MenuItemBuilder.toComponent("<gray>Ranking global</gray>"));
            int rank = 1;
            for (var entry : top) {
                loreLines.add(MenuItemBuilder.toComponent("<white>" + rank + ". " + entry.name() + "</white> <gray>-</gray> <white>" + formatCount(entry.deliveries()) + "</white>"));
                rank++;
            }
            loreLines.add(MenuItemBuilder.toComponent("<dark_gray>Actualizado al completar entregas</dark_gray>"));
        }
        inventory.setItem(26, MenuItemBuilder.build(Material.NETHER_STAR, 1, MenuItemBuilder.toComponent(displayName), loreLines));
    }

    public void handleClick(int slot, org.bukkit.event.inventory.ClickType click) {
        if (slot == 11 || slot == 13 || slot == 15) {
            int idx = slot == 11 ? 0 : slot == 13 ? 1 : 2;
            processSingleDelivery(idx);
            return;
        }
        if (slot == 18) {
            var deliveries = plugin.deliveryService().currentDeliveries();
            long completed = deliveries.stream().filter(d -> !d.locked() && plugin.stats().hasCompleted(viewer.getUniqueId(), d.key())).count();
            long active = deliveries.stream().filter(d -> !d.locked()).count();
            if (completed >= active && active > 0) {
                viewer.sendMessage(MenuItemBuilder.toComponent("<white>Faltan " + formatTime(plugin.remainingSeconds()) + " para el proximo ciclo.</white>"));
                viewer.playSound(viewer.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 0.5f);
            } else {
                processDeliverAll();
            }
            return;
        }
        if (slot == 26) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                viewer.closeInventory();
                if (isTopEnabled()) sendTopToChat();
            });
        }
    }

    private void scheduleRefresh() { Bukkit.getScheduler().runTask(plugin, this::build); }

    private void processDeliverAll() {
        var deliveries = plugin.deliveryService().currentDeliveries();
        double credited = 0.0;
        int completedNow = 0;
        for (DeliveryDefinition d : deliveries) {
            if (d.locked()) continue;
            var result = plugin.deliveryService().submit(viewer, d);
            if (result instanceof DeliveryService.Result.Completed c) { credited += c.credited(); completedNow++; }
            else if (result instanceof DeliveryService.Result.Partial p) credited += p.credited();
        }
        if (completedNow > 0 || credited > 0) {
            viewer.playSound(viewer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
            String raw = plugin.getConfig().getString("messages.delivered", "<prefix><gray>Entrega completada: +</gray><white>%reward%</white>");
            raw = raw.replace("<prefix>", plugin.getConfig().getString("prefix", ""));
            raw = raw.replace("%reward%", formatReward(credited));
            viewer.sendMessage(MenuItemBuilder.toComponent(raw));
            viewer.sendActionBar(MenuItemBuilder.toComponent("<gray>+" + formatReward(credited) + "</gray>"));
            checkFinalAchievement();
        } else {
            viewer.sendMessage(MenuItemBuilder.toComponent(plugin.getConfig().getString("prefix", "") + "<gray>Te faltan materiales.</gray>"));
            viewer.sendActionBar(MenuItemBuilder.toComponent("<gray>Te faltan materiales.</gray>"));
            viewer.playSound(viewer.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.7f);
        }
        scheduleRefresh();
    }

    private void sendTopToChat() {
        if (!isTopEnabled()) return;
        var top = plugin.stats().top(7);
        viewer.sendMessage(MenuItemBuilder.toComponent("<white><bold>TOP ENTREGAS</bold></white>"));
        if (top.isEmpty()) { viewer.sendMessage(MenuItemBuilder.toComponent("<gray>Sin entregas completadas</gray>")); return; }
        int rank = 1;
        for (var entry : top) {
            viewer.sendMessage(MenuItemBuilder.toComponent("<white>" + rank + ". " + entry.name() + "</white> <gray>-</gray> <white>" + formatCount(entry.deliveries()) + "</white>"));
            rank++;
        }
    }

    private void processSingleDelivery(int idx) {
        var deliveries = plugin.deliveryService().currentDeliveries();
        if (idx < 0 || idx >= deliveries.size()) return;
        DeliveryDefinition d = deliveries.get(idx);
        if (d.locked()) { viewer.sendActionBar(MenuItemBuilder.toComponent("<gray>No hay entrega asignada en este slot.</gray>")); return; }
        var result = plugin.deliveryService().submit(viewer, d);
        if (result instanceof DeliveryService.Result.Completed c) { onCredited(c.credited()); checkFinalAchievement(); }
        else if (result instanceof DeliveryService.Result.Partial p) onPartial(p);
        else if (result instanceof DeliveryService.Result.AlreadyCompleted) { viewer.sendActionBar(MenuItemBuilder.toComponent("<gray>Ya completaste esta entrega.</gray>")); viewer.playSound(viewer.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.5f); }
        else if (result instanceof DeliveryService.Result.NoItems) { viewer.sendActionBar(MenuItemBuilder.toComponent("<gray>No tienes este material.</gray>")); viewer.playSound(viewer.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.7f); }
        else if (result instanceof DeliveryService.Result.ConsumeFailed) viewer.sendActionBar(MenuItemBuilder.toComponent("<gray>Error al entregar.</gray>"));
        scheduleRefresh();
    }

    private void onCredited(double credited) {
        viewer.playSound(viewer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
        String raw = plugin.getConfig().getString("messages.delivered", "<prefix><gray>Entrega completada: +</gray><white>%reward%</white>");
        raw = raw.replace("<prefix>", plugin.getConfig().getString("prefix", ""));
        raw = raw.replace("%reward%", formatReward(credited));
        viewer.sendMessage(MenuItemBuilder.toComponent(raw));
        viewer.sendActionBar(MenuItemBuilder.toComponent("<gray>+" + formatReward(credited) + "</gray>"));
    }

    private void onPartial(DeliveryService.Result.Partial p) {
        viewer.playSound(viewer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.1f);
        viewer.sendActionBar(MenuItemBuilder.toComponent("<gray>+" + formatReward(p.credited()) + " <white>" + p.current() + "/" + p.required() + "</white></gray>"));
    }

    private void checkFinalAchievement() {
        var curr = plugin.deliveryService().currentDeliveries();
        int active = (int) curr.stream().filter(x -> !x.locked()).count();
        long done = curr.stream().filter(x -> !x.locked() && plugin.stats().hasCompleted(viewer.getUniqueId(), x.key())).count();
        if (active > 0 && done >= active) triggerFinalAchievement();
    }

    private void triggerFinalAchievement() {
        double finalReward = plugin.deliveryConfig().finalReward();
        if (finalReward > 0) plugin.economy().depositFinal(viewer, finalReward);
        viewer.playSound(viewer.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
    }

    @NotNull private static String formatReward(double reward) { return String.format(java.util.Locale.US, "%,.0f", reward); }

    @NotNull @Override public Inventory getInventory() {
        if (inventory == null) throw new IllegalStateException("Menu aun no abierto.");
        return inventory;
    }
}
