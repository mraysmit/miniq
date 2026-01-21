package miniq.client.integration;

import miniq.client.api.MessageConsumer;
import miniq.client.api.MessageProducer;
import miniq.client.impl.SimpleMessageConsumer;
import miniq.client.impl.SimpleMessageProducer;
import miniq.config.QConfig;
import miniq.core.MiniQ;
import miniq.core.model.Message;
import miniq.core.model.MessageStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the MiniQ client.
 */
public class ClientIntegrationTest {
    
    private MiniQ miniQ;
    private QConfig sharedConfig;
    private MessageProducer producer;
    private MessageConsumer consumer;
    
    @BeforeEach
    public void setUp() throws SQLException {
        // Create a QConfig for shared and dedicated connections
        sharedConfig = new QConfig.Builder()
                .DbName("test_integration")
                .QueueName("test_queue")
                .QueueMaxSize(100)
                .CreateDb(true)
                .CreateQueue(true)
                .build();
        
        // Create a shared MiniQ instance for basic tests
        miniQ = new MiniQ(sharedConfig);
        producer = new SimpleMessageProducer(miniQ);
        consumer = new SimpleMessageConsumer(miniQ);
    }
    
    @AfterEach
    public void tearDown() {
        consumer.close();
        producer.close();
        miniQ.close();
    }
    
    @Test
    public void testProducerConsumerIntegration() throws ExecutionException, InterruptedException, SQLException {
        // Send a message
        String testData = "Test integration message";
        CompletableFuture<String> sendFuture = producer.sendMessage(testData);
        String messageId = sendFuture.get();
        
        // Verify the message was sent
        assertNotNull(messageId);
        
        // Receive the message
        CompletableFuture<Optional<Message>> receiveFuture = consumer.receiveMessage();
        Optional<Message> messageOpt = receiveFuture.get();
        
        // Verify the message was received
        assertTrue(messageOpt.isPresent());
        Message message = messageOpt.get();
        assertEquals(messageId, message.messageId());
        assertEquals(testData, message.data());
        
        // Acknowledge the message
        CompletableFuture<Void> ackFuture = consumer.acknowledgeMessage(message.messageId());
        ackFuture.get();
        
        // Verify the message status is updated
        Message updatedMessage = miniQ.get(message.messageId());
        assertEquals(MessageStatus.DONE, updatedMessage.status());
    }
    
    @Test
    public void testTopicRoutingIntegration() throws ExecutionException, InterruptedException {
        // Send messages with different topics
        producer.sendMessage("Order message", "orders.created").get();
        producer.sendMessage("User message", "users.created").get();
        
        // Receive a message with a specific topic pattern
        CompletableFuture<Optional<Message>> receiveFuture = consumer.receiveMessage("orders.*");
        Optional<Message> messageOpt = receiveFuture.get();
        
        // Verify the correct message was received
        assertTrue(messageOpt.isPresent());
        Message message = messageOpt.get();
        assertEquals("Order message", message.data());
        assertEquals("orders.created", message.topic());
    }
    
    @Test
    public void testCallbackIntegration() throws InterruptedException, ExecutionException, SQLException {
        // Close the shared producer/consumer - we'll use dedicated connections for this test
        consumer.close();
        producer.close();
        
        // Create a config for this test (don't recreate DB since it already exists)
        QConfig dedicatedConfig = new QConfig.Builder()
                .DbName("test_integration")
                .QueueName("test_queue")
                .QueueMaxSize(100)
                .CreateDb(false)  // DB already exists
                .CreateQueue(false) // Queue already exists
                .build();
        
        // Create producer and consumer with their own dedicated connections
        MessageProducer dedicatedProducer = new SimpleMessageProducer(dedicatedConfig);
        MessageConsumer dedicatedConsumer = new SimpleMessageConsumer(dedicatedConfig);
        
        try {
            // Create a latch to wait for callbacks
            CountDownLatch latch = new CountDownLatch(2);
            AtomicInteger orderCallbackCount = new AtomicInteger(0);
            AtomicInteger userCallbackCount = new AtomicInteger(0);
            
            // Register callbacks for different topic patterns
            dedicatedConsumer.onMessage("orders.*", message -> {
                orderCallbackCount.incrementAndGet();
                latch.countDown();
            });
            
            dedicatedConsumer.onMessage("users.*", message -> {
                userCallbackCount.incrementAndGet();
                latch.countDown();
            });
            
            // Send messages with different topics
            dedicatedProducer.sendMessage("Order message", "orders.created").get();
            dedicatedProducer.sendMessage("User message", "users.created").get();
            
            // Wait for both callbacks to be invoked
            boolean callbacksInvoked = latch.await(5, TimeUnit.SECONDS);
            
            // Verify the callbacks were invoked
            assertTrue(callbacksInvoked);
            assertEquals(1, orderCallbackCount.get());
            assertEquals(1, userCallbackCount.get());
        } finally {
            dedicatedConsumer.close();
            dedicatedProducer.close();
        }
    }
}