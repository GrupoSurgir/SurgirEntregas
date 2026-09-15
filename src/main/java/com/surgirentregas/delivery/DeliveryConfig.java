package com.surgirentregas.delivery;

import com.surgirentregas.core.SurgirEntregasPlugin;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Configuracion y estado de entregas.
 *
 * <p>Lee su configuracion desde {@code config.yml} (precios, temporizadores,
 * modos por defecto) y desde {@code items_pool.yml} (pool de items del cofre
 * de rotacion, serializados en Base64 para preservar NBT / modelos custom).</p>
 *
 * <p>Los precios admiten dos modos:
 * <ul>
 *   <li>{@code GLOBAL}: siempre usa {@code price.global}.</li>
 *   <li>{@code UNICO}: usa {@code price.per-item.<MATERIAL>} cuando exista,
 *       si no, usa el global.</li>
 * </ul>
 *
 * <p>El orden de los items rotativos puede ser:
 * <ul>
 *   <li>{@code POR_MATERIALES}: orden alfabetico por nombre del material.</li>
 *   <li>{@code ORDEN_DEL_COFRE}: orden en que fueron colocados en el cofre.</li>
 * </ul>
 */
public final class DeliveryConfig {

    private final Plugin plugin;

    private volatile long rotationSeconds = 1200L;
    private volatile int announcementCooldownMinutes = 5;
    private volatile int manualStepSeconds = 60;
    private volatile double globalReward = 2000.0;
    private volatile double finalReward = 2000.0;
    private volatile OrderMode orderMode = OrderMode.POR_MATERIALES;
    private volatile PriceMode priceMode = PriceMode.GLOBAL;

    private final java.util.Map<String, Double> perItemReward = new java.util.HashMap<>();
    private final CopyOnWriteArrayList<ItemStack> pool = new CopyOnWriteArrayList<>();

    /** Fallback pool loaded from config.yml fallback-pool.items when the main pool is empty. */
    private final List<FallbackItem> fallbackPool = new java.util.ArrayList<>();

    /** Entregas activas del ciclo actual (globales para todos los jugadores). */
    private volatile List<DeliveryDefinition> activeCycle = Collections.emptyList();

