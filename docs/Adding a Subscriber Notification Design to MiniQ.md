
# Adding a Subscriber Notification Design to MiniQ

To implement a subscriber notification design for the MiniQ project, we need to create a system that allows clients to register interest in specific message topics and receive asynchronous notifications when matching messages arrive in the queue.

## Design Approaches Comparison

There are three possible approaches to implementing subscriber notifications:

| Approach | Description | Pros | Cons |
|----------|-------------|------|------|
| **SQLite Update Hook** (Recommended) | Use SQLite's native `sqlite3_update_hook` callback | Catches ALL changes including external processes, database-level guarantee | Driver-specific, single callback per connection |
| Application-Level Trigger | Call notification logic directly from `put()` method | Simple implementation, no SQLite-specific code | Only notifies for messages inserted via the same MiniQ instance |
| Polling | Periodically query for new messages | Works across processes/connections, simple | Higher latency, wasted resources, not true push |

## Recommended Design: SQLite Update Hook

The preferred approach uses SQLite's native update hook mechanism, which provides database-level notification guarantees. The SQLite JDBC driver (`org.sqlite.SQLiteConnection`) exposes this functionality via the `SQLiteUpdateListener` interface.

### Why SQLite Update Hook?

1. **Catches ALL changes**: Notifications are triggered for any INSERT to the messages table, regardless of source
2. **Database-level guarantee**: The hook is called by SQLite itself, not application code
3. **Works with external processes**: If another process or tool inserts messages, subscribers are still notified
4. **No missed notifications**: Unlike polling, there's no window where messages could be missed
5. **Immediate notification**: Zero latency compared to polling intervals

## Implementation Details

### 1. Create a Subscriber Interface

First, let's create a `Subscriber` interface that clients will implement to receive notifications:

```java
package miniq.core.subscription;

import miniq.core.model.Message;

public interface Subscriber {
    /**
     * Called when a message matching the subscription pattern arrives
     * @param message The message that triggered the notification
     */
    void onMessageArrived(Message message);
    
    /**
     * Get the ID of this subscriber (used for subscription management)
     * @return A unique identifier for this subscriber
     */
    String getSubscriberId();
}
```

### 2. Create a Subscription Class

Next, let's create a `Subscription` class to represent a subscription:

```java
package miniq.core.subscription;

public record Subscription(
    String subscriberId,
    String routingPattern,
    Subscriber subscriber
) {}
```

### 3. Create a SubscriptionManager Class with SQLite Update Hook

The `SubscriptionManager` class uses SQLite's native update hook to detect new messages:

