package miniq.core;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.uuid.Generators;

import miniq.config.QConfig;
import miniq.core.model.Message;
import miniq.core.model.MessageStatus;


import static com.fasterxml.uuid.Generators.*;

import static miniq.core.utils.QUtils.*;

/*
TODO: push a notification to connected clients using a pubsub callback mechanism
TODO: push API to push a message to the subscribers via Webhook with resubscribe
TODO: add support for message routing with a xx.xx format
TODO: add support for message priority
TODO: add proper error handling
 */


public class MiniQ {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(MiniQ.class);

    // Cache for frequently used routing patterns
    private final Map<String, String> patternQueryCache = new ConcurrentHashMap<>();

    private final Connection conn;
    private final String dbName;
    private final String queueName;
    private final Integer queueMaxSize;
    private final Integer sqliteCacheSizeBytes;
    private final boolean isCreateDb;
    private final boolean isCreateQueue;

    public MiniQ(QConfig config) throws SQLException {
        // Set the configuration
        this.dbName = config.dbName();
        this.queueName = config.queueName();
        this.queueMaxSize = config.queueMaxSize();
        this.sqliteCacheSizeBytes = config.sqliteCacheSizeBytes();
        this.isCreateDb = config.createDb();
        this.isCreateQueue = config.createQueue();

        // If dbName is not null and not ":memory:", prepend the "db\" folder path
        String dbPath = this.dbName;
        if (dbPath != null && !dbPath.equals(":memory:")) {
            // Create the db directory if it doesn't exist
            java.io.File dbDir = new java.io.File("db");
            if (!dbDir.exists()) {
                dbDir.mkdirs();
            }
            dbPath = "db\\" + dbPath;
        }

        this.conn = DriverManager.getConnection("jdbc:sqlite:" + (dbPath != null ? dbPath : ":memory:"));

        Qinit();
    }


    /***************************************************************
     INSERT methods
     ****************************************************************/
    public Message put(String data) throws SQLException {
        return putMessage(data, null);
    }

    public Message put(String data, String route) throws SQLException {
        return putMessage(data, route);
    }

    // The put method is used to put a message into the queue
    private Message putMessage(String data, String routingKey) throws SQLException {
        final String messageId = String.valueOf(timeBasedEpochGenerator().generate());
        final int status = MessageStatus.READY.getValue();
        final long inTime = System.currentTimeMillis();

        // Check if we need to manage the transaction
        boolean wasAutoCommit = conn.getAutoCommit();
        boolean needToCommit = wasAutoCommit;

        if (wasAutoCommit) {
            // Begin transaction only if autoCommit was true
            conn.setAutoCommit(false);
        }

        try {
            // Insert into main queue table
            String sql = "INSERT INTO %s (message_id, topic, data, status, in_time) VALUES (?, ?, ?, ?, ?)".formatted(this.queueName);
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, messageId);
                pstmt.setString(2, routingKey);
                pstmt.setString(3, data);
                pstmt.setInt(4, status);
                pstmt.setLong(5, inTime);
                pstmt.executeUpdate();
            }

            // Parse and store routing segments if routing key is provided
            if (routingKey != null && !routingKey.isEmpty()) {
                String[] segments = routingKey.split("\\.");
                for (int i = 0; i < segments.length; i++) {
                    storeRoutingSegment(messageId, i, segments[i]);
                }
            }

