package com.contractnotemanager.domain;

import java.util.Optional;

/**
 * Order workflow. Statuses only ever move forward:
 * NEW → ON_MARKET → TRADED → CONFIRMED (contract note match only) → ALLOCATED.
 */
public enum OrderStatus {
    NEW("New", "Send to market"),
    ON_MARKET("On market", "Mark traded"),
    TRADED("Traded", null),          // waits for a matching contract note
    CONFIRMED("Confirmed", "Mark allocated"),
    ALLOCATED("Allocated", null);

    private final String label;
    private final String advanceAction;

    OrderStatus(String label, String advanceAction) {
        this.label = label;
        this.advanceAction = advanceAction;
    }

    public String label() {
        return label;
    }

    /** Label of the blue button, or empty when the user cannot move the order forward. */
    public Optional<String> advanceAction() {
        return Optional.ofNullable(advanceAction);
    }

    /** The status the blue button moves to; empty for TRADED (needs a contract note) and ALLOCATED. */
    public Optional<OrderStatus> nextByUser() {
        return switch (this) {
            case NEW -> Optional.of(ON_MARKET);
            case ON_MARKET -> Optional.of(TRADED);
            case CONFIRMED -> Optional.of(ALLOCATED);
            case TRADED, ALLOCATED -> Optional.empty();
        };
    }

    /** Edits are allowed until the order has been confirmed by a contract note. */
    public boolean isEditable() {
        return this == NEW || this == ON_MARKET || this == TRADED;
    }

    public boolean isAfter(OrderStatus other) {
        return ordinal() > other.ordinal();
    }

    /** Maps a Sharpfin status value to the local workflow. */
    public static Optional<OrderStatus> fromSharpfin(String sfStatus) {
        if (sfStatus == null) {
            return Optional.empty();
        }
        return switch (sfStatus.toLowerCase()) {
            case "new" -> Optional.of(NEW);
            case "on_market" -> Optional.of(ON_MARKET);
            case "traded" -> Optional.of(TRADED);
            case "finalized" -> Optional.of(ALLOCATED);
            default -> Optional.empty();
        };
    }
}