```java
package miniq.core.subscription;

import miniq.core.model.Message;
import miniq.core.model.MessageStatus;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteUpdateListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SubscriptionManager implements SQLiteUpdateListener {
    private static final Logger logger = LoggerFactory.getLogger(SubscriptionManager.class);
    
    // Map of routing patterns to sets of subscribers
    private final Map<String, Set<Subscription>> subscriptions = new ConcurrentHashMap<>();
    
    // Executor service for asynchronous notification delivery
    private final ExecutorService notificationExecutor;
    
    // Reference to the connection for fetching message details
    private final Connection connection;
    
    // The table name to monitor
    private final String tableName;
    
    public SubscriptionManager(Connection connection, String queueName) {
        this.connection = connection;
        this.tableName = queueName;
        this.notificationExecutor = Executors.newCachedThreadPool();
        
        // Register the SQLite update hook
        registerUpdateHook();
    }
    
    /**
     * Register the SQLite update hook to receive notifications on table changes
     */
    private void registerUpdateHook() {
        try {
            if (connection.isWrapperFor(SQLiteConnection.class)) {
                SQLiteConnection sqliteConn = connection.unwrap(SQLiteConnection.class);
                sqliteConn.addUpdateListener(this);
                logger.info("SQLite update hook registered for table: {}", tableName);
            } else {
                logger.warn("Connection is not a SQLite connection, update hook not available");
            }
        } catch (SQLException e) {
            logger.error("Failed to register SQLite update hook: {}", e.getMessage(), e);
        }
    }
    
    /**
     * SQLite update hook callback - called by SQLite on INSERT, UPDATE, DELETE
     */
    @Override
    public void onUpdate(Type type, String database, String table, long rowId) {
        // Only process INSERTs to our messages table
        if (type != Type.INSERT || !table.equals(tableName)) {
            return;
        }
        
        logger.debug("SQLite update hook triggered: {} on table {} rowId {}", type, table, rowId);
        
        // Fetch the message details asynchronously and notify subscribers
        notificationExecutor.submit(() -> {
            try {
                Message message = fetchMessageByRowId(rowId);
                if (message != null && message.topic() != null) {
                    notifySubscribers(message);
                }
            } catch (SQLException e) {
                logger.error("Error fetching message for rowId {}: {}", rowId, e.getMessage(), e);
            }
        });
    }
    
    /**
     * Fetch a message by its SQLite rowId
     */
    private Message fetchMessageByRowId(long rowId) throws SQLException {
        String sql = "SELECT id, topic, data, status, in_time, done_time, locked_until " +
                     "FROM " + tableName + " WHERE rowid = ?";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, rowId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new Message(
                        rs.getString("id"),
                        rs.getString("topic"),
                        rs.getString("data"),
                        MessageStatus.valueOf(rs.getString("status")),
                        rs.getLong("in_time"),
                        rs.getLong("done_time"),
                        rs.getLong("locked_until")
                    );
                }
            }
        }
        return null;
    }
    
    /**
     * Subscribe to messages matching a routing pattern
     * @param routingPattern The routing pattern to match (supports wildcards)
     * @param subscriber The subscriber to notify
     * @return true if subscription was added, false if it already existed
     */
    public boolean subscribe(String routingPattern, Subscriber subscriber) {
        String subscriberId = subscriber.getSubscriberId();
        Subscription subscription = new Subscription(subscriberId, routingPattern, subscriber);
        
        // Get or create the set of subscriptions for this pattern
        Set<Subscription> patternSubscriptions = subscriptions.computeIfAbsent(
            routingPattern, k -> ConcurrentHashMap.newKeySet()
        );
        
        // Add the subscription
        boolean added = patternSubscriptions.add(subscription);
        if (added) {
            logger.info("Added subscription for subscriber {} with pattern {}", 
                subscriberId, routingPattern);
        }
        return added;
    }
    
    /**
     * Unsubscribe from messages matching a routing pattern
     * @param routingPattern The routing pattern to unsubscribe from
     * @param subscriberId The ID of the subscriber to unsubscribe
     * @return true if subscription was removed, false if it didn't exist
     */
    public boolean unsubscribe(String routingPattern, String subscriberId) {
        Set<Subscription> patternSubscriptions = subscriptions.get(routingPattern);
        if (patternSubscriptions == null) {
            return false;
        }
        
        boolean removed = patternSubscriptions.removeIf(s -> s.subscriberId().equals(subscriberId));
        if (removed) {
            logger.info("Removed subscription for subscriber {} with pattern {}", 
                subscriberId, routingPattern);
            
            // Clean up empty sets
            if (patternSubscriptions.isEmpty()) {
                subscriptions.remove(routingPattern);
            }
        }
        return removed;
    }
    
    /**
     * Unsubscribe a subscriber from all patterns
     * @param subscriberId The ID of the subscriber to unsubscribe
     * @return The number of subscriptions removed
     */
    public int unsubscribeAll(String subscriberId) {
        int count = 0;
        for (Map.Entry<String, Set<Subscription>> entry : subscriptions.entrySet()) {
            String pattern = entry.getKey();
            Set<Subscription> subs = entry.getValue();
            
            boolean removed = subs.removeIf(s -> s.subscriberId().equals(subscriberId));
            if (removed) {
                count++;
                logger.info("Removed subscription for subscriber {} with pattern {}", 
                    subscriberId, pattern);
                
                // Clean up empty sets
                if (subs.isEmpty()) {
                    subscriptions.remove(pattern);
                }
            }
        }
        return count;
    }
    
    /**
     * Notify subscribers of a new message
     * @param message The message to notify about
     */
    private void notifySubscribers(Message message) {
        String topic = message.topic();
        if (topic == null) {
            return;
        }
        
        // Find all matching subscriptions
        for (Map.Entry<String, Set<Subscription>> entry : subscriptions.entrySet()) {
            String pattern = entry.getKey();
            
            // Check if the topic matches the pattern
            if (matchesPattern(topic, pattern)) {
                Set<Subscription> matchingSubscriptions = entry.getValue();
                
                // Notify each subscriber asynchronously
                for (Subscription subscription : matchingSubscriptions) {
                    notificationExecutor.submit(() -> {
                        try {
                            subscription.subscriber().onMessageArrived(message);
                        } catch (Exception e) {
                            logger.error("Error notifying subscriber {}: {}", 
                                subscription.subscriberId(), e.getMessage(), e);
                        }
                    });
                }
            }
        }
    }
    
    /**
     * Check if a topic matches a routing pattern
     * Uses the same wildcard semantics as MiniQ routing:
     * - '*' matches a single segment
     * - '#' matches zero or more segments
     * @param topic The topic to check
     * @param pattern The pattern to match against
     * @return true if the topic matches the pattern
     */
    private boolean matchesPattern(String topic, String pattern) {
        // Exact match
        if (pattern.equals(topic)) {
            return true;
        }
        
        // If no wildcards, must be exact match (which failed above)
        if (!pattern.contains("*") && !pattern.contains("#")) {
            return false;
        }
        
        // Convert to regex pattern
        String regexPattern = pattern
            .replace(".", "\\.")           // Escape dots
            .replace("*", "[^.]+")         // * matches single segment
            .replace("#", ".*");           // # matches zero or more segments
        
        return topic.matches(regexPattern);
    }
    
    /**
     * Shutdown the subscription manager and unregister the update hook
     */
    public void shutdown() {
        try {
            if (connection.isWrapperFor(SQLiteConnection.class)) {
                SQLiteConnection sqliteConn = connection.unwrap(SQLiteConnection.class);
                sqliteConn.removeUpdateListener(this);
                logger.info("SQLite update hook unregistered");
            }
        } catch (SQLException e) {
            logger.error("Error unregistering update hook: {}", e.getMessage(), e);
        }
        
        notificationExecutor.shutdown();
    }
}
```

