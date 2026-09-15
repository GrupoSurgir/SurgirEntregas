package com.surgirentregas.economy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Enganche multinivel: Vault / PlayerPoints / Custom Commands.
 * Configurable desde config.yml -> economy.type
 */
public final class EconomyHook {

    private final JavaPlugin plugin;
    private Economy vaultEconomy;
    private Object playerPointsAPI;
    private java.lang.reflect.Method ppGiveMethod;
    private java.lang.reflect.Method ppLookMethod;

    public EconomyHook(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
        hookVault();
        hookPlayerPoints();
    }

    private void hookVault() {
        try {
            RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager()
                    .getRegistration(Economy.class);
            if (rsp != null) {
                vaultEconomy = rsp.getProvider();
                plugin.getLogger().info("Vault economy detectado: " + vaultEconomy.getName());
            }
        } catch (NoClassDefFoundError e) {
            plugin.getLogger().info("Vault API no presente — se omitira Vault.");
        }
    }

    private void hookPlayerPoints() {
        try {
            if (Bukkit.getPluginManager().getPlugin("PlayerPoints") != null) {
                Class<?> apiClass = Class.forName("org.black_ixx.playerpoints.PlayerPoints");
                Object ppPlugin = apiClass.getMethod("getInstance").invoke(null);
                playerPointsAPI = ppPlugin.getClass().getMethod("getAPI").invoke(ppPlugin);
                ppGiveMethod = playerPointsAPI.getClass().getMethod("give", java.util.UUID.class, int.class);
                try {
                    ppLookMethod = playerPointsAPI.getClass().getMethod("look", java.util.UUID.class);
                } catch (NoSuchMethodException ignored) {
                }
                plugin.getLogger().info("PlayerPoints detectado — enganche activo.");
            }
        } catch (Exception e) {
            plugin.getLogger().info("PlayerPoints no detectado: " + e.getMessage());
        }
    }

    /**
     * Auto-deteccion de motor economico y log en consola.
     * Prioridad: Vault > PlayerPoints > Fallback Comando.
     */
    public void detectAndLog() {
        if (isVaultAvailable()) {
            plugin.getLogger().info("[Economia] Enganchado con exito a Vault (Proveedor de Dinero).");
        } else if (isPlayerPointsAvailable()) {
            plugin.getLogger().info("[Economia] Vault no encontrado/sin proveedor. Enganchado a PlayerPoints (Puntos).");
        } else {
            plugin.getLogger().warning("[Economia] No se detecto Vault ni PlayerPoints. Se utilizara ejecucion por comando de consola.");
        }
    }

    public boolean isVaultAvailable() {
        return vaultEconomy != null;
    }

    public boolean isPlayerPointsAvailable() {
        return playerPointsAPI != null && ppGiveMethod != null;
    }

    public boolean isAvailable() {
        String type = plugin.getConfig().getString("economy.type", "VAULT").toUpperCase();
        return switch (type) {
            case "VAULT" -> isVaultAvailable();
            case "PLAYERPOINTS" -> isPlayerPointsAvailable();
            case "COMMAND", "NONE" -> true;
            case "VAULT_COMMAND" -> isVaultAvailable() || true;
            default -> isVaultAvailable();
        };
    }

    public boolean deposit(@NotNull Player player, double amount) {
        if (amount <= 0) return false;
        String type = plugin.getConfig().getString("economy.type", "VAULT").toUpperCase();
        return switch (type) {
            case "VAULT" -> depositVault(player, amount);
            case "PLAYERPOINTS" -> depositPoints(player, (int) amount);
            case "COMMAND" -> executeCommands(player, amount, false);
            case "VAULT_COMMAND" -> depositVault(player, amount) || executeCommands(player, amount, false);
            default -> depositVault(player, amount);
        };
    }

    public boolean depositFinal(@NotNull Player player, double amount) {
        if (amount <= 0) return false;
        String type = plugin.getConfig().getString("economy.type", "VAULT").toUpperCase();
        // Para recompensa final usamos final-commands si type es COMMAND
        if (type.equals("COMMAND") || type.equals("VAULT_COMMAND")) {
            List<String> cmds = plugin.getConfig().getStringList("economy.final-commands");
            if (!cmds.isEmpty()) {
                return executeCommandsList(player, amount, cmds);
            }
        }
        return deposit(player, amount);
    }

    private boolean depositVault(@NotNull Player player, double amount) {
        if (vaultEconomy == null) return false;
        try {
            return vaultEconomy.depositPlayer(player, amount).transactionSuccess();
        } catch (Exception e) {
            plugin.getLogger().warning("Error Vault depositando a " + player.getName() + ": " + e.getMessage());
            return false;
        }
    }

    private boolean depositPoints(@NotNull Player player, int amount) {
        if (playerPointsAPI == null || ppGiveMethod == null) return false;
        try {
            return (boolean) ppGiveMethod.invoke(playerPointsAPI, player.getUniqueId(), amount);
        } catch (Exception e) {
            plugin.getLogger().warning("Error PlayerPoints give: " + e.getMessage());
            return false;
        }
    }

    private boolean executeCommands(@NotNull Player player, double amount, boolean isFinal) {
        List<String> cmds = isFinal
                ? plugin.getConfig().getStringList("economy.final-commands")
                : plugin.getConfig().getStringList("economy.commands");
        if (cmds.isEmpty()) {
            cmds = List.of("eco give %player% %reward%");
        }
        return executeCommandsList(player, amount, cmds);
    }

    private boolean executeCommandsList(@NotNull Player player, double amount, List<String> cmds) {
        boolean any = false;
        for (String raw : cmds) {
            String cmd = raw.replace("%player%", player.getName())
                    .replace("%reward%", String.valueOf((long) amount))
                    .replace("%amount%", String.valueOf((long) amount));
            // Evita prefijo slash doble
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            try {
                boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                any = any || ok;
            } catch (Exception e) {
                plugin.getLogger().warning("Error ejecutando comando recompensa: " + cmd + " -> " + e.getMessage());
            }
        }
        return any;
    }

    @NotNull
    public String format(double amount) {
        if (vaultEconomy != null) {
            try {
                return vaultEconomy.format(amount);
            } catch (Exception ignored) {
            }
        }
        return String.format("%.0f", amount);
    }
}
