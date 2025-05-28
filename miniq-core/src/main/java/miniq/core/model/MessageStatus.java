package miniq.core.model;

public enum MessageStatus {
    READY(0),
    LOCKED(1),
    DONE(2),
    FAILED(3),
    ARCHIVED(4);

    private final int value;

    MessageStatus(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}