### 4. Modify the MiniQ Class

Integrate the subscription manager with SQLite update hook:

```java
// Add these fields to the MiniQ class
private final SubscriptionManager subscriptionManager;

// Modify the constructor to initialize the subscription manager
public MiniQ(QConfig config) throws SQLException {
    // Existing initialization code...
    
    // Initialize subscription manager with SQLite update hook
    this.subscriptionManager = new SubscriptionManager(conn, queueName);
    
    Qinit();
}

// Note: No changes needed to putMessage() - the SQLite update hook
// is triggered automatically by SQLite after any INSERT

// Add methods to manage subscriptions
/**
 * Subscribe to messages matching a routing pattern
 * @param routingPattern The routing pattern to match (supports wildcards)
 * @param subscriber The subscriber to notify
 * @return true if subscription was added, false if it already existed
 */
public boolean subscribe(String routingPattern, Subscriber subscriber) {
    return subscriptionManager.subscribe(routingPattern, subscriber);
}

/**
 * Unsubscribe from messages matching a routing pattern
 * @param routingPattern The routing pattern to unsubscribe from
 * @param subscriberId The ID of the subscriber to unsubscribe
 * @return true if subscription was removed, false if it didn't exist
 */
public boolean unsubscribe(String routingPattern, String subscriberId) {
    return subscriptionManager.unsubscribe(routingPattern, subscriberId);
}

/**
 * Unsubscribe a subscriber from all patterns
 * @param subscriberId The ID of the subscriber to unsubscribe
 * @return The number of subscriptions removed
 */
public int unsubscribeAll(String subscriberId) {
    return subscriptionManager.unsubscribeAll(subscriberId);
}

// Modify the close method to shutdown the subscription manager
public void close() {
    try {
        if (subscriptionManager != null) {
            subscriptionManager.shutdown();
        }
        
        if (conn != null) {
            conn.close();
        }
    } catch (SQLException e) {
        logger.error("Error closing connection: {}", e.getMessage(), e);
    }
}
```

