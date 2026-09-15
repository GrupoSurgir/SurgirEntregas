package com.surgirentregas.delivery;

import com.surgirentregas.core.SurgirEntregasPlugin;
import com.surgirentregas.economy.EconomyHook;
import com.surgirentregas.delivery.PlayerProgress;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Logica de negocio de las entregas: detecta si el jugador tiene el material,
 * lo consume, paga la recompensa y actualiza el progreso/ranking.
 *
 * <p>Trabaja sobre el inventario del jugador. Para custom models (Oraxen,
 * ItemsAdder, CustomModelData) compara tambien el template definido.</p>
 */
public final class DeliveryService {

    private final SurgirEntregasPlugin plugin;
    private final EconomyHook economy;
    private final Map<UUID, Boolean> processing = new ConcurrentHashMap<>();

    public DeliveryService(@NotNull SurgirEntregasPlugin plugin) {
        this.plugin = plugin;
        this.economy = plugin.economy();
    }

    /**
     * Lista de entregas disponibles para el ciclo actual (global para todos).
     */
    @NotNull
    public List<DeliveryDefinition> currentDeliveries() {
        return plugin.deliveryConfig().currentCycle();
    }

    /**
     * Cuenta cuanto del material tiene el jugador en su inventario siguiendo
     * reglas estrictas anti-trampa:
     *
     * <ul>
     *   <li>El item debe ser del mismo {@link Material} que pide la entrega.</li>
     *   <li>Items danados (durability > 0) NO cuentan.</li>
     *   <li>Si el template del pool tiene meta (NBT / PDC / CustomModelData),
     *       el item del jugador debe coincidir exactamente con ese template.</li>
     *   <li>Si el template NO tiene meta, solo se valida el material
     *       (asi items vanilla son aceptados sin NBT).</li>
     * </ul>
     */
    public int countInInventory(@NotNull Player player, @NotNull DeliveryDefinition delivery) {
        ItemStack[] contents = player.getInventory().getContents();
        ItemStack template = delivery.template();
        boolean templateHasMeta = template != null && template.hasItemMeta();
        int total = 0;
        for (ItemStack item : contents) {
            if (item == null || item.getType().isAir()) continue;
            if (item.getType() != delivery.material()) continue;
            // Items danados: nunca cuentan.
            if (item.getDurability() > 0) continue;
            // Si el template exige NBT/PDC/CustomModelData, el item debe ser
            // isSimilar al template (excluyendo amount).
            if (templateHasMeta) {
                if (!matchesTemplate(item, template)) continue;
            }
            total += item.getAmount();
        }
        return total;
    }

    /**
     * Compara dos items ignorando la cantidad. Requiere mismo material, misma
     * durabilidad (cero para items no danables) y misma meta (ItemMeta.isSimilar).
     */
    private boolean matchesTemplate(@NotNull ItemStack item, @NotNull ItemStack template) {
        if (item.getType() != template.getType()) return false;
        if (item.getDurability() != template.getDurability()) return false;
        boolean itemHasMeta = item.hasItemMeta();
        boolean tplHasMeta = template.hasItemMeta();
        if (!itemHasMeta && !tplHasMeta) return true;
        if (itemHasMeta != tplHasMeta) return false;
        ItemStack a = item.clone();
        a.setAmount(1);
        ItemStack b = template.clone();
        b.setAmount(1);
        return a.isSimilar(b);
    }

