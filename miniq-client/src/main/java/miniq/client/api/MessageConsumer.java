package miniq.client.api;

import miniq.core.model.Message;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Interface for clients that consume messages from a MiniQ queue.
 */
public interface MessageConsumer {
    /**
     * Receives a message from the queue.
     * 
     * @return A CompletableFuture that completes with an Optional containing the message, or empty if no message is available
     */
    CompletableFuture<Optional<Message>> receiveMessage();

    /**
     * Receives a message from the queue with a specific topic pattern.
     * 
     * @param topicPattern The topic pattern to match
     * @return A CompletableFuture that completes with an Optional containing the message, or empty if no message is available
     */
    CompletableFuture<Optional<Message>> receiveMessage(String topicPattern);

    /**
     * Acknowledges that a message has been processed successfully.
     * 
     * @param messageId The ID of the message to acknowledge
     * @return A CompletableFuture that completes when the acknowledgement is complete
     */
    CompletableFuture<Void> acknowledgeMessage(String messageId);

    /**
     * Rejects a message, indicating that it could not be processed.
     * 
     * @param messageId The ID of the message to reject
     * @return A CompletableFuture that completes when the rejection is complete
     */
    CompletableFuture<Void> rejectMessage(String messageId);

    /**
     * Registers a callback to be invoked when a message is received.
     * 
     * @param callback The callback to invoke
     */
    void onMessage(Consumer<Message> callback);

    /**
     * Registers a callback to be invoked when a message with a specific topic pattern is received.
     * 
     * @param topicPattern The topic pattern to match
     * @param callback The callback to invoke
     */
    void onMessage(String topicPattern, Consumer<Message> callback);

    /**
     * Closes the consumer and releases any resources.
     */
    void close();
}
