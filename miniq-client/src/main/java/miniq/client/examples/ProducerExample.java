package miniq.client.examples;

import miniq.client.api.MessageProducer;
import miniq.client.impl.SimpleMessageProducer;
import miniq.config.QConfig;
import miniq.core.MiniQ;

import java.sql.SQLException;
import java.util.concurrent.ExecutionException;

/**
 * Example demonstrating how to use the MiniQ message producer.
 */
public class ProducerExample {
    public static void main(String[] args) throws SQLException, ExecutionException, InterruptedException {
        // Create a MiniQ instance
        QConfig config = new QConfig.Builder()
                .DbName("producer_example")
                .QueueName("messages")
                .QueueMaxSize(1000)
                .CreateDb(true)
                .CreateQueue(true)
                .build();
        
        MiniQ miniQ = new MiniQ(config);
        
        try {
            // Create a producer
            MessageProducer producer = new SimpleMessageProducer(miniQ);
            
            // Send a message
            String messageId = producer.sendMessage("Hello, world!").get();
            System.out.println("Sent message with ID: " + messageId);
            
            // Send a message with a topic
            messageId = producer.sendMessage("New order created", "orders.created").get();
            System.out.println("Sent message with ID: " + messageId + " and topic: orders.created");
            
            // Send multiple messages with different topics
            for (int i = 0; i < 5; i++) {
                String topic = (i % 2 == 0) ? "orders.created" : "users.updated";
                String data = "Message " + i + " with topic " + topic;
                messageId = producer.sendMessage(data, topic).get();
                System.out.println("Sent message with ID: " + messageId + " and topic: " + topic);
            }
            
            // Close the producer
            producer.close();
        } finally {
            // Close the MiniQ instance
            miniQ.close();
        }
    }
}