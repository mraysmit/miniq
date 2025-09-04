package miniq.client.integration;

import miniq.config.QConfig;
import miniq.core.MiniQ;
import miniq.core.model.Message;
import miniq.core.model.MessageStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for enhanced routing with multi-segment patterns (#) in MiniQ.
 */
public class EnhancedRoutingTest {
    private static final Logger logger = LoggerFactory.getLogger(EnhancedRoutingTest.class);
    
    private MiniQ miniQ;
    
    @BeforeEach
    public void setUp() throws SQLException {
        // Create a MiniQ instance with a unique database for each test
        String dbName = "test_enhanced_routing_" + System.currentTimeMillis();
        QConfig config = new QConfig.Builder()
                .DbName(dbName)
                .QueueName("test_queue")
                .QueueMaxSize(1000)
                .CreateDb(true)
                .CreateQueue(true)
                .build();

        miniQ = new MiniQ(config);
        logger.debug("Set up MiniQ instance for enhanced routing tests with db: {}", dbName);
    }
    
    @AfterEach
    public void tearDown() {
        if (miniQ != null) {
            miniQ.close();
            logger.debug("Closed MiniQ instance");
        }
    }
    
    @Test
    public void testMultiSegmentWildcardMatching() throws SQLException {
        logger.info("Testing multi-segment wildcard (#) matching");
        
        // Put messages with different routing patterns
        miniQ.put("Order for electronics laptop", "orders.electronics.laptops.premium");
        miniQ.put("Order for electronics phone", "orders.electronics.phones.budget");
        miniQ.put("Order for books fiction", "orders.books.fiction");
        miniQ.put("User notification", "notifications.user.123.email");
        miniQ.put("System log", "logs.system.error.database");
        
        // Test # wildcard matching all segments after "orders"
        List<Message> orderMessages = miniQ.getByRoutingPattern("orders.#", MessageStatus.READY);
        assertEquals(3, orderMessages.size(), "Should match all order messages");
        
        // Test # wildcard matching all electronics orders
        List<Message> electronicsMessages = miniQ.getByRoutingPattern("orders.electronics.#", MessageStatus.READY);
        assertEquals(2, electronicsMessages.size(), "Should match all electronics orders");
        
        // Test # wildcard with specific ending
        List<Message> premiumMessages = miniQ.getAllByRoutingPattern("orders.#.premium");
        assertEquals(1, premiumMessages.size(), "Should match orders ending with premium");
        assertEquals("Order for electronics laptop", premiumMessages.get(0).data());
        
        logger.info("Multi-segment wildcard matching test passed");
    }
    
    @Test
    public void testSingleSegmentWildcardWithMultiSegment() throws SQLException {
        logger.info("Testing combination of single (*) and multi-segment (#) wildcards");
        
        // Put messages with different routing patterns
        miniQ.put("Electronics laptop order", "orders.electronics.laptops.premium");
        miniQ.put("Electronics phone order", "orders.electronics.phones.budget");
        miniQ.put("Books fiction order", "orders.books.fiction.standard");
        miniQ.put("User email notification", "notifications.user.123.email");
        
        // Test single wildcard followed by multi-segment wildcard
        List<Message> messages = miniQ.getByRoutingPattern("orders.*.#", MessageStatus.READY);
        assertEquals(3, messages.size(), "Should match all orders with any second segment");
        
        // Test specific pattern with multi-segment wildcard
        List<Message> electronicsMessages = miniQ.getByRoutingPattern("orders.electronics.#", MessageStatus.READY);
        assertEquals(2, electronicsMessages.size(), "Should match electronics orders with any segments after electronics");

        // Test single-segment wildcard (should match exactly 3 segments)
        miniQ.put("Simple electronics order", "orders.electronics.tablets");
        List<Message> singleWildcardMessages = miniQ.getByRoutingPattern("orders.electronics.*", MessageStatus.READY);
        assertEquals(1, singleWildcardMessages.size(), "Should match only the 3-segment electronics order");
        assertEquals("Simple electronics order", singleWildcardMessages.get(0).data());

        logger.info("Combined wildcard matching test passed");
    }
    
    @Test
    public void testPopWithEnhancedRouting() throws SQLException {
        logger.info("Testing pop operations with enhanced routing");
        
        // Put messages with different routing patterns
        miniQ.put("Electronics order 1", "orders.electronics.laptops.premium");
        miniQ.put("Electronics order 2", "orders.electronics.phones.budget");
        miniQ.put("Books order", "orders.books.fiction");
        miniQ.put("User notification", "notifications.user.123");
        
        // Pop with multi-segment wildcard
        Message message1 = miniQ.popWithRoutingPattern("orders.electronics.#");
        assertNotNull(message1, "Should pop an electronics order");
        assertTrue(message1.topic().startsWith("orders.electronics"), "Should be an electronics order");
        assertEquals(MessageStatus.LOCKED, message1.status(), "Message should be locked after pop");
        
        // Pop with specific pattern
        Message message2 = miniQ.popWithRoutingPattern("orders.books.#");
        assertNotNull(message2, "Should pop a books order");
        assertEquals("orders.books.fiction", message2.topic());
        assertEquals("Books order", message2.data());
        
        // Try to pop with pattern that has no matches
        Message message3 = miniQ.popWithRoutingPattern("orders.clothing.#");
        assertNull(message3, "Should not find any clothing orders");
        
        logger.info("Pop with enhanced routing test passed");
    }
    
    @Test
    public void testPeekWithEnhancedRouting() throws SQLException {
        logger.info("Testing peek operations with enhanced routing");
        
        // Put messages with different routing patterns
        miniQ.put("Electronics order", "orders.electronics.laptops.premium");
        miniQ.put("Books order", "orders.books.fiction");
        miniQ.put("User notification", "notifications.user.123");
        
        // Peek with multi-segment wildcard
        Message message1 = miniQ.peekWithRoutingPattern("orders.#");
        assertNotNull(message1, "Should peek an order message");
        assertTrue(message1.topic().startsWith("orders"), "Should be an order");
        assertEquals(MessageStatus.READY, message1.status(), "Message should remain ready after peek");
        
        // Peek again should return the same message
        Message message2 = miniQ.peekWithRoutingPattern("orders.#");
        assertNotNull(message2, "Should peek the same message again");
        assertEquals(message1.messageId(), message2.messageId(), "Should be the same message");
        
        // Peek with specific pattern
        Message message3 = miniQ.peekWithRoutingPattern("notifications.#");
        assertNotNull(message3, "Should peek a notification");
        assertEquals("notifications.user.123", message3.topic());
        
        logger.info("Peek with enhanced routing test passed");
    }
    
    @Test
    public void testExactMatchStillWorks() throws SQLException {
        logger.info("Testing that exact matching still works correctly");
        
        // Put messages with different routing patterns
        miniQ.put("Exact match message", "orders.electronics.laptops");
        miniQ.put("Similar message", "orders.electronics.laptops.premium");
        
        // Test exact match
        List<Message> exactMessages = miniQ.getByRoutingPattern("orders.electronics.laptops", MessageStatus.READY);
        assertEquals(1, exactMessages.size(), "Should match only the exact pattern");
        assertEquals("Exact match message", exactMessages.get(0).data());
        
        // Test that wildcard matches both
        List<Message> wildcardMessages = miniQ.getByRoutingPattern("orders.electronics.#", MessageStatus.READY);
        assertEquals(2, wildcardMessages.size(), "Wildcard should match both messages");
        
        logger.info("Exact matching test passed");
    }
    
    @Test
    public void testComplexRoutingPatterns() throws SQLException {
        logger.info("Testing complex routing patterns");
        
        // Put messages with complex routing patterns
        miniQ.put("Complex order 1", "orders.electronics.laptops.gaming.premium");
        miniQ.put("Complex order 2", "orders.electronics.phones.android.budget");
        miniQ.put("Complex order 3", "orders.books.fiction.scifi.hardcover");
        miniQ.put("Complex notification", "notifications.user.123.email.urgent");
        
        // Test complex multi-segment wildcard
        List<Message> messages1 = miniQ.getByRoutingPattern("orders.electronics.#.premium", MessageStatus.READY);
        assertEquals(1, messages1.size(), "Should match electronics orders ending with premium");
        assertEquals("Complex order 1", messages1.get(0).data());
        
        // Test multiple wildcards
        List<Message> messages2 = miniQ.getByRoutingPattern("orders.*.*.*.budget", MessageStatus.READY);
        assertEquals(1, messages2.size(), "Should match orders with budget at the end");
        assertEquals("Complex order 2", messages2.get(0).data());
        
        // Test # in the middle
        List<Message> messages3 = miniQ.getAllByRoutingPattern("notifications.#.urgent");
        assertEquals(1, messages3.size(), "Should match notifications ending with urgent");
        assertEquals("Complex notification", messages3.get(0).data());
        
        logger.info("Complex routing patterns test passed");
    }
    
    @Test
    public void testEmptyAndNullPatterns() throws SQLException {
        logger.info("Testing empty and null patterns");
        
        // Put some messages
        miniQ.put("Message 1", "orders.electronics");
        miniQ.put("Message 2", "notifications.user");
        
        // Test null pattern
        List<Message> nullMessages = miniQ.getAllByRoutingPattern(null);
        assertTrue(nullMessages.isEmpty(), "Null pattern should return empty list");
        
        // Test empty pattern  
        List<Message> emptyMessages = miniQ.getAllByRoutingPattern("");
        assertTrue(emptyMessages.isEmpty(), "Empty pattern should return empty list");
        
        // Test peek with null pattern
        Message peekMessage = miniQ.peekWithRoutingPattern(null);
        assertNotNull(peekMessage, "Peek with null pattern should return first message");
        
        logger.info("Empty and null patterns test passed");
    }
}
