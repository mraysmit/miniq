package miniq.client.examples;

import miniq.client.api.MessageConsumer;
import miniq.client.impl.SimpleMessageConsumer;
import miniq.config.QConfig;
import miniq.core.MiniQ;
import miniq.core.model.Message;

import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/**
 * Example demonstrating how to use the MiniQ message consumer.
 */
public class ConsumerExample {
    public static void main(String[] args) throws SQLException, ExecutionException, InterruptedException {
        // Create a MiniQ instance
        QConfig config = new QConfig.Builder()
                .DbName("consumer_example")
                .QueueName("messages")
                .QueueMaxSize(1000)
                .CreateDb(true)
                .CreateQueue(true)
                .build();
        
        MiniQ miniQ = new MiniQ(config);
        
        try {
            // Put some messages in the queue for testing
            miniQ.put("Message 1");
            miniQ.put("Order message", "orders.created");
            miniQ.put("User message", "users.updated");
            
            // Create a consumer
            MessageConsumer consumer = new SimpleMessageConsumer(miniQ);
            
            // Receive a message
            Optional<Message> messageOpt = consumer.receiveMessage().get();
            if (messageOpt.isPresent()) {
                Message message = messageOpt.get();
                System.out.println("Received message: " + message.data());
                
                // Acknowledge the message
                consumer.acknowledgeMessage(message.messageId()).get();
                System.out.println("Acknowledged message with ID: " + message.messageId());
            }
            
            // Receive a message with a specific topic
            messageOpt = consumer.receiveMessage("orders.*").get();
            if (messageOpt.isPresent()) {
                Message message = messageOpt.get();
                System.out.println("Received message with topic " + message.topic() + ": " + message.data());
                
                // Reject the message
                consumer.rejectMessage(message.messageId()).get();
                System.out.println("Rejected message with ID: " + message.messageId());
            }
            
            // Register a callback for messages
            consumer.onMessage(message -> {
                System.out.println("Received message via callback: " + message.data());
            });
            
            // Put another message to trigger the callback
            miniQ.put("Callback message");
            
            // Wait for the callback to be invoked
            Thread.sleep(2000);
            
            // Close the consumer
            consumer.close();
        } finally {
            // Close the MiniQ instance
            miniQ.close();
        }
    }
}