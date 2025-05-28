
# Adding a Subscriber Notification Design to MiniQ

To implement a subscriber notification design for the MiniQ project, we need to create a system that allows clients to register interest in specific message topics and receive asynchronous notifications when matching messages arrive in the queue.

## Design Overview

The proposed solution consists of:

1. A `Subscriber` interface for clients to implement
2. A `SubscriptionManager` class to manage subscriptions
3. Integration with the existing `MiniQ` class
4. Asynchronous notification delivery using an executor service

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

### 3. Create a SubscriptionManager Class

Now, let's create a `SubscriptionManager` class to manage subscriptions:

```java
package miniq.core.subscription;

import miniq.core.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SubscriptionManager {
    private static final Logger logger = LoggerFactory.getLogger(SubscriptionManager.class);
    
    // Map of routing patterns to sets of subscribers
    private final Map<String, Set<Subscription>> subscriptions = new ConcurrentHashMap<>();
    
    // Executor service for asynchronous notification delivery
    private final ExecutorService notificationExecutor;
    
    public SubscriptionManager() {
        // Create a cached thread pool for notifications
        this.notificationExecutor = Executors.newCachedThreadPool();
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
    public void notifySubscribers(Message message) {
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
     * @param topic The topic to check
     * @param pattern The pattern to match against (supports wildcards)
     * @return true if the topic matches the pattern
     */
    private boolean matchesPattern(String topic, String pattern) {
        // If the pattern doesn't contain wildcards, use exact match
        if (!pattern.contains("*")) {
            return topic.equals(pattern);
        }
        
        // Convert the routing pattern to a regex pattern
        String regexPattern = pattern.replace(".", "\\.").replace("*", ".*");
        return topic.matches(regexPattern);
    }
    
    /**
     * Shutdown the notification executor
     */
    public void shutdown() {
        notificationExecutor.shutdown();
    }
}
```

### 4. Modify the MiniQ Class

Now, let's modify the `MiniQ` class to integrate the subscription system:

```java
// Add these fields to the MiniQ class
private final SubscriptionManager subscriptionManager;

// Modify the constructor to initialize the subscription manager
public MiniQ(QConfig config) throws SQLException {
    // Existing initialization code...
    
    this.subscriptionManager = new SubscriptionManager();
    
    Qinit();
}

// Modify the putMessage method to notify subscribers
private Message putMessage(String data, String topic) throws SQLException {
    // Existing code to insert the message...
    
    // Create the message object
    Message message = new Message(messageId, topic, data, MessageStatus.READY, inTime, null, null);
    
    // Notify subscribers asynchronously
    if (topic != null) {
        subscriptionManager.notifySubscribers(message);
    }
    
    return message;
}

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

// Later, when done with the subscription
queue.unsubscribe("orders.*", "subscriber-001");

// Or unsubscribe from all patterns
queue.unsubscribeAll("subscriber-001");
```

## Benefits of This Design

1. **Asynchronous Notifications**: Subscribers are notified in separate threads, preventing blocking of the main queue operations.
2. **Pattern-Based Subscriptions**: Uses the existing routing pattern format with wildcard support.
3. **Thread Safety**: Uses concurrent collections to ensure thread safety.
4. **Error Isolation**: Errors in subscriber handlers don't affect the queue or other subscribers.
5. **Clean Integration**: Minimal changes to the existing MiniQ class.

## Testing Considerations

To test this implementation, you would need to:

1. Create mock subscribers
2. Verify they receive notifications when matching messages are added
3. Test pattern matching with various wildcards
4. Test error handling in subscriber notifications
5. Test subscription management (adding/removing subscriptions)

## Conclusion

This design provides a flexible, asynchronous notification system that integrates well with the existing MiniQ codebase. It allows clients to subscribe to specific message topics and receive notifications when matching messages arrive in the queue, without blocking the main queue operations.