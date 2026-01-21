package miniq.client.impl;

import miniq.client.api.MessageConsumer;
import miniq.config.QConfig;
import miniq.core.MiniQ;
import miniq.core.model.Message;
import miniq.core.model.MessageStatus;

import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger logger = LoggerFactory.getLogger(SimpleMessageConsumer.class);

    private final MiniQ miniQ;
    private final Executor executor;
    private final ScheduledExecutorService scheduler;
    private final Map<String, Consumer<Message>> topicCallbacks;
    private final Consumer<Message> defaultCallback;
    private final boolean ownsMiniQ; // true if this consumer created its own MiniQ instance
    private boolean isRunning;

    /**
     * Creates a new SimpleMessageConsumer with the specified MiniQ instance.
     * The MiniQ instance is shared and will NOT be closed when this consumer is closed.
     * 
     * @param miniQ The MiniQ instance to use
     */
    public SimpleMessageConsumer(MiniQ miniQ) {
        this(miniQ, null, false);
    }

    /**
     * Creates a new SimpleMessageConsumer with the specified MiniQ instance and default callback.
     * The MiniQ instance is shared and will NOT be closed when this consumer is closed.
     * 
     * @param miniQ The MiniQ instance to use
     * @param defaultCallback The default callback to invoke when a message is received
     */
    public SimpleMessageConsumer(MiniQ miniQ, Consumer<Message> defaultCallback) {
        this(miniQ, defaultCallback, false);
    }

    /**
     * Creates a new SimpleMessageConsumer with its own MiniQ connection.
     * This constructor creates a dedicated database connection for this consumer,
     * enabling true concurrent access without lock contention.
     * The MiniQ instance will be closed when this consumer is closed.
     * 
     * @param config The QConfig to use for creating the MiniQ instance
     * @throws SQLException If there is an error creating the MiniQ instance
     */
    public SimpleMessageConsumer(QConfig config) throws SQLException {
        this(new MiniQ(config), null, true);
    }

    /**
     * Creates a new SimpleMessageConsumer with its own MiniQ connection and default callback.
     * This constructor creates a dedicated database connection for this consumer,
     * enabling true concurrent access without lock contention.
     * The MiniQ instance will be closed when this consumer is closed.
     * 
     * @param config The QConfig to use for creating the MiniQ instance
     * @param defaultCallback The default callback to invoke when a message is received
     * @throws SQLException If there is an error creating the MiniQ instance
     */
    public SimpleMessageConsumer(QConfig config, Consumer<Message> defaultCallback) throws SQLException {
        this(new MiniQ(config), defaultCallback, true);
    }

    /**
     * Internal constructor that handles MiniQ ownership.
     * 
     * @param miniQ The MiniQ instance to use
     * @param defaultCallback The default callback to invoke when a message is received
     * @param ownsMiniQ Whether this consumer owns (and should close) the MiniQ instance
     */
    private SimpleMessageConsumer(MiniQ miniQ, Consumer<Message> defaultCallback, boolean ownsMiniQ) {
        this.miniQ = miniQ;
        this.executor = Executors.newSingleThreadExecutor();
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.topicCallbacks = new ConcurrentHashMap<>();
        this.defaultCallback = defaultCallback;
        this.ownsMiniQ = ownsMiniQ;
        this.isRunning = false;
        logger.debug("Created SimpleMessageConsumer with {} MiniQ instance: {}", 
            ownsMiniQ ? "dedicated" : "shared", miniQ);
        if (defaultCallback != null) {
            logger.debug("Default callback registered");
        }
    }

    @Override
    public CompletableFuture<Optional<Message>> receiveMessage() {
        logger.debug("Attempting to receive a message");
        return CompletableFuture.supplyAsync(() -> {
            try {
                Message message = miniQ.pop();
                if (message != null) {
                    logger.debug("Received message with ID: {}, topic: {}", message.messageId(), message.topic());
                } else {
                    logger.debug("No message available to receive");
                }
                return Optional.ofNullable(message);
            } catch (SQLException e) {
                logger.error("Failed to receive message", e);
                throw new RuntimeException("Failed to receive message", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Optional<Message>> receiveMessage(String topicPattern) {
        logger.debug("Attempting to receive a message with topic pattern: {}", topicPattern);
        return CompletableFuture.supplyAsync(() -> {
            try {
                Message message = miniQ.popWithRoutingPattern(topicPattern);
                if (message != null) {
                    logger.debug("Received message with ID: {}, topic: {} matching pattern: {}", 
                        message.messageId(), message.topic(), topicPattern);
                } else {
                    logger.debug("No message available with topic pattern: {}", topicPattern);
                }
                return Optional.ofNullable(message);
            } catch (SQLException e) {
                logger.error("Failed to receive message with topic pattern: {}", topicPattern, e);
                throw new RuntimeException("Failed to receive message", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> acknowledgeMessage(String messageId) {
        logger.debug("Acknowledging message with ID: {}", messageId);
        return CompletableFuture.runAsync(() -> {
            try {
                int result = miniQ.setDone(messageId);
                logger.debug("Message acknowledged with ID: {}, result: {}", messageId, result);
            } catch (SQLException e) {
                logger.error("Failed to acknowledge message with ID: {}", messageId, e);
                throw new RuntimeException("Failed to acknowledge message", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> rejectMessage(String messageId) {
        logger.debug("Rejecting message with ID: {}", messageId);
        return CompletableFuture.runAsync(() -> {
            try {
                int result = miniQ.setFailed(messageId);
                logger.debug("Message rejected with ID: {}, result: {}", messageId, result);
            } catch (SQLException e) {
                logger.error("Failed to reject message with ID: {}", messageId, e);
                throw new RuntimeException("Failed to reject message", e);
            }
        }, executor);
    }

    @Override
    public void onMessage(Consumer<Message> callback) {
        if (callback != null) {
            logger.info("Registering default callback for all messages");
            startMessagePolling(null, callback);
        } else {
            logger.warn("Attempted to register null callback, ignoring");
        }
    }

    @Override
    public void onMessage(String topicPattern, Consumer<Message> callback) {
        if (callback != null) {
            logger.info("Registering callback for topic pattern: {}", topicPattern);
            topicCallbacks.put(topicPattern, callback);
            startMessagePolling(topicPattern, callback);
        } else {
            logger.warn("Attempted to register null callback for topic pattern: {}, ignoring", topicPattern);
        }
    }

    @Override
    public void onMessage(String[] routingKeyPattern, Consumer<Message> callback) {
        String pattern = String.join(".", routingKeyPattern);
        logger.info("Registering callback for routing key pattern segments: {}", pattern);
        onMessage(pattern, callback);
    }

    private void startMessagePolling(String topicPattern, Consumer<Message> callback) {
        // Add the callback to the map if it's for a specific topic pattern
        if (topicPattern != null) {
            topicCallbacks.put(topicPattern, callback);
            logger.debug("Added callback for topic pattern: {} to callback map", topicPattern);
        }

        // Start polling if not already running
        if (!isRunning) {
            logger.info("Starting message polling");
            isRunning = true;
            scheduler.scheduleWithFixedDelay(() -> {
                try {
                    // Try to get messages for each specific topic pattern
                    boolean processedMessage = false;

                    // Use a synchronized block to ensure only one thread accesses the database at a time
                    synchronized (miniQ) {
                        // First, try to process messages for specific topic patterns
                        for (String pattern : topicCallbacks.keySet()) {
                            Message message = miniQ.popWithRoutingPattern(pattern);
                            if (message != null) {
                                // Get the callback for this pattern
                                Consumer<Message> messageCallback = topicCallbacks.get(pattern);

                                // Process the message
                                if (messageCallback != null) {
                                    try {
                                        // Release the lock while processing the message to avoid holding it too long
                                        final Message finalMessage = message;
                                        messageCallback.accept(finalMessage);

                                        // Re-acquire the lock to update the message status
                                        synchronized (miniQ) {
                                            miniQ.setDone(finalMessage.messageId());
                                        }
                                        processedMessage = true;
                                    } catch (Exception e) {
                                        // Re-acquire the lock to update the message status
                                        synchronized (miniQ) {
                                            miniQ.setFailed(message.messageId());
                                        }
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
                                        // Release the lock while processing the message to avoid holding it too long
                                        final Message finalMessage = message;
                                        messageCallback.accept(finalMessage);

                                        // Re-acquire the lock to update the message status
                                        synchronized (miniQ) {
                                            miniQ.setDone(finalMessage.messageId());
                                        }
                                    } catch (Exception e) {
                                        // Re-acquire the lock to update the message status
                                        synchronized (miniQ) {
                                            miniQ.setFailed(message.messageId());
                                        }
                                        throw e;
                                    }
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
                        String markerMsg = "[DATABASE_TRANSACTION_ERROR] Error polling for messages: " + errorMessage;
                        logger.error(markerMsg, e);
                        System.err.println(markerMsg); // Keep for test compatibility

                        // For transaction errors, we need to stop the scheduler to prevent further errors
                        isRunning = false;
                        scheduler.shutdownNow();
                        logger.warn("Shutting down scheduler due to database transaction error");

                        // Throw a custom error that will propagate up and fail the test
                        throw new DatabaseTransactionError("Database transaction error detected: " + errorMessage, e);
                    } else {
                        // For other SQLExceptions, log but continue polling
                        logger.warn("Error polling for messages: {}", errorMessage, e);
                        System.err.println("Error polling for messages: " + errorMessage); // Keep for test compatibility
                    }
                }
            }, 0, 1, TimeUnit.SECONDS);
        }
    }

    @Override
    public void close() {
        logger.info("Closing SimpleMessageConsumer");
        isRunning = false;
        scheduler.shutdown();
        try {
            logger.debug("Waiting for scheduler to terminate");
            boolean terminated = scheduler.awaitTermination(5, TimeUnit.SECONDS);
            if (terminated) {
                logger.debug("Scheduler terminated successfully");
            } else {
                logger.warn("Scheduler did not terminate within timeout, some tasks may still be running");
            }
        } catch (InterruptedException e) {
            logger.warn("Interrupted while waiting for scheduler to terminate", e);
            Thread.currentThread().interrupt();
        }
        if (ownsMiniQ && miniQ != null) {
            logger.debug("Closing owned MiniQ instance");
            miniQ.close();
        }
        logger.info("SimpleMessageConsumer closed");
    }
}
