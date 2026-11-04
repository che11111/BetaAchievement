package dev.che1s.betaachievement.run;

public enum RunStatus {
    READY,
    ACTIVE,
    COMPLETED,
    ABANDONED,
    INVALID;

    public static RunStatus fromDatabase(String value) {
        try {
            return valueOf(value);
        } catch (RuntimeException exception) {
            return INVALID;
        }
    }

    public boolean isOpen() {
        return this == READY || this == ACTIVE;
    }

    public boolean isClosed() {
        return this == COMPLETED || this == ABANDONED || this == INVALID;
    }
}
