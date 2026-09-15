package com.surgirentregas.core;

import com.surgirentregas.delivery.PlayerProgress;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Estado global del plugin: ranking acumulado y progreso individual por
 * jugador/entrega. Persiste el ranking en {@code ranking.json} en disco.
 *
 * <p>El ranking utiliza puntos fraccionarios ({@code Double}) para que
 * cada entrega individual de ítems aporte valor proporcional de forma
 * inmediata, sin esperar a completar el paquete completo de tres.</p>
 */
public final class DeliveryStats {

    private final SurgirEntregasPlugin plugin;
    private final Map<String, Double> globalRanking = new ConcurrentHashMap<>();
    private final Map<String, PlayerProgress> progress = new ConcurrentHashMap<>();
    private final Set<UUID> rotationCompleted = ConcurrentHashMap.newKeySet();
    private long cycleStartTime = System.currentTimeMillis();

    public DeliveryStats(@NotNull SurgirEntregasPlugin plugin) {
        this.plugin = plugin;
        loadRanking();
        loadCycleState();
    }

    public long cycleStartTime() {
        return cycleStartTime;
    }

    public void newCycle() {
        progress.clear();
        rotationCompleted.clear();
        cycleStartTime = System.currentTimeMillis();
        saveCycleStateAsync();
    }

    public int countCompletedInCycle(@NotNull UUID playerId) {
        int c = 0;
        for (String key : progress.keySet()) {
            if (key.startsWith(playerId.toString() + ":")) {
                PlayerProgress pp = progress.get(key);
                if (pp != null && pp.isCompleted()) c++;
            }
        }
        if (rotationCompleted.contains(playerId)) return Math.max(c, 3);
        return c;
    }

    public boolean hasCompletedAllThree(@NotNull UUID playerId, int totalDeliveries) {
        if (totalDeliveries <= 0) return false;
        if (rotationCompleted.contains(playerId)) return true;
        int completed = 0;
        for (Map.Entry<String, PlayerProgress> e : progress.entrySet()) {
            if (e.getKey().startsWith(playerId.toString() + ":" ) && e.getValue().isCompleted()) completed++;
        }
        return completed >= totalDeliveries;
    }

    public boolean hasRotationCompleted(@NotNull UUID playerId) {
        return rotationCompleted.contains(playerId);
    }

    public void markRotationCompleted(@NotNull UUID playerId) {
        if (rotationCompleted.add(playerId)) {
            globalRanking.merge(playerId.toString(), 3.0, Double::sum);
            saveRankingAsync();
        }
    }

    // ── Progreso ─────────────────────────────────────────────────────────────

    @NotNull
    public PlayerProgress getProgress(@NotNull UUID playerId, @NotNull String deliveryKey) {
        String key = playerId + ":" + deliveryKey;
        return progress.computeIfAbsent(key, k -> new PlayerProgress(playerId, deliveryKey));
    }

    public boolean hasCompleted(@NotNull UUID playerId, @NotNull String deliveryKey) {
        return getProgress(playerId, deliveryKey).isCompleted();
    }

    /**
     * Marca una entrega como completada para el jugador.
     */
    public void markCompleted(@NotNull UUID playerId, @NotNull String deliveryKey) {
        PlayerProgress p = getProgress(playerId, deliveryKey);
        if (p.isCompleted()) return;
        p.markCompleted();
        saveRankingAsync();
    }

    // ── Puntos por Entrega Individual ──────────────────────────────────────

    /**
     * Suma puntos fraccionarios inmediatamente al ranking global.
     *
     * <p>Cada vez que un jugador entrega ítems (aunque sea una fracción),
     * se le acreditan puntos proporcionales: {@code pointsToAdd} se suma
     * al total acumulado del jugador. Esto permite que cada entrega parcial
     * aporte valor al ranking y al Top 7.</p>
     */
    public void addPoints(@NotNull UUID playerId, double pointsToAdd) {
        if (pointsToAdd <= 0) return;
        globalRanking.merge(playerId.toString(), pointsToAdd, Double::sum);
        saveRankingAsync();
    }

    // ── Ranking ──────────────────────────────────────────────────────────────

    public double getDeliveries(@NotNull UUID playerId) {
        return globalRanking.getOrDefault(playerId.toString(), 0.0);
    }

    @NotNull
    public Map<String, Double> getGlobalRanking() {
        return globalRanking;
    }