            if (needToCommit) {
                // Only commit if we started the transaction
                conn.commit();
            }
        } catch (SQLException e) {
            if (needToCommit) {
                // Only rollback if we started the transaction
                conn.rollback();
            }
            System.err.println("Error putting message: " + e.getMessage());
            throw e; // Re-throw the exception to propagate it to the caller
        } finally {
            if (wasAutoCommit) {
                // Only reset autoCommit if it was true before
                conn.setAutoCommit(true);
            }
        }
        return new Message(messageId, routingKey, data, MessageStatus.READY, inTime, null, null);
    }

    // Helper method to store a routing segment
    private void storeRoutingSegment(String messageId, int position, String value) throws SQLException {
        String sql = "INSERT INTO routing_segments (message_id, segment_position, segment_value) VALUES (?, ?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, messageId);
            pstmt.setInt(2, position);
            pstmt.setString(3, value);
            pstmt.executeUpdate();
        }
    }


    /***************************************************************
     SELECT methods
     ****************************************************************/

    // The pop method is used to pop a message from the queue using a database transaction
    // select the first message in the queue with status = READY
    // lock the message to avoid another process from getting it
    public Message pop() throws SQLException {
        return popWithRoutingPattern(null);
    }

    // The popWithRoutingPattern method is used to pop a message from the queue with a specific routing pattern
    // The routing pattern is in the format "xx.xx" where each part can be a specific value or a wildcard "*" or "#".
    public Message popWithRoutingPattern(String pattern) throws SQLException {
        if (pattern == null || pattern.isEmpty()) {
            return pop(); // Default behavior
        }

        if (!pattern.contains("*") && !pattern.contains("#")) {
            // Exact match
            return popWithExactRoutingKey(pattern);
        }

        // Check if we need to manage the transaction
        boolean wasAutoCommit = conn.getAutoCommit();
        boolean needToCommit = wasAutoCommit;

        if (wasAutoCommit) {
            // Begin transaction only if autoCommit was true
            conn.setAutoCommit(false);
        }

        try {
            // Parse pattern into segments
            String[] patternSegments = pattern.split("\\.");

            // Build SQL query based on pattern segments
            StringBuilder sql = new StringBuilder();
            sql.append("SELECT m.* FROM ").append(this.queueName).append(" m WHERE m.status = ? ");

            // Add pattern matching conditions
            if (patternSegments.length > 0) {
                sql.append("AND m.message_id IN (");
                sql.append(buildPatternMatchingQuery(patternSegments));
                sql.append(") ");
            }

            sql.append("ORDER BY m.in_time LIMIT 1");

            // Execute query
            PreparedStatement ps1 = conn.prepareStatement(sql.toString());
            ps1.setInt(1, MessageStatus.READY.getValue());

            ResultSet rs1 = ps1.executeQuery();

            if (rs1.next()) {
                // Lock the message
                PreparedStatement ps2 = conn.prepareStatement("UPDATE " + this.queueName + " SET status = ?, lock_time = ? WHERE message_id = ? AND status = ?");
                ps2.setInt(1, MessageStatus.LOCKED.getValue());
                ps2.setLong(2, System.currentTimeMillis());
                ps2.setString(3, rs1.getString("message_id"));
                ps2.setInt(4, MessageStatus.READY.getValue());
                ps2.executeUpdate();

                if (needToCommit) {
                    // Only commit if we started the transaction
                    conn.commit();
                }

                // Return the selected message
                return new Message(
                        rs1.getString("message_id"),
                        rs1.getString("topic"),
                        rs1.getString("data"),
                        MessageStatus.LOCKED,
                        rs1.getLong("in_time"),
                        System.currentTimeMillis(),
                        resultSetGetLong(rs1, "done_time")
                );
            }

            if (needToCommit) {
                // Only commit if we started the transaction
                conn.commit();
            }

        } catch (SQLException e) {
            if (needToCommit) {
                // Only rollback if we started the transaction
                conn.rollback();
            }
            throw e;
        } finally {
            if (wasAutoCommit) {
                // Only reset autoCommit if it was true before
                conn.setAutoCommit(true);
            }
        }

        return null;
    }

    // Helper method to build pattern matching query
    private String buildPatternMatchingQuery(String[] patternSegments) {
        String patternKey = String.join(".", patternSegments);

        // Check cache first
        if (patternQueryCache.containsKey(patternKey)) {
            return patternQueryCache.get(patternKey);
        }

        StringBuilder subquery = new StringBuilder();
        subquery.append("SELECT DISTINCT rs1.message_id FROM routing_segments rs1 ");

        // Track if we have a multi-segment wildcard
        boolean hasMultiSegmentWildcard = false;
        int lastSegmentIndex = -1;

        for (int i = 0; i < patternSegments.length; i++) {
            String segment = patternSegments[i];

            if (segment.equals("#")) {
                // Multi-segment wildcard
                hasMultiSegmentWildcard = true;
                lastSegmentIndex = i;
                break;
            } else if (i > 0) {
                // Join with previous segments
                subquery.append("INNER JOIN routing_segments rs").append(i + 1).append(" ON rs1.message_id = rs").append(i + 1).append(".message_id ");
            }
        }

        subquery.append("WHERE ");

        // Add conditions for each segment
        for (int i = 0; i < patternSegments.length; i++) {
            if (hasMultiSegmentWildcard && i >= lastSegmentIndex) {
                break;
            }

            String segment = patternSegments[i];

            if (i > 0) {
                subquery.append("AND ");
            }

            subquery.append("rs").append(i + 1).append(".segment_position = ").append(i).append(" ");

            if (segment.equals("*")) {
                // Single-segment wildcard - no additional condition needed
            } else if (!segment.equals("#")) {
                // Exact match
                subquery.append("AND rs").append(i + 1).append(".segment_value = '").append(segment).append("' ");
            }
        }

        // Handle multi-segment wildcard if present
        if (hasMultiSegmentWildcard && lastSegmentIndex < patternSegments.length - 1) {
            // There are segments after the # wildcard
            for (int i = lastSegmentIndex + 1; i < patternSegments.length; i++) {
                String segment = patternSegments[i];

                if (segment.equals("*") || segment.equals("#")) {
                    continue; // Skip wildcards after #
                }

                // Add condition to match this segment at any position > lastSegmentIndex
                subquery.append("AND EXISTS (SELECT 1 FROM routing_segments rs_tail WHERE rs1.message_id = rs_tail.message_id ");
                subquery.append("AND rs_tail.segment_value = '").append(segment).append("' ");

                // If this is the last segment in the pattern, it must be the last segment in the routing key
                if (i == patternSegments.length - 1) {
                    subquery.append("AND NOT EXISTS (SELECT 1 FROM routing_segments rs_next WHERE rs1.message_id = rs_next.message_id ");
                    subquery.append("AND rs_next.segment_position > rs_tail.segment_position) ");
                }

                subquery.append(") ");
            }
        } else if (!hasMultiSegmentWildcard) {
            // If no # wildcard, ensure the routing key has exactly the same number of segments
            subquery.append("AND NOT EXISTS (SELECT 1 FROM routing_segments rs_extra WHERE rs1.message_id = rs_extra.message_id ");
            subquery.append("AND rs_extra.segment_position >= ").append(patternSegments.length).append(") ");
        }

        String result = subquery.toString();

        // Cache the result
        patternQueryCache.put(patternKey, result);

        return result;
    }

    // Helper method for exact routing key match
    private Message popWithExactRoutingKey(String routingKey) throws SQLException {
        // Check if we need to manage the transaction
        boolean wasAutoCommit = conn.getAutoCommit();
        boolean needToCommit = wasAutoCommit;

        if (wasAutoCommit) {
            // Begin transaction only if autoCommit was true
            conn.setAutoCommit(false);
        }

        try {
            // Select the first message with the exact routing key
            PreparedStatement ps1 = conn.prepareStatement("SELECT * FROM " + this.queueName + " WHERE status = ? AND topic = ? ORDER BY in_time LIMIT 1");
            ps1.setInt(1, MessageStatus.READY.getValue());
            ps1.setString(2, routingKey);

            ResultSet rs1 = ps1.executeQuery();

            if (rs1.next()) {
                // Lock the message
                PreparedStatement ps2 = conn.prepareStatement("UPDATE " + this.queueName + " SET status = ?, lock_time = ? WHERE message_id = ? AND status = ?");
                ps2.setInt(1, MessageStatus.LOCKED.getValue());
                ps2.setLong(2, System.currentTimeMillis());
                ps2.setString(3, rs1.getString("message_id"));
                ps2.setInt(4, MessageStatus.READY.getValue());
                ps2.executeUpdate();

                if (needToCommit) {
                    // Only commit if we started the transaction
                    conn.commit();
                }

                // Return the selected message
                return new Message(
                        rs1.getString("message_id"),
                        rs1.getString("topic"),
                        rs1.getString("data"),
                        MessageStatus.LOCKED,
                        rs1.getLong("in_time"),
                        System.currentTimeMillis(),
                        resultSetGetLong(rs1, "done_time")
                );
            }

            if (needToCommit) {
                // Only commit if we started the transaction
                conn.commit();
            }
        } catch (SQLException e) {
            if (needToCommit) {
                // Only rollback if we started the transaction
                conn.rollback();
            }
            throw e;
        } finally {
            if (wasAutoCommit) {
                // Only reset autoCommit if it was true before
                conn.setAutoCommit(true);
            }
        }

        return null;
    }

    // Note that the SQLite RETURNING (UPDATE + SELECT) clause is not supported in all versions of SQLite, and not all JDBC drivers support it.
    public Message popReturning() throws SQLException {
        return popReturningWithRoutingPattern(null);
    }

    // The popReturningWithRoutingPattern method is used to pop a message from the queue with a specific routing pattern
    // The routing pattern is in the format "xx.xx" where each part can be a specific value or a wildcard "*".
    public Message popReturningWithRoutingPattern(String routingPattern) throws SQLException {
        String sql;

        if (routingPattern == null || routingPattern.isEmpty()) {
            sql = "UPDATE %s SET status = ?, lock_time = ? WHERE rowid = (SELECT rowid FROM %s WHERE status = ? ORDER BY in_time LIMIT 1) RETURNING *".formatted(this.queueName, this.queueName);
        } else if (!routingPattern.contains(".") && !routingPattern.contains("*")) {
            sql = "UPDATE %s SET status = ?, lock_time = ? WHERE rowid = (SELECT rowid FROM %s WHERE status = ? AND topic = ? ORDER BY in_time LIMIT 1) RETURNING *".formatted(this.queueName, this.queueName);
        } else {
            String likePattern = routingPattern.replace("*", "%");
            sql = "UPDATE %s SET status = ?, lock_time = ? WHERE rowid = (SELECT rowid FROM %s WHERE status = ? AND topic LIKE ? ORDER BY in_time LIMIT 1) RETURNING *".formatted(this.queueName, this.queueName);
        }

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, MessageStatus.LOCKED.getValue());
            pstmt.setLong(2, System.currentTimeMillis());
            pstmt.setInt(3, MessageStatus.READY.getValue());

            if (routingPattern != null && !routingPattern.isEmpty()) {
                if (!routingPattern.contains(".") && !routingPattern.contains("*")) {
                    pstmt.setString(4, routingPattern);
                } else {
                    String likePattern = routingPattern.replace("*", "%");
                    pstmt.setString(4, likePattern);
                }
            }

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return new Message(
                            rs.getString("message_id"),
                            rs.getString("topic"),
                            rs.getString("data"),
                            MessageStatus.LOCKED,
                            rs.getLong("in_time"),
                            System.currentTimeMillis(),
                            rs.getLong("done_time")
                    );
                }
                else {
                    return null;
                }
            } catch (SQLException e) {
                System.err.println("Error in popReturningWithRoutingPattern: " + e.getMessage());
                return null;
            }
        }
    }

    // The peek method is used to peek at the first message in the queue without removing it.
    public Message peek() throws SQLException {
        return peekWithRoutingPattern(null);
    }

    // The peekWithRoutingPattern method is used to peek at the first message in the queue with a specific routing pattern
    // The routing pattern is in the format "xx.xx" where each part can be a specific value or a wildcard "*".
    public Message peekWithRoutingPattern(String routingPattern) throws SQLException {
        PreparedStatement pstmt;

        if (routingPattern == null || routingPattern.isEmpty()) {
            final var sql = "SELECT * FROM %s WHERE status = ? ORDER BY in_time LIMIT 1".formatted(this.queueName);
            pstmt = conn.prepareStatement(sql);
            pstmt.setInt(1, MessageStatus.READY.getValue());
        } else if (!routingPattern.contains(".") && !routingPattern.contains("*")) {
            final var sql = "SELECT * FROM %s WHERE status = ? AND topic = ? ORDER BY in_time LIMIT 1".formatted(this.queueName);
            pstmt = conn.prepareStatement(sql);
            pstmt.setInt(1, MessageStatus.READY.getValue());
            pstmt.setString(2, routingPattern);
        } else {
            String likePattern = routingPattern.replace("*", "%");
            final var sql = "SELECT * FROM %s WHERE status = ? AND topic LIKE ? ORDER BY in_time LIMIT 1".formatted(this.queueName);
            pstmt = conn.prepareStatement(sql);
            pstmt.setInt(1, MessageStatus.READY.getValue());
            pstmt.setString(2, likePattern);
        }

        try (pstmt) {
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return new Message(
                            rs.getString("message_id"),
                            rs.getString("topic"),
                            rs.getString("data"),
                            MessageStatus.values()[rs.getInt("status")],
                            resultSetGetLong(rs,"in_time"),
                            resultSetGetLong(rs,"lock_time"),
                            resultSetGetLong(rs,"done_time")
                    );
                }
                else {
                    return null;
                }
            } catch (SQLException e) {
                System.err.println("Error in peekWithRoutingPattern: " + e.getMessage());
                return null;
            }
        }
    }

    // The get method is used to get a message by its message ID.
    public Message get(String messageId) throws SQLException {
        String sql = "SELECT * FROM %s WHERE message_id = ?".formatted(this.queueName);

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, messageId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return new Message(
                            rs.getString("message_id"),
                            rs.getString("topic"),
                            rs.getString("data"),
                            MessageStatus.values()[rs.getInt("status")],
                            rs.getLong("in_time"),
                            resultSetGetLong(rs, "lock_time"),
                            resultSetGetLong(rs, "done_time")
                    );
                } else {
                    return null;
                }
            }
        }
    }

    // The getByRoutingPattern method is used to get messages by routing pattern.
    // The routing pattern is in the format "xx.xx" where each part can be a specific value or a wildcard "*".
    // For example, "orders.created" would match only messages with that exact topic,
    // while "orders.*" would match any message with a topic that starts with "orders.".
    public List<Message> getByRoutingPattern(String routingPattern, MessageStatus status) throws SQLException {
        // If the routing pattern doesn't contain a dot or wildcard, use exact match
        if (!routingPattern.contains(".") && !routingPattern.contains("*")) {
            String sql = "SELECT * FROM %s WHERE topic = ? AND status = ?".formatted(this.queueName);
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, routingPattern);
                pstmt.setInt(2, status.getValue());
                return getMessages(pstmt);
            }
        }

        // Convert the routing pattern to a SQL LIKE pattern
        String likePattern = routingPattern.replace("*", "%");
        String sql = "SELECT * FROM %s WHERE topic LIKE ? AND status = ?".formatted(this.queueName);

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, likePattern);
            pstmt.setInt(2, status.getValue());
            return getMessages(pstmt);
        }
    }

    // Get all messages with a specific routing pattern regardless of status
    public List<Message> getAllByRoutingPattern(String routingPattern) throws SQLException {
        // If the routing pattern doesn't contain a dot or wildcard, use exact match
        if (!routingPattern.contains(".") && !routingPattern.contains("*")) {
            String sql = "SELECT * FROM %s WHERE topic = ?".formatted(this.queueName);
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, routingPattern);
                return getMessages(pstmt);
            }
        }

        // Convert the routing pattern to a SQL LIKE pattern
        String likePattern = routingPattern.replace("*", "%");
        String sql = "SELECT * FROM %s WHERE topic LIKE ?".formatted(this.queueName);

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, likePattern);
            return getMessages(pstmt);
        }
    }


    /***************************************************************
     get List<Messages> methods
     ****************************************************************/

    // The listFailed method is used to list with specified status.
    public List<Message> getWithStatus(MessageStatus status) throws SQLException {
        String sql = "SELECT * FROM " + this.queueName + " WHERE status = ?";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, status.getValue());
            return getMessages(pstmt);
        }
    }
    // The getLocked method is used to list all locked messages that have been locked for more than a certain threshold in seconds.
    public List<Message> getLocked(long thresholdMillis) throws SQLException {
        long timeValue = System.currentTimeMillis() - thresholdMillis;
        String sql = "SELECT * FROM %s WHERE status = ? AND lock_time < ?".formatted(this.queueName);

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, MessageStatus.LOCKED.getValue());
            pstmt.setLong(2, timeValue);
            return getMessages(pstmt);
        }
    }

    private List<Message> getMessages(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            List<Message> messages = new ArrayList<>();
            while (rs.next()) {
                messages.add(new Message(
                        rs.getString("message_id"),
                        rs.getString("topic"),
                        rs.getString("data"),
                        MessageStatus.values()[rs.getInt("status")],
                        rs.getLong("in_time"),
                        resultSetGetLong(rs, "lock_time"),
                        resultSetGetLong(rs, "done_time")
                ));
            }
            return messages;
        }
    }


    /***************************************************************
     COUNT methods
     ****************************************************************/

    // get COUNT of all messages in the queue with status not DONE or FAILED
    public int qsizeDoneFailed() {
        return qsizeIn(List.of(MessageStatus.DONE, MessageStatus.FAILED));
    }

    // get COUNT of all READY messages in the queue
    public boolean empty() {
        return (qsizeIn(List.of(MessageStatus.READY)) == 0);
    }

    // get COUNT of all READY messages in the queue relative to the max size
    public boolean full() {
        if (this.queueMaxSize == null) { return false; }
        return (qsizeIn(List.of(MessageStatus.READY)) >= this.queueMaxSize);
    }

    // get COUNT of all messages in the queue with status IN (?)
    public int qsizeIn(List<MessageStatus> messageStatuses) {
        String sql = String.format("SELECT COUNT(*) as cnt FROM %s WHERE status IN (%s)", 
                this.queueName, 
                miniq.core.utils.QUtils.getMessageStatusString(messageStatuses));

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            return executeReturnInt(pstmt);
        } catch (SQLException e) {
            System.err.println("Error in qsizeIn: " + e.getMessage());
        }
        return 0;
    }

    // get COUNT of all messages in the queue with status NOT DONE or FAILED
    private int qsizeNotIn(List<MessageStatus> messageStatuses) {
        String sql = String.format("SELECT COUNT(*) as cnt FROM %s WHERE status NOT IN (?)", this.queueName);
        var stList = miniq.core.utils.QUtils.getStatusDoneFailedString();

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, stList);
            return executeReturnInt(pstmt);
        } catch (SQLException e) {
            logger.error("Error in qsizeNotIn: {}", e.getMessage(), e);
        }
        return 0;
    }

    private int executeReturnInt(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            logger.error("Error in executeReturnInt: {}", e.getMessage(), e);
        }
        return 0;
    }

    /***************************************************************
     UPDATE methods
     ****************************************************************/

    // The setDone method is used to mark a message as done by its message ID.
    public int setDone(String messageId) throws SQLException {
        return setStatus(messageId, MessageStatus.DONE);
    }

    // The setFailed method is used to mark a message as failed by its message ID.
    public int setFailed(String messageId) throws SQLException {
        return setStatus(messageId, MessageStatus.FAILED);
    }

    // The done method is used to mark a message as done by its message ID and status
    public int setStatus(String messageId, MessageStatus status) throws SQLException {
        String sql = "UPDATE %s SET status = ?, done_time = ? WHERE message_id = ?".formatted(this.queueName);

        // Check if we need to manage the transaction
        boolean wasAutoCommit = conn.getAutoCommit();
        boolean needToCommit = wasAutoCommit;

        if (wasAutoCommit) {
            // Begin transaction only if autoCommit was true
            conn.setAutoCommit(false);
        }

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, status.getValue());
            pstmt.setLong(2, System.currentTimeMillis());
            pstmt.setString(3, messageId);
            var cnt = pstmt.executeUpdate();

            if (needToCommit) {
                // Only commit if we started the transaction
                conn.commit();
            }

            return cnt;
        } catch (SQLException e) {
            if (needToCommit) {
                // Only rollback if we started the transaction
                conn.rollback();
            }
            logger.error("Error in setStatus: {}", e.getMessage(), e);
            throw e; // Re-throw the exception to propagate it to the caller
        } finally {
            if (wasAutoCommit) {
                // Only reset autoCommit if it was true before
                conn.setAutoCommit(true);
            }
        }
    }

    public Optional<Integer> retry(String messageId) {
        String sql = String.format("UPDATE %s SET status = ?, done_time = NULL WHERE message_id = ?", this.queueName);

        try {
            // Check if we need to manage the transaction
            boolean wasAutoCommit = conn.getAutoCommit();
            boolean needToCommit = wasAutoCommit;

            if (wasAutoCommit) {
                // Begin transaction only if autoCommit was true
                conn.setAutoCommit(false);
            }

            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setInt(1, MessageStatus.READY.getValue());
                pstmt.setString(2, messageId);
                int affectedRows = pstmt.executeUpdate();

                if (needToCommit) {
                    // Only commit if we started the transaction
                    conn.commit();
                }

                return affectedRows > 0 ? Optional.of(affectedRows) : Optional.empty();
            } catch (SQLException e) {
                if (needToCommit) {
                    // Only rollback if we started the transaction
                    conn.rollback();
                }
                logger.error("Error in retry: {}", e.getMessage(), e);
                throw e;
            } finally {
                if (wasAutoCommit) {
                    // Only reset autoCommit if it was true before
                    conn.setAutoCommit(true);
                }
            }
        } catch (SQLException e) {
            logger.error("Error in retry: {}", e.getMessage(), e);
        }
        return Optional.empty();
    }


    /***************************************************************
       DELETE methods
     ****************************************************************/

    // delete ONLY "DONE" and/or "FAILED" messages
    public void prune(boolean includeFailed) {
        String sql;

        try {
            // Check if we need to manage the transaction
            boolean wasAutoCommit = conn.getAutoCommit();
            boolean needToCommit = wasAutoCommit;

            if (wasAutoCommit) {
                // Begin transaction only if autoCommit was true
                conn.setAutoCommit(false);
            }

            try {
                if (includeFailed) {
                    sql = String.format("DELETE FROM %s WHERE status IN (?, ?)", this.queueName);
                    try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                        pstmt.setInt(1, MessageStatus.DONE.getValue());
                        pstmt.setInt(2, MessageStatus.FAILED.getValue());
                        pstmt.executeUpdate();
                    }
                } else {
                    sql = String.format("DELETE FROM %s WHERE status = ?", this.queueName);
                    try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                        pstmt.setInt(1, MessageStatus.DONE.getValue());
                        pstmt.executeUpdate();
                    }
                }

                if (needToCommit) {
                    // Only commit if we started the transaction
                    conn.commit();
                }
            } catch (SQLException e) {
                if (needToCommit) {
                    // Only rollback if we started the transaction
                    conn.rollback();
                }
                logger.error("Error in prune: {}", e.getMessage(), e);
                throw e;
            } finally {
                if (wasAutoCommit) {
                    // Only reset autoCommit if it was true before
                    conn.setAutoCommit(true);
                }
            }
        } catch (SQLException e) {
            logger.error("Error in prune: {}", e.getMessage(), e);
        }
    }

    // delete all messages
    public void purge() {
        String sql = String.format("DELETE FROM %s ", this.queueName);

        try {
            // Check if we need to manage the transaction
            boolean wasAutoCommit = conn.getAutoCommit();
            boolean needToCommit = wasAutoCommit;

            if (wasAutoCommit) {
                // Begin transaction only if autoCommit was true
                conn.setAutoCommit(false);
            }

            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.executeUpdate();

                if (needToCommit) {
                    // Only commit if we started the transaction
                    conn.commit();
                }
            } catch (SQLException e) {
                if (needToCommit) {
                    // Only rollback if we started the transaction
                    conn.rollback();
                }
                logger.error("Error in purge: {}", e.getMessage(), e);
                throw e;
            } finally {
                if (wasAutoCommit) {
                    // Only reset autoCommit if it was true before
                    conn.setAutoCommit(true);
                }
            }
        } catch (SQLException e) {
            logger.error("Error in purge: {}", e.getMessage(), e);
        }
    }

    // vacuum the database
    public void vacuum() {
        try {
            // Check if we need to manage the transaction
            boolean wasAutoCommit = conn.getAutoCommit();
            boolean needToCommit = wasAutoCommit;

            if (wasAutoCommit) {
                // Begin transaction only if autoCommit was true
                conn.setAutoCommit(false);
            }

            try (Statement stmt = conn.createStatement()) {
                stmt.execute("VACUUM");

                if (needToCommit) {
                    // Only commit if we started the transaction
                    conn.commit();
                }
            } catch (SQLException e) {
                if (needToCommit) {
                    // Only rollback if we started the transaction
                    conn.rollback();
                }
                logger.error("Error in vacuum: {}", e.getMessage(), e);
                throw e;
            } finally {
                if (wasAutoCommit) {
                    // Only reset autoCommit if it was true before
                    conn.setAutoCommit(true);
                }
            }
        } catch (SQLException e) {
            logger.error("Error in vacuum: {}", e.getMessage(), e);
        }
    }

    // close the connection
    public void close() {
        try {
            if (conn != null) {
                conn.close();
            }
        } catch (SQLException e) {
            logger.error("Error closing connection: {}", e.getMessage(), e);
        }
    }

    @Override
    public String toString() {
        return "MiniQ{" +
                "conn=" + conn +
                ", queueName='" + queueName + '\'' +
                ", maxSize=" + queueMaxSize +
                '}';
    }

    private void Qinit() throws SQLException {
        // set pragmas
        final var stmt = conn.createStatement();
        stmt.execute(String.format("PRAGMA cache_size = %d;", this.sqliteCacheSizeBytes));
        stmt.execute("PRAGMA journal_mode = WAL;");
        stmt.execute("PRAGMA temp_store = MEMORY;");
        stmt.execute("PRAGMA synchronous = NORMAL;");

        this.conn.setAutoCommit(false);

        // optionally remove the Queue table
        if (isCreateDb) {
            final var stmt1 = conn.createStatement();
            final var sql1 = "DROP TABLE IF EXISTS " + this.queueName + "";
            try {
                var r = stmt1.executeUpdate(sql1);
                conn.commit();
            } catch (SQLException e) {
                logger.error("Error dropping table in Qinit: {}", e.getMessage(), e);
            }
        }

        // Create the Queue table
        final var stmt2 = conn.createStatement();
        final var sql2 = "CREATE TABLE IF NOT EXISTS " + this.queueName + " " +
                "(message_id TEXT NOT NULL, " +
                " topic TEXT, " +
                " data TEXT NOT NULL, " +
                " status INTEGER NOT NULL, " +
                " in_time INTEGER NOT NULL, " +
                " lock_time INTEGER, " +
                " done_time INTEGER)";
        try {
            stmt2.executeUpdate(sql2);
            conn.commit();
        } catch (SQLException e) {
            logger.error("Error creating table in Qinit: {}", e.getMessage(), e);
        }

        // Create the indexes
        final var stmt3 = conn.createStatement();
        final var sql3 = "CREATE INDEX IF NOT EXISTS idx_queue_message_id ON " + this.queueName + "(message_id)";
        try {
            stmt3.executeUpdate(sql3);
            conn.commit();
        } catch (SQLException e) {
            logger.error("Error creating message_id index in Qinit: {}", e.getMessage(), e);
        }

        final var stmt4 = conn.createStatement();
        final var sql4 = "CREATE INDEX IF NOT EXISTS idx_queue_status ON " + this.queueName + "(status)";
        try {
            stmt4.executeUpdate(sql4);
            conn.commit();
        } catch (SQLException e) {
            logger.error("Error creating status index in Qinit: {}", e.getMessage(), e);
        }

        // Create the routing_segments table
        final var stmtSegments = conn.createStatement();
        final var sqlSegments = "CREATE TABLE IF NOT EXISTS routing_segments " +
                "(message_id TEXT NOT NULL, " +
                " segment_position INTEGER NOT NULL, " +
                " segment_value TEXT NOT NULL, " +
                " PRIMARY KEY (message_id, segment_position), " +
                " FOREIGN KEY (message_id) REFERENCES " + this.queueName + "(message_id) ON DELETE CASCADE)";
        try {
            stmtSegments.executeUpdate(sqlSegments);
            conn.commit();
        } catch (SQLException e) {
            logger.error("Error creating routing_segments table in Qinit: {}", e.getMessage(), e);
        }

        // Create indexes for efficient lookups
        final var stmtSegmentIndex1 = conn.createStatement();
        final var sqlSegmentIndex1 = "CREATE INDEX IF NOT EXISTS idx_routing_segments_value ON routing_segments(segment_value)";
        try {
            stmtSegmentIndex1.executeUpdate(sqlSegmentIndex1);
            conn.commit();
        } catch (SQLException e) {
            logger.error("Error creating segment_value index in Qinit: {}", e.getMessage(), e);
        }

        final var stmtSegmentIndex2 = conn.createStatement();
        final var sqlSegmentIndex2 = "CREATE INDEX IF NOT EXISTS idx_routing_segments_position ON routing_segments(segment_position)";
        try {
            stmtSegmentIndex2.executeUpdate(sqlSegmentIndex2);
            conn.commit();
        } catch (SQLException e) {
            logger.error("Error creating segment_position index in Qinit: {}", e.getMessage(), e);
        }

        // Create the trigger to set messages as failed
        var tr_queue_cleaner_control = "CREATE TRIGGER IF NOT EXISTS tr_queue_cleaner_control_%s ".formatted(this.queueName) +
                "BEFORE INSERT " +
                "ON %s ".formatted(this.queueName) +
                "WHEN (SELECT COUNT(*) FROM %s WHERE status = 1) >= %d ".formatted(this.queueName, this.queueMaxSize) +
                "BEGIN " +
                "SELECT RAISE (ABORT,'Queue overflow maximum reached: %d'); ".formatted(this.queueMaxSize) +
                "END;";

        // Create the trigger to manage queue overflow if overflow value is set
        // If overflow value is not set don't create the trigger
        var tr_overflow_control = "CREATE TRIGGER IF NOT EXISTS overflow_control_%s ".formatted(this.queueName) +
                "BEFORE INSERT " +
                "ON %s ".formatted(this.queueName) +
                "WHEN (SELECT COUNT(*) FROM %s WHERE status = 1) >= %d ".formatted(this.queueName, this.queueMaxSize) +
                "BEGIN " +
                "SELECT RAISE (ABORT,'Queue overflow maximum reached: %d'); ".formatted(this.queueMaxSize) +
                "END;";

        // Execute the trigger creation SQL if queueMaxSize is not null
        if (this.queueMaxSize != null) {
            try {
                final var stmt5 = conn.createStatement();
                stmt5.executeUpdate(tr_queue_cleaner_control);
                conn.commit();

                final var stmt6 = conn.createStatement();
                stmt6.executeUpdate(tr_overflow_control);
                conn.commit();
            } catch (SQLException e) {
                logger.error("Error creating triggers in Qinit: {}", e.getMessage(), e);
            }
        }


        // Disabled SQLiteFunction to fix build issues
        // final var func = new SQLiteFunction();
        // func.createFunction(conn);

        // Reset autoCommit mode
        try {
            conn.setAutoCommit(true);
        } catch (SQLException e) {
            logger.error("Error resetting autoCommit in Qinit: {}", e.getMessage(), e);
        }
    }



    public String getSQLiteVersion() throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        return meta.getDatabaseProductVersion();
    }




}
