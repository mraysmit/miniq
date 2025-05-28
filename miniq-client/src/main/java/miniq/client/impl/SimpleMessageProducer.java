package miniq.client.impl;

import miniq.client.api.MessageProducer;
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

    /**
     * Creates a new SimpleMessageProducer with the specified MiniQ instance.
     * 
     * @param miniQ The MiniQ instance to use
     */
    public SimpleMessageProducer(MiniQ miniQ) {
        this.miniQ = miniQ;
        this.executor = Executors.newSingleThreadExecutor();
        logger.debug("Created SimpleMessageProducer with MiniQ instance: {}", miniQ);
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
    public CompletableFuture<String> sendMessage(String data, String[] routingKeySegments) {
        String routingKey = String.join(".", routingKeySegments);
        logger.debug("Sending message with data: {} and routing key segments: {}", data, routingKey);
        return sendMessage(data, routingKey);
    }

    @Override
    public void close() {
        logger.info("Closing SimpleMessageProducer");
        // No need to close the MiniQ instance here, as it might be shared
        // The caller should close the MiniQ instance when done
        logger.debug("SimpleMessageProducer closed");
    }
}
