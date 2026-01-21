package miniq.client.impl;

import miniq.client.api.MessageProducer;
import miniq.config.QConfig;
import miniq.core.MiniQ;
import miniq.core.model.Message;

import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A simple implementation of the MessageProducer interface.
 */
public class SimpleMessageProducer implements MessageProducer {
    private static final Logger logger = LoggerFactory.getLogger(SimpleMessageProducer.class);

    private final MiniQ miniQ;
    private final Executor executor;
    private final boolean ownsMiniQ; // true if this producer created its own MiniQ instance

    /**
     * Creates a new SimpleMessageProducer with the specified MiniQ instance.
     * The MiniQ instance is shared and will NOT be closed when this producer is closed.
     * 
     * @param miniQ The MiniQ instance to use
     */
    public SimpleMessageProducer(MiniQ miniQ) {
        this.miniQ = miniQ;
        this.executor = Executors.newSingleThreadExecutor();
        this.ownsMiniQ = false;
        logger.debug("Created SimpleMessageProducer with shared MiniQ instance: {}", miniQ);
    }

    /**
     * Creates a new SimpleMessageProducer with its own MiniQ connection.
     * This constructor creates a dedicated database connection for this producer,
     * enabling true concurrent access without lock contention.
     * The MiniQ instance will be closed when this producer is closed.
     * 
     * @param config The QConfig to use for creating the MiniQ instance
     * @throws SQLException If there is an error creating the MiniQ instance
     */
    public SimpleMessageProducer(QConfig config) throws SQLException {
        this.miniQ = new MiniQ(config);
        this.executor = Executors.newSingleThreadExecutor();
        this.ownsMiniQ = true;
        logger.debug("Created SimpleMessageProducer with dedicated MiniQ instance: {}", miniQ);
    }

    @Override
    public CompletableFuture<String> sendMessage(String data) {
        logger.debug("Sending message with data: {}", data);
        return CompletableFuture.supplyAsync(() -> {
            try {
                Message message = miniQ.put(data);
                logger.debug("Message sent successfully with ID: {}", message.messageId());
                return message.messageId();
            } catch (SQLException e) {
                logger.error("Failed to send message: {}", data, e);
                throw new RuntimeException("Failed to send message", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<String> sendMessage(String data, String topic) {
        logger.debug("Sending message with data: {} and topic: {}", data, topic);
        return CompletableFuture.supplyAsync(() -> {
            try {
                Message message = miniQ.put(data, topic);
                logger.debug("Message sent successfully with ID: {} and topic: {}", message.messageId(), topic);
                return message.messageId();
            } catch (SQLException e) {
                logger.error("Failed to send message with topic: {}", topic, e);
                throw new RuntimeException("Failed to send message", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<String> sendMessage(String data, int priority) {
        logger.debug("Sending message with data: {} and priority: {}", data, priority);
        return CompletableFuture.supplyAsync(() -> {
            try {
                Message message = miniQ.put(data, priority);
                logger.debug("Message sent successfully with ID: {} and priority: {}", message.messageId(), priority);
                return message.messageId();
            } catch (SQLException e) {
                logger.error("Failed to send message with priority: {}", priority, e);
                throw new RuntimeException("Failed to send message", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<String> sendMessage(String data, String topic, int priority) {
        logger.debug("Sending message with data: {}, topic: {}, and priority: {}", data, topic, priority);
        return CompletableFuture.supplyAsync(() -> {
            try {
                Message message = miniQ.put(data, topic, priority);
                logger.debug("Message sent successfully with ID: {}, topic: {}, and priority: {}", message.messageId(), topic, priority);
                return message.messageId();
            } catch (SQLException e) {
                logger.error("Failed to send message with topic: {} and priority: {}", topic, priority, e);
                throw new RuntimeException("Failed to send message", e);
            }
        }, executor);
    }

    @Override
    public void close() {
        logger.info("Closing SimpleMessageProducer");
        if (ownsMiniQ && miniQ != null) {
            logger.debug("Closing owned MiniQ instance");
            miniQ.close();
        }
        logger.debug("SimpleMessageProducer closed");
    }
}
