package miniq.core.model;

public record Message (
                String messageId,
                String topic,
                String data,
                MessageStatus status,
                Long inTime,
                Long lockTime,
                Long doneTime) { };