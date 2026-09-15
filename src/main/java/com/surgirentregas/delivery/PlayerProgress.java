package com.surgirentregas.delivery;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Progreso individual de un jugador para una entrega especifica del ciclo.
 */
public final class PlayerProgress {

    private final UUID playerId;
    private final String deliveryKey;
    private int currentAmount;
    private boolean completed;

    public PlayerProgress(@NotNull UUID playerId, @NotNull String deliveryKey) {
        this.playerId = playerId;
        this.deliveryKey = deliveryKey;
        this.currentAmount = 0;
        this.completed = false;
    }

    @NotNull
    public UUID playerId() {
        return playerId;
    }

    @NotNull
    public String deliveryKey() {
        return deliveryKey;
    }

    public int currentAmount() {
        return currentAmount;
    }

    public void setCurrentAmount(int amount) {
        this.currentAmount = Math.max(0, amount);
    }

    public void addProgress(int amount) {
        if (amount <= 0) return;
        this.currentAmount = Math.max(0, this.currentAmount + amount);
    }

    public boolean isCompleted() {
        return completed;
    }

    public void markCompleted() {
        this.completed = true;
    }

    public void reset() {
        this.currentAmount = 0;
        this.completed = false;
    }
}
