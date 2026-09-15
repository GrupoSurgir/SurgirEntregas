package com.surgirentregas.menu;

import com.surgirentregas.core.SurgirEntregasPlugin;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Holder / estado de sesion del menu admin (45 slots).
 *
 * <p>Almacena:
 * <ul>
 *   <li>Unidad de tiempo activa (HORAS / MINUTOS / SEGUNDOS) para los botones
 *       + y - (29 y 31).</li>
 *   <li>Valor por unidad que se sumara / restara al pulsar +/-.</li>
 * </ul>
 *
 * <p>Esta clase NO persiste — el estado vive mientras el menu esta abierto.
 * El timer real vive en {@link com.surgirentregas.delivery.DeliveryConfig}.</p>
 */
public final class AdminMenuHolder {

    /** Unidades de tiempo seleccionables en el boton 30 (RECOVERY_COMPASS). */
    public enum TimeUnit {
        HORAS(3600L, "H"),
        MINUTOS(60L, "M"),
        SEGUNDOS(1L, "S");

        public final long seconds;
        public final String shortName;

        TimeUnit(long seconds, String shortName) {
            this.seconds = seconds;
            this.shortName = shortName;
        }

        @NotNull
        public TimeUnit next() {
            return switch (this) {
                case HORAS -> MINUTOS;
                case MINUTOS -> SEGUNDOS;
                case SEGUNDOS -> HORAS;
            };
        }
    }

    private final SurgirEntregasPlugin plugin;
    private final Player viewer;
    private TimeUnit timeUnit = TimeUnit.HORAS;

    public AdminMenuHolder(@NotNull SurgirEntregasPlugin plugin, @NotNull Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
    }

    @NotNull
    public SurgirEntregasPlugin plugin() {
        return plugin;
    }

    @NotNull
    public Player viewer() {
        return viewer;
    }

    @NotNull
    public TimeUnit timeUnit() {
        return timeUnit;
    }

    public void toggleTimeUnit() {
        this.timeUnit = timeUnit.next();
    }

    /**
     * Suma 1 unidad al timer actual (segun la unidad seleccionada) y guarda.
     */
    public void incrementTime() {
        long current = plugin.deliveryConfig().rotationSeconds();
        long newValue = current + timeUnit.seconds;
        plugin.deliveryConfig().setRotationSeconds(newValue);
        plugin.restartCycleTask();
    }

    public void decrementTime() {
        long current = plugin.deliveryConfig().rotationSeconds();
        long newValue = Math.max(60L, current - timeUnit.seconds);
        plugin.deliveryConfig().setRotationSeconds(newValue);
        plugin.restartCycleTask();
    }
}
