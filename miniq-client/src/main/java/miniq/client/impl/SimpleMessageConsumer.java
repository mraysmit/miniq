package miniq.client.impl;

import miniq.client.api.MessageConsumer;
import miniq.core.MiniQ;
import miniq.core.model.Message;
import miniq.core.model.MessageStatus;

import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom error for database transaction errors.
 * This is an Error, not an Exception, so it will not be caught by normal exception handling.
 */
class DatabaseTransactionError extends Error {
    public DatabaseTransactionError(String message, Throwable cause) {
        super(message, cause);
    }
}

/**
 * A simple implementation of the MessageConsumer interface.
 */
public class SimpleMessageConsumer implements MessageConsumer {
    private final MiniQ miniQ;
    private final Executor executor;
    private final ScheduledExecutorService scheduler;
    private final Map<String, Consumer<Message>> topicCallbacks;
    private final Consumer<Message> defaultCallback;
    private boolean isRunning;

    /**
     * Creates a new SimpleMessageConsumer with the specified MiniQ instance.
     * 
     * @param miniQ The MiniQ instance to use
     */
    public SimpleMessageConsumer(MiniQ miniQ) {
        this(miniQ, null);
    }

    /**
     * Creates a new SimpleMessageConsumer with the specified MiniQ instance and default callback.
     * 
     * @param miniQ The MiniQ instance to use
     * @param defaultCallback The default callback to invoke when a message is received
     */
    public SimpleMessageConsumer(MiniQ miniQ, Consumer<Message> defaultCallback) {
        this.miniQ = miniQ;
        this.executor = Executors.newSingleThreadExecutor();
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.topicCallbacks = new ConcurrentHashMap<>();
        this.defaultCallback = defaultCallback;
        this.isRunning = false;
    }

    @Override
    public CompletableFuture<Optional<Message>> receiveMessage() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Message message = miniQ.pop();
                return Optional.ofNullable(message);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to receive message", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Optional<Message>> receiveMessage(String topicPattern) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Message message = miniQ.popWithRoutingPattern(topicPattern);
                return Optional.ofNullable(message);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to receive message", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> acknowledgeMessage(String messageId) {
        return CompletableFuture.runAsync(() -> {
            try {
                miniQ.setDone(messageId);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to acknowledge message", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> rejectMessage(String messageId) {
        return CompletableFuture.runAsync(() -> {
            try {
                miniQ.setFailed(messageId);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to reject message", e);
            }
        }, executor);
    }

    @Override
    public void onMessage(Consumer<Message> callback) {
        if (callback != null) {
            startMessagePolling(null, callback);
        }
    }

    @Override
    public void onMessage(String topicPattern, Consumer<Message> callback) {
        if (callback != null) {
            topicCallbacks.put(topicPattern, callback);
            startMessagePolling(topicPattern, callback);
        }
    }

    private void startMessagePolling(String topicPattern, Consumer<Message> callback) {
        // Add the callback to the map if it's for a specific topic pattern
        if (topicPattern != null) {
            topicCallbacks.put(topicPattern, callback);
        }

        // Start polling if not already running
        if (!isRunning) {
            isRunning = true;
            scheduler.scheduleWithFixedDelay(() -> {
                try {
                    // Try to get messages for each specific topic pattern
                    boolean processedMessage = false;

                    // First, try to process messages for specific topic patterns
                    for (String pattern : topicCallbacks.keySet()) {
                        Message message = miniQ.popWithRoutingPattern(pattern);
                        if (message != null) {
                            // Get the callback for this pattern
                            Consumer<Message> messageCallback = topicCallbacks.get(pattern);

                            // Process the message
                            if (messageCallback != null) {
                                try {
                                    messageCallback.accept(message);
                                    miniQ.setDone(message.messageId());
                                    processedMessage = true;
                                } catch (Exception e) {
                                    miniQ.setFailed(message.messageId());
                                    throw e;
                                }
                            }
                        }
                    }

                    // If no message was processed for specific patterns, try to get any message
                    if (!processedMessage) {
                        Message message = miniQ.pop();
                        if (message != null) {
                            // Find the appropriate callback based on the message topic
                            Consumer<Message> messageCallback = null;
                            if (message.topic() != null) {
                                for (Map.Entry<String, Consumer<Message>> entry : topicCallbacks.entrySet()) {
                                    String pattern = entry.getKey();
                                    // Convert the pattern to a regex pattern
                                    String regexPattern = pattern.replace(".", "\\.").replace("*", ".*");
                                    if (message.topic().matches(regexPattern)) {
                                        messageCallback = entry.getValue();
                                        break;
                                    }
                                }
                            }

                            // If no specific callback found, use the default or the provided callback
                            if (messageCallback == null) {
                                messageCallback = defaultCallback != null ? defaultCallback : callback;
                            }

                            // Process the message
                            if (messageCallback != null) {
                                try {
                                    messageCallback.accept(message);
                                    miniQ.setDone(message.messageId());
                                } catch (Exception e) {
                                    miniQ.setFailed(message.messageId());
                                    throw e;
                                }
                            }
                        }
                    }
                } catch (SQLException e) {
                    String errorMessage = e.getMessage();

                    // Check if this is a transaction-related error
                    if (errorMessage != null && 
                        (errorMessage.contains("cannot start a transaction within a transaction") ||
                         errorMessage.contains("no transaction is active"))) {
                        // Log with a special marker that can be detected by tests
                        System.err.println("[DATABASE_TRANSACTION_ERROR] Error polling for messages: " + errorMessage);

                        // For transaction errors, we need to stop the scheduler to prevent further errors
                        isRunning = false;
                        scheduler.shutdownNow();

                        // Log the error with a special marker that can be detected by tests
                        System.err.println("[DATABASE_TRANSACTION_ERROR] Error polling for messages: " + errorMessage);

                        // Throw a custom error that will propagate up and fail the test
                        throw new DatabaseTransactionError("Database transaction error detected: " + errorMessage, e);
                    } else {
                        // For other SQLExceptions, log but continue polling
                        System.err.println("Error polling for messages: " + errorMessage);
                    }
                }
            }, 0, 1, TimeUnit.SECONDS);
        }
    }

    @Override
    public void close() {
        isRunning = false;
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // No need to close the MiniQ instance here, as it might be shared
        // The caller should close the MiniQ instance when done
    }
}