## Usage Example

Here's how clients would use the subscription system:

```java
// Create a subscriber
Subscriber mySubscriber = new Subscriber() {
    @Override
    public void onMessageArrived(Message message) {
        System.out.println("Received message: " + message.data());
        // Process the message asynchronously
    }
    
    @Override
    public String getSubscriberId() {
        return "subscriber-001";
    }
};

// Subscribe to messages with a specific topic pattern
queue.subscribe("orders.*", mySubscriber);

// Messages inserted by ANY source will trigger notifications
// - Same MiniQ instance
// - Different MiniQ instance on same database
// - External tools (sqlite3 CLI, DB browser, etc.)

// Later, when done with the subscription
queue.unsubscribe("orders.*", "subscriber-001");

// Or unsubscribe from all patterns
queue.unsubscribeAll("subscriber-001");
```

## Benefits of SQLite Update Hook Design

1. **Database-Level Notifications**: Catches ALL inserts, not just those from the application
2. **Zero Latency**: Notifications triggered immediately by SQLite, no polling delay
3. **External Process Support**: Works even when messages are inserted by other processes
4. **Asynchronous Delivery**: Subscribers are notified in separate threads
5. **Pattern-Based Subscriptions**: Uses the existing routing pattern format with wildcard support
6. **Thread Safety**: Uses concurrent collections to ensure thread safety
7. **Error Isolation**: Errors in subscriber handlers don't affect the queue or other subscribers

## Limitations and Considerations

1. **Single Hook Per Connection**: SQLite allows only one update hook per connection; the manager handles multiplexing to multiple subscribers
2. **Driver Dependency**: Requires the `org.sqlite.SQLiteConnection` interface from sqlite-jdbc
3. **Connection Scope**: The hook is registered per connection; each MiniQ instance with its own connection can have its own subscribers
4. **Performance**: Fetching message details after hook callback adds a small overhead

## Alternative Approaches (For Comparison)

### Alternative A: Application-Level Trigger

A simpler approach that triggers notifications directly from `putMessage()`:

```java
// In putMessage() method:
private Message putMessage(String data, String topic) throws SQLException {
    // Insert message...
    Message message = new Message(...);
    
    // Trigger notification directly (application-level)
    if (topic != null) {
        subscriptionManager.notifySubscribers(message);
    }
    
    return message;
}
```

**Limitations**: Only notifies for messages inserted via this specific MiniQ instance. External inserts or other MiniQ instances won't trigger notifications.

### Alternative B: Polling

Use a background thread to periodically check for new messages:

```java
// Poll for new messages every N milliseconds
ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();
poller.scheduleAtFixedRate(() -> {
    List<Message> newMessages = checkForNewMessages(lastSeenId);
    for (Message msg : newMessages) {
        notifySubscribers(msg);
    }
}, 0, 100, TimeUnit.MILLISECONDS);
```

**Limitations**: Introduces latency (up to poll interval), wastes resources checking when no messages exist, requires tracking "last seen" state.

## Testing Considerations

To test this implementation, you would need to:

1. Create mock subscribers and verify they receive notifications
2. Test that notifications are triggered for INSERTs from:
   - The same MiniQ instance
   - Direct SQL execution on the same connection
   - (If possible) External processes
3. Test pattern matching with various wildcards (`*`, `#`)
4. Test error handling in subscriber notifications
5. Test subscription management (adding/removing subscriptions)
6. Verify proper cleanup when shutting down

## Conclusion

The SQLite Update Hook design provides a robust, database-level notification system that catches all message insertions regardless of source. This is the recommended approach for MiniQ as it provides the strongest guarantees and best aligns with the expectations of a message queue system where messages may originate from multiple sources.