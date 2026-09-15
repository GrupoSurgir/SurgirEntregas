package com.surgirentregas.delivery;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Define una entrega individual: material requerido, cantidad y recompensa.
 *
 * <p>Se construye a partir del item del pool (con su tipo y metadatos). La
 * cantidad se toma del item mismo (amount), y la recompensa de la
 * configuracion (global o por-item).</p>
 *
 * <p>Una entrega puede estar {@link #locked()} si el pool no tiene suficientes
 * items unicos para completar el ciclo. En ese caso el menu la muestra como
 * {@link Material#BARRIER} con nombre "BLOQUEADO".</p>
 */
public final class DeliveryDefinition {

    /** Material centinela para slots bloqueados. */
    public static final Material LOCKED_MATERIAL = Material.BARRIER;

    private final String key;
    private final Material material;
    private final int amount;
    private final double reward;
    private final int order;
    @Nullable
    private final ItemStack template;
    private final boolean locked;

    public DeliveryDefinition(@NotNull String key,
                              @NotNull Material material,
                              int amount,
                              double reward,
                              int order,
                              @Nullable ItemStack template) {
        this.key = Objects.requireNonNull(key);
        this.material = Objects.requireNonNull(material);
        this.amount = Math.max(1, amount);
        this.reward = Math.max(0.0, reward);
        this.order = Math.max(0, order);
        this.template = template == null ? null : template.clone();
        this.locked = false;
    }

    private DeliveryDefinition(@NotNull String key, int order) {
        this.key = key;
        this.material = LOCKED_MATERIAL;
        this.amount = 0;
        this.reward = 0.0;
        this.order = Math.max(0, order);
        this.template = null;
        this.locked = true;
    }

    /**
     * Crea una entrega bloqueada (el pool no tiene items unicos disponibles).
     * El menu muestra BARRIER con nombre "BLOQUEADO" en este slot.
     */
    @NotNull
    public static DeliveryDefinition locked(@NotNull String key, int order) {
        return new DeliveryDefinition(key, order);
    }

    @NotNull
    public String key() {
        return key;
    }

    @NotNull
    public Material material() {
        return material;
    }

    public int amount() {
        return amount;
    }

    public double reward() {
        return reward;
    }

    public int order() {
        return order;
    }

    /**
     * Plantilla completa del item (NBT/PDC/CustomModelData/Lore). Puede ser null
     * cuando la entrega se construyo solo con material+cantidad.
     */
    @Nullable
    public ItemStack template() {
        return template == null ? null : template.clone();
    }

    /**
     * @return {@code true} si el slot esta bloqueado por falta de items en el pool.
     */
    public boolean locked() {
        return locked;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeliveryDefinition that)) return false;
        return amount == that.amount
                && Double.compare(reward, that.reward) == 0
                && order == that.order
                && locked == that.locked
                && key.equals(that.key)
                && material == that.material;
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, material, amount, reward, order, locked);
    }
}