    @NotNull
    public List<RankingEntry> top(int limit) {
        List<RankingEntry> out = new ArrayList<>(globalRanking.size());
        for (Map.Entry<String, Double> e : globalRanking.entrySet()) {
            String name = resolveName(e.getKey());
            out.add(new RankingEntry(e.getKey(), name, e.getValue()));
        }
        out.sort((a, b) -> Double.compare(b.deliveries(), a.deliveries()));
        if (limit > 0 && out.size() > limit) {
            return new ArrayList<>(out.subList(0, limit));
        }
        return out;
    }

    public int rankOf(@NotNull UUID playerId) {
        List<RankingEntry> all = top(0);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).uuid().equals(playerId.toString())) {
                return i + 1;
            }
        }
        return -1;
    }

    @Nullable
    private String resolveName(String uuidStr) {
        try {
            UUID uuid = UUID.fromString(uuidStr);
            String n = Bukkit.getOfflinePlayer(uuid).getName();
            return n != null ? n : uuidStr.substring(0, 8);
        } catch (Exception e) {
            return uuidStr;
        }
    }

    // ── Persistencia ─────────────────────────────────────────────────────────

    private void loadRanking() {
        File file = new File(plugin.getDataFolder(), "ranking.json");
        if (!file.exists()) return;
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            for (String raw : lines) {
                String trimmed = raw.trim();
                if (trimmed.isEmpty()) continue;
                int colon = trimmed.indexOf(':');
                if (colon < 0) continue;
                String uuid = trimmed.substring(0, colon).trim();
                try {
                    double n = Double.parseDouble(trimmed.substring(colon + 1).trim());
                    globalRanking.put(uuid, n);
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("No se pudo leer ranking.json: " + e.getMessage());
        }
    }

    public void saveRankingAsync() {
        final List<String> lines = new ArrayList<>(globalRanking.size());
        for (Map.Entry<String, Double> e : globalRanking.entrySet()) {
            lines.add(e.getKey() + ":" + e.getValue());
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                File file = new File(plugin.getDataFolder(), "ranking.json");
                Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
            } catch (Exception e) {
                plugin.getLogger().warning("No se pudo guardar ranking.json: " + e.getMessage());
            }
        });
    }

    public void saveRankingSync() {
        try {
            List<String> lines = new ArrayList<>(globalRanking.size());
            for (Map.Entry<String, Double> e : globalRanking.entrySet()) {
                lines.add(e.getKey() + ":" + e.getValue());
            }
            File file = new File(plugin.getDataFolder(), "ranking.json");
            Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
        } catch (Exception e) {
            plugin.getLogger().warning("No se pudo guardar ranking.json: " + e.getMessage());
        }
    }

    private void loadCycleState() {
        File f = new File(plugin.getDataFolder(), "cycle.dat");
        if (!f.exists()) return;
        try {
            String s = Files.readString(f.toPath(), StandardCharsets.UTF_8).trim();
            if (s.isEmpty()) throw new NumberFormatException("empty");
            long parsed = Long.parseLong(s);
            long now = System.currentTimeMillis();
            // Validacion estricta: 0, negativo, futuro (+5s tolerancia) o no numerico -> corrupto
            if (parsed <= 0 || parsed > now + 5000L) throw new NumberFormatException("out of range: " + parsed);
            cycleStartTime = parsed;
        } catch (Exception e) {
            plugin.getLogger().warning("[Cycle] cycle.dat corrupto o invalido (" + e.getMessage() + ") -> se regenerara ciclo nuevo.");
            cycleStartTime = System.currentTimeMillis();
        }
    }

    /**
     * Indica si el ciclo actual ya expiro segun rotationSeconds configurado.
     */
    public boolean isExpired(long rotationSeconds) {
        long elapsed = (System.currentTimeMillis() - cycleStartTime) / 1000L;
        return elapsed >= rotationSeconds;
    }

    private void saveCycleStateAsync() {
        long ts = cycleStartTime;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                File f = new File(plugin.getDataFolder(), "cycle.dat");
                Files.writeString(f.toPath(), String.valueOf(ts), StandardCharsets.UTF_8);
            } catch (Exception e) {
                plugin.getLogger().warning("No se pudo guardar cycle.dat: " + e.getMessage());
            }
        });
    }

    public void saveCycleStateSync() {
        try {
            File f = new File(plugin.getDataFolder(), "cycle.dat");
            Files.writeString(f.toPath(), String.valueOf(cycleStartTime), StandardCharsets.UTF_8);
        } catch (Exception e) {
            plugin.getLogger().warning("No se pudo guardar cycle.dat: " + e.getMessage());
        }
    }

    public record RankingEntry(String uuid, String name, double deliveries) {}
}
