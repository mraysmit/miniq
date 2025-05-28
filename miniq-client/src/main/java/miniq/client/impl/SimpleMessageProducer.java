package miniq.client.impl;

import miniq.client.api.MessageProducer;
import miniq.core.MiniQ;
import miniq.core.model.Message;

import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * A simple implementation of the MessageProducer interface.
 */
public class SimpleMessageProducer implements MessageProducer {
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
    }
    
    @Override
    public CompletableFuture<String> sendMessage(String data) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Message message = miniQ.put(data);
                return message.messageId();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to send message", e);
            }
        }, executor);
    }
    
    @Override
    public CompletableFuture<String> sendMessage(String data, String topic) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Message message = miniQ.put(data, topic);
                return message.messageId();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to send message", e);
            }
        }, executor);
    }
    
    @Override
    public void close() {
        // No need to close the MiniQ instance here, as it might be shared
        // The caller should close the MiniQ instance when done
    }
}