    /**
      * Entrega fraccionada, acumulativa e independiente.
     *
     * <p>Consume todo lo que el jugador tiene del material (hasta el requerido),
     * acredita la recompensa <b>proporcional</b> a la fraccion entregada y acumula
     * el progreso. No exige completar el paquete de un solo golpe: cada entrega
     * parcial suma su parte y cobra su parte.</p>
     *
     * <p>UNICO: cada entrega paga independientemente.
     * GLOBAL: solo consume items y acumula progreso; el pago final
     * (price.final) se acredita cuando TODAS las entregas se completan.</p>
     *
     * <p>Una entrega ya completada queda bloqueada para TODOS los jugadores,
     * incluidos operadores (OP): la restriccion es global, sin bypass.</p>
     */
     @NotNull
     public Result submit(@NotNull Player player, @NotNull DeliveryDefinition delivery) {
         UUID id = player.getUniqueId();
         Boolean prev = processing.putIfAbsent(id, Boolean.TRUE);
         if (prev != null) {
             return new Result.InProgress();
         }
         try {
             if (delivery.locked()) {
                 return new Result.NoItems();
             }
             // Restriccion global anti-repeticion: aplica tambien a OP.
             if (plugin.stats().hasCompleted(id, delivery.key())) {
                 plugin.getLogger().info("[Entrega] Ya completada por " + player.getName() + ": " + delivery.key());
                 return new Result.AlreadyCompleted();
             }
             int has = countInInventory(player, delivery);
             if (has <= 0) {
                 return new Result.NoItems();
             }
             int required = delivery.amount();
             int toConsume = Math.min(has, required);
             if (!consume(player, delivery, toConsume)) {
                 plugin.getLogger().warning("[Entrega] Fallo al consumir items para " + player.getName() + ": " + delivery.key());
                 return new Result.ConsumeFailed();
             }
             // Puntos fraccionarios inmediatos al ranking (cada entrega aporta).
             double fractionalPoints = (double) toConsume / (double) Math.max(1, required);
             plugin.stats().addPoints(id, fractionalPoints);
             // Registrar progreso.
             PlayerProgress progress = plugin.stats().getProgress(id, delivery.key());
             progress.addProgress(toConsume);
             if (progress.currentAmount() >= required) {
                 progress.setCurrentAmount(required);
                 progress.markCompleted();
                 plugin.stats().markCompleted(id, delivery.key());
                 plugin.getLogger().info("[Entrega] Entrega COMPLETADA: " + delivery.key() + " para " + player.getName());
             } else {
                 plugin.getLogger().info("[Entrega] Entrega PARCIAL: " + player.getName() + " tiene " + progress.currentAmount() + "/" + required + " de " + delivery.key() + " | Puntos ranking: +" + String.format("%.2f", fractionalPoints));
             }

             // Modo de precio: UNICO paga por entrega; GLOBAL solo paga al final.
             boolean isGlobal = plugin.deliveryConfig().priceMode() == DeliveryConfig.PriceMode.GLOBAL;
             double credited = 0.0;
             if (!isGlobal) {
                 // UNICO: recompensa proporcional inmediata.
                 credited = proportionalReward(delivery.reward(), required, toConsume);
                 if (economy.isAvailable()) {
                     boolean paid = economy.deposit(player, credited);
                     if (!paid) {
                         plugin.getLogger().warning("[Entrega] Fallo al acreditar $" + credited + " a " + player.getName() + "!");
                         return new Result.ConsumeFailed();
                     }
                     plugin.getLogger().info("[Entrega] Pagado $" + credited + " a " + player.getName() + " por " + delivery.key());
                 }
             }

             if (progress.currentAmount() >= required) {
                 return new Result.Completed(credited, delivery);
             }
             return new Result.Partial(progress.currentAmount(), required, credited);
         } finally {
             processing.remove(id);
         }
     }

    /**
     * Recompensa proporcional a la fraccion entregada ({@code delivered}/{@code required}).
     */
    private double proportionalReward(double reward, int required, int delivered) {
        if (required <= 0) return reward;
        if (delivered <= 0) return 0.0;
        return reward * ((double) delivered / (double) required);
    }

    private boolean consume(@NotNull Player player, @NotNull DeliveryDefinition delivery, int amount) {
        ItemStack template = delivery.template();
        int pending = amount;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && pending > 0; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType().isAir()) continue;
            if (item.getType() != delivery.material()) continue;
            if (template != null && template.hasItemMeta()
                    && !matchesTemplate(item, template)) {
                continue;
            }
            int take = Math.min(item.getAmount(), pending);
            pending -= take;
            if (item.getAmount() == take) {
                player.getInventory().setItem(i, null);
            } else {
                item.setAmount(item.getAmount() - take);
                player.getInventory().setItem(i, item);
            }
        }
        return pending == 0;
    }

    /**
     * Resultado del intento de entrega.
     */
    public sealed interface Result {
        record Completed(double credited, DeliveryDefinition delivery) implements Result {}
        record Partial(int current, int required, double credited) implements Result {}
        record AlreadyCompleted() implements Result {}
        record NoItems() implements Result {}
        record ConsumeFailed() implements Result {}
        record InProgress() implements Result {}
    }

    /**
     * Atajo para compatibilidad: vista de los 3 slots del menu.
     */
    @NotNull
    public Map<String, Object> describe() {
        Map<String, Object> m = new HashMap<>();
        m.put("deliveries", currentDeliveries());
        return m;
    }
}
