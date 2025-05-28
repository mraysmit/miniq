package miniq.client.impl;

import miniq.client.api.MessageProducer;
import miniq.config.QConfig;
import miniq.core.MiniQ;
import miniq.core.model.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link SimpleMessageProducer}.
 */
public class SimpleMessageProducerTest {
    
    private MiniQ miniQ;
    private MessageProducer producer;
    
    @BeforeEach
    public void setUp() throws SQLException {
        // Create a MiniQ instance with an in-memory database for testing
        QConfig config = new QConfig.Builder()
                .DbName("test_producer")
                .QueueName("test_queue")
                .QueueMaxSize(100)
                .CreateDb(true)
                .CreateQueue(true)
                .build();
        
        miniQ = new MiniQ(config);
        producer = new SimpleMessageProducer(miniQ);
    }
    
    @AfterEach
    public void tearDown() {
        producer.close();
        miniQ.close();
    }
    
    @Test
    public void testSendMessage() throws ExecutionException, InterruptedException, SQLException {
        // Send a message
        String testData = "Test message data";
        CompletableFuture<String> future = producer.sendMessage(testData);
        String messageId = future.get();
        
        // Verify the message was sent
        assertNotNull(messageId);
        
        // Retrieve the message from the queue
        Message message = miniQ.get(messageId);
        
        // Verify the message data
        assertNotNull(message);
        assertEquals(testData, message.data());
        assertNull(message.topic());
    }
    
    @Test
    public void testSendMessageWithTopic() throws ExecutionException, InterruptedException, SQLException {
        // Send a message with a topic
        String testData = "Test message with topic";
        String testTopic = "test.topic";
        CompletableFuture<String> future = producer.sendMessage(testData, testTopic);
        String messageId = future.get();
        
        // Verify the message was sent
        assertNotNull(messageId);
        
        // Retrieve the message from the queue
        Message message = miniQ.get(messageId);
        
        // Verify the message data and topic
        assertNotNull(message);
        assertEquals(testData, message.data());
        assertEquals(testTopic, message.topic());
    }
}