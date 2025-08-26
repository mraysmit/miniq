package miniq.core.model;

public record Message (
                String messageId,
                String topic,
                String data,
                MessageStatus status,
                Integer priority,
                Long inTime,
                Long lockTime,
                Long doneTime) {

    /**
     * Default priority for messages when not specified
     */
    public static final int DEFAULT_PRIORITY = 5;

    /**
     * Highest priority (most urgent)
     */
    public static final int HIGH_PRIORITY = 1;

    /**
     * Lowest priority (least urgent)
     */
    public static final int LOW_PRIORITY = 10;

    /**
     * Creates a message with default priority
     */
    public static Message withDefaultPriority(String messageId, String topic, String data,
                                            MessageStatus status, Long inTime, Long lockTime, Long doneTime) {
        return new Message(messageId, topic, data, status, DEFAULT_PRIORITY, inTime, lockTime, doneTime);
    }
};