package miniq.client.api;

import java.util.concurrent.CompletableFuture;

/**
 * Interface for clients that produce messages to a MiniQ queue.
 */
public interface MessageProducer {
    /**
     * Sends a message to the queue.
     * 
     * @param data The message data
     * @return A CompletableFuture that completes with the message ID when the message is sent
     */
    CompletableFuture<String> sendMessage(String data);

    /**
     * Sends a message to the queue with a specific topic.
     * 
     * @param data The message data
     * @param topic The message topic
     * @return A CompletableFuture that completes with the message ID when the message is sent
     */
    CompletableFuture<String> sendMessage(String data, String topic);

    /**
     * Sends a message to the queue with a specific routing key composed of segments.
     * 
     * @param data The message data
     * @param routingKeySegments The segments of the routing key
     * @return A CompletableFuture that completes with the message ID when the message is sent
     */
    CompletableFuture<String> sendMessage(String data, String[] routingKeySegments);

    /**
     * Closes the producer and releases any resources.
     */
    void close();
}