    public DeliveryConfig(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            plugin.saveResource("config.yml", false);
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(configFile);

        rotationSeconds = parseDuration(cfg.getString("timer.rotation", "20m"), 1200L);
        announcementCooldownMinutes = cfg.getInt("timer.announcement-cooldown", 5);
        manualStepSeconds = cfg.getInt("timer.manual-step", 60);
        globalReward = cfg.getDouble("price.global", 2000.0);
        finalReward = cfg.getDouble("price.final", globalReward);

        try {
            priceMode = PriceMode.valueOf(cfg.getString("default-price-mode", "GLOBAL").toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            priceMode = PriceMode.GLOBAL;
        }
        try {
            orderMode = OrderMode.valueOf(
                    cfg.getString("default-order.mode", "POR_MATERIALES").toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            orderMode = OrderMode.POR_MATERIALES;
        }

        perItemReward.clear();
        ConfigurationSection perItem = cfg.getConfigurationSection("price.per-item");
        if (perItem != null) {
            for (String key : perItem.getKeys(false)) {
                perItemReward.put(key.toUpperCase(Locale.ROOT), perItem.getDouble(key, globalReward));
            }
        }

        loadFallbackPool();
        loadPool();
    }

    private void loadPool() {
        pool.clear();
        File file = new File(plugin.getDataFolder(), "items_pool.yml");
        if (!file.exists()) {
            plugin.getLogger().info("[Pool] items_pool.yml no existe. Generando pool inicial...");
            savePoolAsync();
            return;
        }
        int loaded = 0;
        int failed = 0;
        try {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            List<String> list = cfg.getStringList("items");
            plugin.getLogger().info("[Pool] Leyendo " + list.size() + " items de items_pool.yml...");
            for (String b64 : list) {
                if (b64 == null || b64.isBlank()) continue;
                try {
                    byte[] data = Base64.getDecoder().decode(b64.trim());
                    ItemStack item = ItemStack.deserializeBytes(data);
                    if (item != null && !item.getType().isAir()) {
                        pool.add(item);
                        loaded++;
                    }
                } catch (Exception e) {
                    failed++;
                    plugin.getLogger().warning("[Pool] Error al deserializar item: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("No se pudo leer items_pool.yml: " + e.getMessage());
        }
        plugin.getLogger().info("[Pool] Carga completada. " + loaded + " items cargados, " + failed + " errores.");
        if (pool.isEmpty()) {
            plugin.getLogger().info("[Pool] Pool vacio. Activando Fallback Vanilla autonomo.");
        }
    }

    private void loadFallbackPool() {
        fallbackPool.clear();
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(
                new File(plugin.getDataFolder(), "config.yml"));
        List<?> rawList = cfg.getList("fallback-pool.items");
        if (rawList == null || rawList.isEmpty()) {
            plugin.getLogger().info("fallback-pool.items not found in config.yml, using empty fallback");
            return;
        }
        for (Object entry : rawList) {
            if (!(entry instanceof java.util.Map<?, ?> map)) continue;
            String materialName = String.valueOf(map.get("material"));
            if (materialName.isBlank() || "null".equals(materialName)) continue;
            Material mat;
            try {
                mat = Material.valueOf(materialName.toUpperCase(Locale.ROOT));
            } catch (Exception e) {
                plugin.getLogger().warning("Invalid material in fallback-pool: " + materialName);
                continue;
            }
            if (mat == Material.AIR) {
                plugin.getLogger().warning("Invalid material in fallback-pool: " + materialName);
                continue;
            }
            int min = asInt(map.get("min-amount"), 1);
            int max = asInt(map.get("max-amount"), 64);
            double reward = asDouble(map.get("reward"), 2000.0);
            if (max < min) {
                int tmp = min; min = max; max = tmp;
            }
            fallbackPool.add(new FallbackItem(mat, min, max, reward));
        }
    }

    private static int asInt(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (Exception ignored) {}
        }
        return def;
    }

    private static double asDouble(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) {
            try { return Double.parseDouble(s.trim()); } catch (Exception ignored) {}
        }
        return def;
    }

    /**
     * Guarda el pool a disco de forma ASINCRONA.
     */
    public void savePoolAsync() {
        final List<String> serialized = new ArrayList<>(pool.size());
        for (ItemStack item : pool) {
            if (item != null && !item.getType().isAir()) {
                serialized.add(Base64.getEncoder().encodeToString(item.clone().serializeAsBytes()));
            }
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                File file = new File(plugin.getDataFolder(), "items_pool.yml");
                YamlConfiguration cfg = new YamlConfiguration();
                cfg.set("items", serialized);
                Files.writeString(file.toPath(), cfg.saveToString(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                plugin.getLogger().warning("No se pudo guardar items_pool.yml: " + e.getMessage());
            }
        });
    }

    /**
     * Guarda el pool de forma SINCRONA (util en onDisable).
     */
    public void savePoolSync() {
        try {
            List<String> serialized = new ArrayList<>(pool.size());
            for (ItemStack item : pool) {
                if (item != null && !item.getType().isAir()) {
                    serialized.add(Base64.getEncoder().encodeToString(item.clone().serializeAsBytes()));
                }
            }
            File file = new File(plugin.getDataFolder(), "items_pool.yml");
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.set("items", serialized);
            Files.writeString(file.toPath(), cfg.saveToString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo guardar items_pool.yml: " + e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // POOL — cofre de rotacion
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Sustituye el pool completo por la lista dada. NO clona: el caller puede
     * pasar copias defensivas. Persiste en disco de forma asincrona.
     */
    public void setPool(@NotNull List<ItemStack> items) {
        pool.clear();
        for (ItemStack item : items) {
            if (item != null && !item.getType().isAir()) {
                pool.add(item.clone());
            }
        }
        savePoolAsync();
        regenerateCycle();
    }

    /**
     * Inserta o reemplaza el item en el indice dado (sin huecos).
     * Si el indice esta fuera del rango, se agrega al final.
     */
    public void setPoolItem(int index, @NotNull ItemStack item) {
        if (item.getType().isAir()) {
            removePoolItem(index);
            return;
        }
        if (index < 0) index = 0;
        if (index >= pool.size()) {
            pool.add(item.clone());
        } else {
            pool.set(index, item.clone());
        }
        savePoolAsync();
        regenerateCycle();
    }

    public void removePoolItem(int index) {
        if (index < 0 || index >= pool.size()) return;
        pool.remove(index);
        savePoolAsync();
        regenerateCycle();
    }

    @NotNull
    public List<ItemStack> poolItems() {
        return Collections.unmodifiableList(pool);
    }

    public int poolSize() {
        return pool.size();
    }

    /**
     * Indica si el fallback vanilla autonomo esta activo (el pool principal del
     * cofre esta vacio y se estan usando los items nativos de config.yml).
     */
    public boolean isFallbackActive() {
        return pool.isEmpty() && !fallbackPool.isEmpty();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ROTACION — seleccion de entregas segun el modo y el orden configurados
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Entregas activas del ciclo ACTUAL (3 slots). Son las mismas para todos
     * los jugadores: la rotacion es GLOBAL. Se regeneran en cada ciclo.
     */
    @NotNull
    public List<DeliveryDefinition> currentCycle() {
        return activeCycle;
    }

    /**
      * Purga inmediata de cache del ciclo activo en memoria.
      */
    public void clearCache() {
        activeCycle = Collections.emptyList();
    }

    /**
      * Regenera las entregas activas del ciclo (3 items desde el pool respetando
      * el modo de orden configurado). Se invoca al habilitar el plugin, en cada
      * rotacion automatica y cuando el pool/modo cambian desde el menu admin.
      */
    public void regenerateCycle() {
        if (pool.size() == 1) {
            activeCycle = buildDeliveries(1);
        } else if (pool.size() == 2) {
            activeCycle = buildDeliveries(2);
        } else {
            activeCycle = buildDeliveries(3);
        }
    }

    /**
     * Construye hasta {@code count} entregas a partir del pool respetando el
     * modo de orden configurado y aplicando diversificacion:
     *
     * <ul>
     *   <li>Los items del pool se agrupan en categorias (minerales, cultivos,
     *       comida, bloques, otros).</li>
     *   <li>El ciclo alterna entre categorias para que el jugador reciba
     *       entregas variadas (no todo el ciclo con minerales).</li>
     *   <li>NO se repiten materiales dentro del mismo ciclo: cada {@link Material}
     *       aparece a lo sumo UNA vez.</li>
     *   <li>Si el pool se agota antes de completar el ciclo, los slots restantes
     *       se marcan como {@link DeliveryDefinition#LOCKED} (item BARRIER)
     *       para que el menu muestre el estado bloqueado en lugar de repetir
     *       el ultimo material.</li>
     * </ul>
     */
    @NotNull
    public List<DeliveryDefinition> buildDeliveries(int count) {
        if (count <= 0) return Collections.emptyList();

        // Si el pool principal está vacío, usar fallback-pool Vanilla autónomo.
        if (pool.isEmpty() && !fallbackPool.isEmpty()) {
            return buildFromFallbackPool(count);
        }

        List<ItemStack> source = orderedPool();
        List<DeliveryDefinition> out = new ArrayList<>(count);
        java.util.Set<Material> used = new java.util.HashSet<>();
        java.util.Map<ItemCategory, java.util.Deque<ItemStack>> byCategory =
                new java.util.EnumMap<>(ItemCategory.class);
        for (ItemStack item : source) {
            ItemCategory cat = ItemCategory.of(item.getType());
            byCategory.computeIfAbsent(cat, k -> new java.util.ArrayDeque<>()).addLast(item);
        }

        // Orden de rotacion: alternamos categorias para maximizar variedad.
        ItemCategory[] rotation = {
                ItemCategory.MINERAL, ItemCategory.CROP, ItemCategory.FOOD,
                ItemCategory.BLOCK, ItemCategory.OTHER, ItemCategory.MINERAL
        };
        int rotIdx = 0;

        for (int i = 0; i < count; i++) {
            ItemStack pick = null;
            // Buscar el siguiente item sin repetir material, alternando categorias.
            for (int attempt = 0; attempt < rotation.length; attempt++) {
                ItemCategory cat = rotation[(rotIdx + attempt) % rotation.length];
                java.util.Deque<ItemStack> q = byCategory.get(cat);
                if (q == null) continue;
                while (!q.isEmpty()) {
                    ItemStack candidate = q.peekFirst();
                    // Ignora duplicados ya usados en este ciclo.
                    if (used.contains(candidate.getType())) {
                        q.pollFirst();
                        continue;
                    }
                    q.pollFirst();
                    pick = candidate;
                    break;
                }
                if (pick != null) {
                    rotIdx = (rotIdx + attempt + 1) % rotation.length;
                    break;
                }
            }
            if (pick == null) {
                // Pool agotado para este ciclo: slot BLOQUEADO.
                out.add(DeliveryDefinition.locked("delivery-" + (out.size() + 1), out.size()));
                continue;
            }
            used.add(pick.getType());
            // Log de auditoria para items custom (CustomModelData/NBT)
            if (pick.hasItemMeta() && pick.getItemMeta().hasCustomModelData()) {
                int cmd = pick.getItemMeta().getCustomModelData();
                plugin.getLogger().info("[Cofre] Entrega detectada con CustomModelData: " + pick.getType() + " model=" + cmd);
            }
            int poolAmount = Math.max(1, pick.getAmount());
            int amount = poolAmount > 1 ? poolAmount : defaultAmount(pick.getType());
            double reward = resolveReward(pick.getType());
            out.add(new DeliveryDefinition(
                    "delivery-" + (out.size() + 1),
                    pick.getType(), amount, reward, out.size(), pick));
        }
        return out;
    }

    /**
     * Construye entregas utilizando el fallback-pool de config.yml cuando el
     * cofre admin principal está vacío. Selecciona N ítems sin repetir de la
     * lista fallback-pool.items y genera cantidades aleatorias entre min-amount
     * y max-amount para cada uno.
     */
    @NotNull
    private List<DeliveryDefinition> buildFromFallbackPool(int count) {
        List<DeliveryDefinition> out = new ArrayList<>(count);
        java.util.Set<Material> usedMaterials = new java.util.HashSet<>();
        java.util.List<FallbackItem> available = new java.util.ArrayList<>(fallbackPool);

        // Shuffle available items using ThreadLocalRandom for randomness
        java.util.Collections.shuffle(available);

        // Tomar hasta 'count' items únicos sin repetir material
        int idx = 0;
        for (int i = 0; i < count && idx < available.size(); i++) {
            FallbackItem fallback = available.get(idx);
            idx++;

            // Evitar materiales repetidos dentro del mismo ciclo
            if (usedMaterials.contains(fallback.material())) {
                i--; // retry with next item
                continue;
            }
            usedMaterials.add(fallback.material());

            // Generar cantidad aleatoria entre min-amount y max-amount
            int amount = ThreadLocalRandom.current().nextInt(
                    fallback.minAmount(), fallback.maxAmount() + 1);

            out.add(new DeliveryDefinition(
                    "delivery-" + (out.size() + 1),
                    fallback.material(), amount, fallback.reward(), out.size(), null));
        }

        // Si no conseguimos suficientes items únicos, completar con slots bloqueados
        while (out.size() < count) {
            out.add(DeliveryDefinition.locked("delivery-" + (out.size() + 1), out.size()));
        }

        return out;
    }

    /**
     * Categorias de materiales para diversificar el ciclo.
     */
    private enum ItemCategory {
        MINERAL, CROP, FOOD, BLOCK, OTHER;

        @NotNull
        static ItemCategory of(@NotNull Material material) {
            String name = material.name();
            if (name.endsWith("_INGOT") || name.endsWith("_ORE")
                    || name.equals("DIAMOND") || name.equals("EMERALD")
                    || name.equals("COAL") || name.equals("CHARCOAL")
                    || name.equals("QUARTZ") || name.equals("LAPIS_LAZULI")
                    || name.equals("REDSTONE") || name.equals("AMETHYST_SHARD")
                    || name.equals("NETHERITE_INGOT") || name.equals("NETHERITE_SCRAP")
                    || name.equals("RAW_IRON") || name.equals("RAW_COPPER")
                    || name.equals("RAW_GOLD") || name.equals("COPPER_INGOT")
                    || name.equals("ANCIENT_DEBRIS") || name.equals("NETHER_STAR")) {
                return MINERAL;
            }
            if (name.equals("WHEAT") || name.equals("CARROT") || name.equals("POTATO")
                    || name.equals("BEETROOT") || name.equals("PUMPKIN") || name.equals("MELON")
                    || name.equals("SUGAR_CANE") || name.equals("CACTUS") || name.equals("KELP")
                    || name.equals("BAMBOO") || name.equals("NETHER_WART")
                    || name.equals("WHEAT_SEEDS") || name.equals("BEETROOT_SEEDS")
                    || name.equals("PUMPKIN_SEEDS") || name.equals("MELON_SEEDS")) {
                return CROP;
            }
            if (name.equals("APPLE") || name.equals("BREAD") || name.equals("COOKED_BEEF")
                    || name.equals("COOKED_PORKCHOP") || name.equals("COOKED_CHICKEN")
                    || name.equals("COOKED_MUTTON") || name.equals("COOKED_RABBIT")
                    || name.equals("COOKED_COD") || name.equals("COOKED_SALMON")
                    || name.equals("BAKED_POTATO") || name.equals("GOLDEN_APPLE")
                    || name.equals("GOLDEN_CARROT") || name.equals("HONEY_BOTTLE")
                    || name.equals("SWEET_BERRIES") || name.equals("GLOW_BERRIES")
                    || name.equals("ENCHANTED_GOLDEN_APPLE")) {
                return FOOD;
            }
            if (name.endsWith("_LOG") || name.endsWith("_PLANKS")
                    || name.endsWith("_STAIRS") || name.endsWith("_SLAB")
                    || name.endsWith("_BRICKS") || name.endsWith("_STONE")
                    || name.equals("STONE") || name.equals("COBBLESTONE")
                    || name.equals("DIRT") || name.equals("GRAVEL") || name.equals("SAND")
                    || name.equals("ANDESITE") || name.equals("DIORITE") || name.equals("GRANITE")
                    || name.equals("NETHERRACK") || name.equals("END_STONE")) {
                return BLOCK;
            }
            return OTHER;
        }
    }

    private List<ItemStack> orderedPool() {
        if (orderMode == OrderMode.ORDEN_DEL_COFRE) {
            return new ArrayList<>(pool);
        }
        if (orderMode == OrderMode.ALEATORIO) {
            List<ItemStack> shuffled = new ArrayList<>(pool);
            Collections.shuffle(shuffled);
            return shuffled;
        }
        // POR_MATERIALES — orden alfabetico estable.
        List<ItemStack> sorted = new ArrayList<>(pool);
        sorted.sort((a, b) -> a.getType().name().compareTo(b.getType().name()));
        return sorted;
    }

    private double resolveReward(@NotNull Material material) {
        if (priceMode == PriceMode.UNICO) {
            Double custom = perItemReward.get(material.name().toUpperCase(Locale.ROOT));
            if (custom != null) return custom;
        }
        return globalReward;
    }

    public double rewardFor(@NotNull Material material) {
        return resolveReward(material);
    }

    private static boolean isBulkCrop(@NotNull Material m) {
        String n = m.name();
        return n.equals("WHEAT") || n.equals("CARROT") || n.equals("POTATO") || n.equals("BEETROOT")
                || n.equals("SUGAR_CANE") || n.equals("KELP") || n.equals("BAMBOO")
                || n.equals("NETHER_WART") || n.equals("COCOA_BEANS");
    }

    /**
     * Cantidad predeterminada limpia por rareza.
     * - Valiosos: 10
     * - Cultivos a granel: 32
     * - Comunes/bloques: 64
     */
    private int defaultAmount(@NotNull Material material) {
        switch (material) {
            case DIAMOND:
            case DIAMOND_BLOCK:
            case NETHERITE_INGOT:
            case NETHERITE_BLOCK:
            case NETHER_STAR:
            case TOTEM_OF_UNDYING:
            case ELYTRA:
            case ENCHANTED_GOLDEN_APPLE:
            case SHULKER_SHELL:
            case DRAGON_BREATH:
            case DRAGON_HEAD:
            case SKELETON_SKULL:
            case WITHER_SKELETON_SKULL:
            case ZOMBIE_HEAD:
            case CREEPER_HEAD:
            case PLAYER_HEAD:
            case NETHERITE_SCRAP:
            case ANCIENT_DEBRIS:
                return 10;
            default:
                return isBulkCrop(material) ? 32 : 64;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SETTERS (mutadores para el menu admin)
    // ══════════════════════════════════════════════════════════════════════════

    public void setRotationSeconds(long seconds) {
        this.rotationSeconds = Math.max(60L, seconds);
        persistTimer();
    }

    public void setGlobalReward(double reward) {
        this.globalReward = Math.max(0.0, reward);
        persistPrice();
    }

    public void setOrderMode(@NotNull OrderMode mode) {
        this.orderMode = mode;
        persistDefaultOrder();
        regenerateCycle();
    }

    public void setPriceMode(@NotNull PriceMode mode) {
        this.priceMode = mode;
        persistDefaultPriceMode();
        regenerateCycle();
    }

    private void persistTimer() {
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        cfg.set("timer.rotation", rotationSeconds + "s");
        cfg.set("timer.manual-step", manualStepSeconds);
        saveConfig(cfg, file);
    }

    private void persistPrice() {
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        cfg.set("price.global", globalReward);
        saveConfig(cfg, file);
    }

    private void persistDefaultOrder() {
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        cfg.set("default-order.mode", orderMode.name());
        saveConfig(cfg, file);
    }

    private void persistDefaultPriceMode() {
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        cfg.set("default-price-mode", priceMode.name());
        saveConfig(cfg, file);
    }

    private void saveConfig(@NotNull YamlConfiguration cfg, @NotNull File file) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                cfg.save(file);
            } catch (IOException e) {
                plugin.getLogger().warning("No se pudo guardar config.yml: " + e.getMessage());
            }
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    // GETTERS
    // ══════════════════════════════════════════════════════════════════════════

    public long rotationSeconds() {
        return rotationSeconds;
    }

    public int announcementCooldownMinutes() {
        return announcementCooldownMinutes;
    }

    public int manualStepSeconds() {
        return manualStepSeconds;
    }

    public void setManualStepSeconds(int step) {
        this.manualStepSeconds = Math.max(1, step);
        persistTimer();
    }

    public double globalReward() {
        return globalReward;
    }

    public double finalReward() {
        return finalReward;
    }

    public void setFinalReward(double reward) {
        this.finalReward = Math.max(0.0, reward);
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        cfg.set("price.final", finalReward);
        saveConfig(cfg, file);
    }

    @NotNull
    public OrderMode orderMode() {
        return orderMode;
    }

    @NotNull
    public PriceMode priceMode() {
        return priceMode;
    }

    @NotNull
    public java.util.Map<String, Double> perItemReward() {
        return java.util.Collections.unmodifiableMap(perItemReward);
    }

    /**
     * Entrega por defecto para una clave "delivery-N". Si la clave no existe
     * en el pool, devuelve un Optional vacio.
     */
    @NotNull
    public Optional<DeliveryDefinition> findDelivery(@NotNull String key) {
        return buildDeliveries(8).stream().filter(d -> d.key().equals(key)).findFirst();
    }

    /**
     * Parsea una cadena con formato "1h", "30m", "1h30m" o numero a segundos.
     */
    public static long parseDuration(@org.jetbrains.annotations.Nullable String value, long fallbackSeconds) {
        if (value == null || value.isBlank()) return fallbackSeconds;
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        if (trimmed.matches("\\d+")) {
            return Math.max(60L, Long.parseLong(trimmed));
        }
        long total = 0;
        long current = 0;
        boolean any = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c >= '0' && c <= '9') {
                current = current * 10 + (c - '0');
                any = true;
            } else if (c == 'h') {
                total += current * 3600L;
                current = 0;
                any = true;
            } else if (c == 'm') {
                total += current * 60L;
                current = 0;
                any = true;
            } else if (c == 's') {
                total += current;
                current = 0;
                any = true;
            }
        }
        total += current;
        return any ? Math.max(60L, total) : fallbackSeconds;
    }

    /** Representa un ítem en el fallback-pool de la config.yml. */
    public static record FallbackItem(Material material, int minAmount, int maxAmount, double reward) {}

    public enum OrderMode {
        POR_MATERIALES,
        ORDEN_DEL_COFRE,
        ALEATORIO
    }

    public enum PriceMode {
        GLOBAL,
        UNICO
    }
}
