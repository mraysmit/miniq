# MiniQ

[![Java](https://img.shields.io/badge/Java-23-orange.svg)](https://openjdk.java.net/projects/jdk/23/)
[![Maven](https://img.shields.io/badge/Maven-3.8+-blue.svg)](https://maven.apache.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

**Version:** 1.0
**Date:** 2024-04-02
**Author:** Mark Andrew Ray-Smith Cityline Ltd

MiniQ is a lightweight, embedded message queue system built on SQLite. It provides a simple, reliable way to implement message queuing in Java applications without the need for external message brokers.

## Features

- **Embedded**: Runs within your Java application, no separate server needed
- **SQLite-based**: Uses SQLite for storage, providing durability and reliability
- **FIFO Queue**: Messages are processed in first-in, first-out order
- **Message Routing**: Support for topic-based message routing with wildcard patterns
- **Transaction Support**: Ensures message delivery reliability
- **Message Status Tracking**: Track message status (READY, LOCKED, DONE, FAILED)
- **Configurable**: Customize queue size, database location, and more
- **Lightweight**: Minimal dependencies, small footprint

## Installation

Add the following dependencies to your Maven `pom.xml`:

```xml
<dependencies>
    <dependency>
        <groupId>com.github.mraysmit</groupId>
        <artifactId>miniq</artifactId>
        <version>1.0-SNAPSHOT</version>
    </dependency>
</dependencies>
```

## Quick Start

```java
// Create a queue configuration
QConfig config = new QConfig.Builder()
    .DbName("myqueue")           // Creates myqueue.db
    .QueueName("messages")       // Table name for the queue
    .QueueMaxSize(1000)          // Maximum queue size
    .CreateDb(true)              // Create the database if it doesn't exist
    .CreateQueue(true)           // Create the queue table if it doesn't exist
    .build();

// Initialize the queue
MiniQ queue = new MiniQ(config);

// Put a message in the queue
queue.put("Hello, world!");

// Put a message with a topic
queue.put("New order created", "orders.created");

// Pop a message from the queue
Message message = queue.pop();
if (message != null) {
    // Process the message
    System.out.println("Message: " + message.data());
    
    // Mark the message as done
    queue.setDone(message.messageId());
}

// Close the queue when done
queue.close();
```

## Message Routing

MiniQ supports topic-based message routing with wildcard patterns:

```java
// Put messages with different topics
queue.put("Order #1001 created", "orders.created");
queue.put("Order #1001 updated", "orders.updated");
queue.put("User #42 created", "users.created");

// Get messages with a specific topic
Message message = queue.popWithRoutingPattern("orders.created");

// Get messages matching a pattern (using wildcards)
List<Message> orderMessages = queue.getByRoutingPattern("orders.*", MessageStatus.READY);

// Peek at messages without removing them
Message nextUserMessage = queue.peekWithRoutingPattern("users.*");
```

## Configuration Options

The `QConfig` builder supports the following options:

- `DbName(String)`: The name of the SQLite database file (`.db` extension added automatically)
- `QueueName(String)`: The name of the queue table in the database
- `QueueMaxSize(Integer)`: Maximum number of messages in the queue (defaults to `Integer.MAX_VALUE`)
- `SqliteCacheSizeBytes(Integer)`: SQLite cache size in bytes (defaults to 256000)
- `CreateDb(Boolean)`: Whether to create the database if it doesn't exist
- `CreateQueue(Boolean)`: Whether to create the queue table if it doesn't exist

## Queue Management

```java
// Check if the queue is empty
boolean isEmpty = queue.empty();

// Check if the queue is full
boolean isFull = queue.full();

// Get messages with a specific status
List<Message> failedMessages = queue.getWithStatus(MessageStatus.FAILED);

// Retry a failed message
queue.retry(failedMessage.messageId());

// Remove completed messages
queue.prune(false);  // Remove only DONE messages
queue.prune(true);   // Remove both DONE and FAILED messages

// Clear the entire queue
queue.purge();

// Optimize the database
queue.vacuum();
```

## Message Statuses

Messages in MiniQ can have the following statuses:

- `READY`: Message is ready to be processed
- `LOCKED`: Message is being processed
- `DONE`: Message has been successfully processed
- `FAILED`: Message processing has failed

## Performance Considerations

- MiniQ uses SQLite's WAL (Write-Ahead Logging) mode for better concurrency
- The queue is optimized for FIFO operations
- For high-throughput applications, consider adjusting the SQLite cache size

## License

[Add license information here]

## Future Enhancements

Planned features for future releases:
- Push notifications to connected clients using a pubsub callback mechanism
- Webhook support for pushing messages to subscribers
- Message priority support
- Enhanced error handling
