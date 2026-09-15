package com.surgirentregas.commands;

import com.surgirentregas.core.SurgirEntregasPlugin;
import com.surgirentregas.menu.AdminMenuHolder;
import com.surgirentregas.menu.EntregasAdminMenu;
import com.surgirentregas.menu.EntregasMenu;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Comando principal /entregas — abre el menu del jugador.
 * El menu admin se abre con /entregasadmin (definido en plugin.yml).
 */
public final class EntregasCommand implements CommandExecutor {

    private final SurgirEntregasPlugin plugin;

    public EntregasCommand(@NotNull SurgirEntregasPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                              @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Este comando solo puede usarse en el juego.");
            return true;
        }
        // Mantenimiento: restriccion logica interna. Los jugadores normales no
        // pueden abrir el menu ni ejecutar subcomandos; los OP o con bypass si.
        if (!plugin.maintenanceManager().canUse(player)) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(
                    plugin.getConfig().getString("maintenance.messages.blocked-access",
                            "<red>[!] El sistema de entregas se encuentra en mantenimiento. Intenta más tarde.</red>")));
            return true;
        }
        if (args.length > 0) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("admin")) {
                if (!player.hasPermission("entregas.admin")) {
                    player.sendMessage(MiniMessage.miniMessage().deserialize("<red>No tienes permiso.</red>"));
                    return true;
                }
                new EntregasAdminMenu(plugin, new AdminMenuHolder(plugin, player)).open();
                return true;
            }
            if (sub.equals("reload")) {
                if (!player.hasPermission("entregas.admin")) {
                    player.sendMessage(MiniMessage.miniMessage().deserialize("<red>No tienes permiso.</red>"));
                    return true;
                }
                long start = System.nanoTime();
                plugin.reloadConfig();
                plugin.deliveryConfig().load();
                plugin.menuLayout().load();
                plugin.messages().load();
                plugin.maintenanceManager().loadState();
                plugin.deliveryConfig().regenerateCycle();
                plugin.stats().newCycle();
                long ms = (System.nanoTime() - start) / 1_000_000L;

                String engine = plugin.detectModelEngine();
                String maint = plugin.maintenanceManager().getStatus();
                int deliveries = (int) plugin.deliveryConfig().currentCycle().stream()
                        .filter(d -> !d.locked()).count();
                int poolSize = plugin.deliveryConfig().poolSize();
                boolean fallback = plugin.deliveryConfig().isFallbackActive();

                String prefix = plugin.getConfig().getString("prefix", "");

                // Reporte limpio a CONSOLA.
                plugin.getLogger().info("[Reload] Recarga completada en " + ms + " ms.");
                plugin.getLogger().info("[Reload] Motor de modelos: " + engine);
                plugin.getLogger().info("[Reload] Mantenimiento: " + maint);
                if (fallback) {
                    plugin.getLogger().info("[Reload] Entregas: Fallback Vanilla Activo (" + deliveries + " generadas).");
                } else {
                    plugin.getLogger().info("[Reload] Entregas cargadas: " + deliveries + " (pool: " + poolSize + " items).");
                }

                // Reporte a CHAT ADMIN.
                player.sendMessage(MiniMessage.miniMessage().deserialize(
                        prefix + "<green>Recarga completada en <white>" + ms + " ms</white>.</green>"));
                player.sendMessage(MiniMessage.miniMessage().deserialize(
                        prefix + "<gray>Motor de modelos: <white>" + engine + "</white></gray>"));
                player.sendMessage(MiniMessage.miniMessage().deserialize(
                        prefix + "<gray>Mantenimiento: <" + (maint.equals("ACTIVO") ? "red" : "green") + ">"
                                + maint + "</" + (maint.equals("ACTIVO") ? "red" : "green") + "></gray>"));
                if (fallback) {
                    player.sendMessage(MiniMessage.miniMessage().deserialize(
                            prefix + "<yellow>Fallback Vanilla Activo</yellow> <gray>(" + deliveries + " entregas)</gray>"));
                } else {
                    player.sendMessage(MiniMessage.miniMessage().deserialize(
                            prefix + "<gray>Entregas cargadas: <white>" + deliveries + "</white> (pool: " + poolSize + ")</gray>"));
                }
                return true;
            }
            if (sub.equals("forcerotation") || sub.equals("rotar") || sub.equals("reset")) {
                if (!player.hasPermission("entregas.admin")) {
                    player.sendMessage(MiniMessage.miniMessage().deserialize("<red>No tienes permiso.</red>"));
                    return true;
                }
                plugin.forceRotation();
                player.sendMessage(MiniMessage.miniMessage().deserialize(
                        plugin.getConfig().getString("prefix", "") + "<green>Rotacion global forzada.</green>"));
                return true;
            }
            if (sub.equals("help") || sub.equals("ayuda")) {
                player.sendMessage(MiniMessage.miniMessage().deserialize(""
                        + "<dark_gray><bold>SURGIR</bold></dark_gray> <gray>|</gray> <white>Sistema de Entregas</white><br>"
                        + "<dark_gray>»</dark_gray> <white>/entregas</white> <gray>- Abrir menu de entregas</gray><br>"
                        + "<dark_gray>»</dark_gray> <white>/entregasadmin</white> <gray>- Abrir menu admin</gray><br>"
                        + "<dark_gray>»</dark_gray> <white>/entregas forcerotation</white> <gray>- Forzar reinicio global</gray><br>"
                        + "<dark_gray>»</dark_gray> <white>/entregas reload</white> <gray>- Recargar config</gray>"));
                return true;
            }
        }
        new EntregasMenu(plugin, player).open();
        return true;
    }
}
