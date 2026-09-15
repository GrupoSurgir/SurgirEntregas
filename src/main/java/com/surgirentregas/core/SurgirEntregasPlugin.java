package com.surgirentregas.core;

import com.surgirentregas.delivery.DeliveryConfig;
import com.surgirentregas.delivery.DeliveryService;
import com.surgirentregas.economy.EconomyHook;
import com.surgirentregas.menu.AdminMenuHolder;
import com.surgirentregas.menu.AdminMenuListener;
import com.surgirentregas.menu.EntregasAdminMenu;
import com.surgirentregas.menu.EntregasMenu;
import com.surgirentregas.menu.EntregasMenuListener;
import com.surgirentregas.menu.MessagesConfig;
import com.surgirentregas.menu.MenuLayout;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.io.File;

/**
 * SurgirEntregas — sistema nativo, silencioso y vanilla de entregas rotativas.
 * Persistencia SQLite-ready via ranking.json / cycle.dat / items_pool.yml.
 */
public final class SurgirEntregasPlugin extends JavaPlugin {

    private DeliveryConfig deliveryConfig;
    private DeliveryService deliveryService;
    private DeliveryStats stats;
    private EconomyHook economy;
    private MenuLayout menuLayout;
    private MessagesConfig messages;
    private MaintenanceManager maintenanceManager;

    private BukkitTask cycleTask;

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        saveDefaultConfig();

        ensureResource("menus/entregas.yml");
        ensureResource("menus/entregas_admin.yml");
        ensureResource("items_pool.yml");

        this.deliveryConfig = new DeliveryConfig(this);
        this.deliveryConfig.load();

        this.economy = new EconomyHook(this);
        this.stats = new DeliveryStats(this);

        // ── Validacion estricta del ciclo inicial (fix 0:00 tras reinicio) ──
        File cycleFile = new File(getDataFolder(), "cycle.dat");
        boolean needsNew = false;
        String reason = "";
        long now = System.currentTimeMillis();
        long start = stats.cycleStartTime();
        long elapsed = (now - start) / 1000L;
        if (!cycleFile.exists()) {
            needsNew = true; reason = "inexistente";
        } else if (start <= 0) {
            needsNew = true; reason = "timestamp <=0 (" + start + ")";
        } else if (start > now + 5000L) {
            needsNew = true; reason = "timestamp futuro (" + start + " > " + now + ")";
        } else if (elapsed >= deliveryConfig.rotationSeconds() || remainingSeconds() <= 0) {
            needsNew = true; reason = "expirado (" + elapsed + "s >= " + deliveryConfig.rotationSeconds() + "s)";
        }
        if (needsNew) {
            deliveryConfig.regenerateCycle();
            stats.newCycle();
            getLogger().warning("[Cycle] Ciclo inicial invalido (" + reason + ") -> regenerado ciclo nuevo completo (" + deliveryConfig.rotationSeconds() + "s).");
        } else {
            if (deliveryConfig.currentCycle().isEmpty()) deliveryConfig.regenerateCycle();
            getLogger().info("[Cycle] Ciclo restaurado: restante " + remainingSeconds() + "s / " + deliveryConfig.rotationSeconds() + "s.");
        }

        this.deliveryService = new DeliveryService(this);
        this.menuLayout = new MenuLayout(this);
        this.menuLayout.load();
        this.messages = new MessagesConfig(this);
        this.messages.load();
        this.maintenanceManager = new MaintenanceManager(this);

