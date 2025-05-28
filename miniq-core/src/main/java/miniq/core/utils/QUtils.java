package miniq.core.utils;




import miniq.core.model.MessageStatus;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

public class QUtils {
    // Using System.out for logging instead of slf4j to fix build issues

    public static String MillistoDateTime(long millis) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new java.util.Date(millis));
    }

    public static String MillistoEpochDateTime(long millis) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
        return formatter.format(Instant.ofEpochMilli(millis));
    }

    public static String getStatusDoneFailedString() {
        return getMessageStatusString(List.of(MessageStatus.DONE, MessageStatus.FAILED));
    }

    public static String getMessageStatusString(List<MessageStatus> ms) {
        return ms.stream().map(MessageStatus::getValue).map(String::valueOf).collect(Collectors.joining(","));
    }

    // Util functions
    public static <T> T resultSetGetValue(ResultSet rs, String fieldName, Class<T> type) {
        try {
            // doesn't work
            return rs.getObject(fieldName, type);
        } catch (SQLException e) {
            // TODO: Handle exception
            System.err.println("Error in resultSetGetValue: " + e.getMessage());
        }
        return null;
    }

    public static Long resultSetGetLong(ResultSet rs, String fieldName) {
        try {
            final Long x =  rs.getLong(fieldName);
            if (rs.wasNull()) return null;
            return x;
        } catch (SQLException e) {
            // TODO: Handle exception
            System.err.println("Error in resultSetGetLong: " + e.getMessage());
        }
        return null;
    }



}