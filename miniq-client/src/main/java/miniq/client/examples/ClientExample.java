package miniq.client.examples;

import miniq.client.api.MessageConsumer;
import miniq.client.api.MessageProducer;
import miniq.client.impl.SimpleMessageConsumer;
import miniq.client.impl.SimpleMessageProducer;
import miniq.config.QConfig;
import miniq.core.MiniQ;
import miniq.core.model.Message;

import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Example demonstrating how to use the MiniQ client.
 */
public class ClientExample {
    public static void main(String[] args) throws SQLException, ExecutionException, InterruptedException {
        // Create a MiniQ instance
        QConfig config = new QConfig.Builder()
                .DbName("example")
                .QueueName("messages")
                .QueueMaxSize(1000)
                .CreateDb(true)
                .CreateQueue(true)
                .build();
        
        MiniQ miniQ = new MiniQ(config);
        
        try {
            // Create a producer
            MessageProducer producer = new SimpleMessageProducer(miniQ);
            
            // Create a consumer
            MessageConsumer consumer = new SimpleMessageConsumer(miniQ);
            
            // Send a message
            CompletableFuture<String> future = producer.sendMessage("Hello, world!");
            String messageId = future.get();
            System.out.println("Sent message with ID: " + messageId);
            
            // Send a message with a topic
            future = producer.sendMessage("New order created", "orders.created");
            messageId = future.get();
            System.out.println("Sent message with ID: " + messageId + " and topic: orders.created");
            
            // Receive a message
            CompletableFuture<Optional<Message>> receiveFuture = consumer.receiveMessage();
            Optional<Message> messageOpt = receiveFuture.get();
            
            if (messageOpt.isPresent()) {
                Message message = messageOpt.get();
                System.out.println("Received message: " + message.data());
                
                // Acknowledge the message
                consumer.acknowledgeMessage(message.messageId()).get();
                System.out.println("Acknowledged message with ID: " + message.messageId());
            }
            
            // Receive a message with a specific topic
            receiveFuture = consumer.receiveMessage("orders.*");
            messageOpt = receiveFuture.get();
            
            if (messageOpt.isPresent()) {
                Message message = messageOpt.get();
                System.out.println("Received message with topic " + message.topic() + ": " + message.data());
                
                // Acknowledge the message
                consumer.acknowledgeMessage(message.messageId()).get();
                System.out.println("Acknowledged message with ID: " + message.messageId());
            }
            
            // Register a callback for messages
            consumer.onMessage(message -> {
                System.out.println("Received message via callback: " + message.data());
            });
            
            // Register a callback for messages with a specific topic
            consumer.onMessage("orders.*", message -> {
                System.out.println("Received order message via callback: " + message.data());
            });
            
            // Send more messages to trigger the callbacks
            producer.sendMessage("Another message");
            producer.sendMessage("Another order created", "orders.created");
            
            // Wait for the callbacks to be invoked
            Thread.sleep(2000);
            
            // Close the consumer and producer
            consumer.close();
            producer.close();
        } finally {
            // Close the MiniQ instance
            miniQ.close();
        }
    }
}