        PluginCommand cmdEntregas = getCommand("entregas");
        if (cmdEntregas != null) {
            com.surgirentregas.commands.EntregasCommand handler =
                    new com.surgirentregas.commands.EntregasCommand(this);
            cmdEntregas.setExecutor(handler);
        }
        PluginCommand cmdAdmin = getCommand("entregasadmin");
        if (cmdAdmin != null) {
            cmdAdmin.setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Solo jugadores pueden usar este comando.");
                    return true;
                }
                if (!p.hasPermission("entregas.admin")) {
                    p.sendMessage(MiniMessage.miniMessage().deserialize(
                            "<gray>No tienes permiso para abrir el menu admin.</gray>"));
                    return true;
                }
                if (args.length > 0 && args[0].equalsIgnoreCase("maintenance")) {
                    String[] sub = new String[args.length - 1];
                    System.arraycopy(args, 1, sub, 0, args.length - 1);
                    return maintenanceManager.handleCommand(p, sub);
                }
                new EntregasAdminMenu(this, new AdminMenuHolder(this, p)).open();
                return true;
            });
        }

        Bukkit.getPluginManager().registerEvents(new EntregasMenuListener(this), this);
        Bukkit.getPluginManager().registerEvents(new AdminMenuListener(this), this);

        startCycleTask();

        getLogger().info("    ███████ ██    ██ ██████   ██████  ██ ██████ ");
        getLogger().info("    ██      ██    ██ ██   ██ ██       ██ ██   ██ ");
        getLogger().info("    ███████ ██    ██ ██████  ██   ███ ██ ██████ ");
        getLogger().info("         ██ ██    ██ ██   ██ ██    ██ ██ ██   ██ ");
        getLogger().info("    ███████  ██████  ██   ██  ██████  ██ ██   ██    E N T R E G A S");
        getLogger().info("");
        getLogger().info("    SURGIR — Sistema avanzado de economía y misiones técnicas.");
        getLogger().info("    Autor: Samuel Buritica");
        getLogger().info("");

        boolean oraxen = Bukkit.getPluginManager().isPluginEnabled("Oraxen");
        boolean ia = Bukkit.getPluginManager().isPluginEnabled("ItemsAdder");
        if (oraxen) getLogger().info("[Modelos] Oraxen detectado (preservacion NBT/CustomModelData).");
        else if (ia) getLogger().info("[Modelos] ItemsAdder detectado (preservacion NBT/CustomModelData).");
        else getLogger().info("[Modelos] Vanilla nativo (NBT/CustomModelData).");

        economy.detectAndLog();
        getLogger().info("Rotacion: " + deliveryConfig.rotationSeconds() + "s | Global: $"
                + (long) deliveryConfig.globalReward() + " | Final: $" + (long) deliveryConfig.finalReward()
                + " | Orden: " + deliveryConfig.orderMode() + " | Precio: " + deliveryConfig.priceMode());
    }

    @Override
    public void onDisable() {
        cancelCycleTask();
        if (stats != null) {
            stats.saveRankingSync();
            stats.saveCycleStateSync();
        }
        if (deliveryConfig != null) deliveryConfig.savePoolSync();
    }

    private void ensureResource(@NotNull String path) {
        File target = new File(getDataFolder(), path);
        if (!target.exists()) {
            try {
                target.getParentFile().mkdirs();
                saveResource(path, false);
            } catch (IllegalArgumentException ex) {
                getLogger().warning("Recurso no encontrado en JAR: " + path);
            }
        }
    }

    // ── Ciclo asincrono limpio ──────────────────────────────────────────

    private synchronized void startCycleTask() {
        cancelCycleTask();
        // Chequeo cada segundo: evita que el reloj quede en 0:00 si el servidor estuvo apagado
        cycleTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            long elapsed = (System.currentTimeMillis() - stats.cycleStartTime()) / 1000L;
            if (elapsed >= deliveryConfig.rotationSeconds()) {
                deliveryConfig.regenerateCycle();
                stats.newCycle();
                broadcastRotation();
                getLogger().info("[Cycle] Rotacion automatica ejecutada. Nuevo ciclo: " + deliveryConfig.rotationSeconds() + "s.");
            }
        }, 20L, 20L);
    }

    private synchronized void cancelCycleTask() {
        if (cycleTask != null) {
            try { cycleTask.cancel(); } catch (Exception ignored) {}
            cycleTask = null;
        }
    }

    public long remainingSeconds() {
        long elapsed = (System.currentTimeMillis() - stats.cycleStartTime()) / 1000L;
        return Math.max(0L, deliveryConfig.rotationSeconds() - elapsed);
    }

    public void broadcastRotation() {
        String raw = messages.actionBar("rotation", "<gray>Las entregas cambiaron.</gray>");
        Component ab = MiniMessage.miniMessage().deserialize(raw);
        for (Player p : Bukkit.getOnlinePlayers()) p.sendActionBar(ab);

        String prefix = getConfig().getString("prefix", "<dark_gray><bold>SURGIR</bold></dark_gray> <gray>|</gray> ");
        String chatRaw = getConfig().getString("messages.rotation-broadcast", "<prefix>Las /entregas cambiaron, vende tus items.");
        chatRaw = chatRaw.replace("<prefix>", prefix);
        Component chat = MiniMessage.miniMessage().deserialize(chatRaw);
        for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(chat);
        Bukkit.getConsoleSender().sendMessage(chat);
    }

    public void forceRotation() {
        deliveryConfig.clearCache();
        deliveryConfig.regenerateCycle();
        stats.newCycle();
        broadcastRotation();
        restartCycleTask();
        getLogger().info("ForceRotation: ciclo re-escaneado desde cofre, activeCycle=" + deliveryConfig.currentCycle().size());
    }

    public void openPlayerMenu(Player player) {
        new EntregasMenu(this, player).open();
    }

    public synchronized void restartCycleTask() {
        cancelCycleTask();
        startCycleTask();
    }

    public DeliveryConfig deliveryConfig() { return deliveryConfig; }
    public DeliveryService deliveryService() { return deliveryService; }
    public DeliveryStats stats() { return stats; }
    public EconomyHook economy() { return economy; }
    public MenuLayout menuLayout() { return menuLayout; }
    public MessagesConfig messages() { return messages; }
    public MaintenanceManager maintenanceManager() { return maintenanceManager; }

    public String colorize(String message) {
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', message);
    }

    @NotNull
    public String detectModelEngine() {
        if (Bukkit.getPluginManager().isPluginEnabled("Oraxen")) return "Oraxen";
        if (Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) return "ItemsAdder";
        return "Vanilla Nativo";
    }
}
