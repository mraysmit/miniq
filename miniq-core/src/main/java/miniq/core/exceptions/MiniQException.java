package miniq.core.exceptions;

/**
 * Base exception class for all MiniQ-related exceptions.
 * This provides a common base for all custom exceptions in the MiniQ system.
 */
public class MiniQException extends Exception {
    
    private final String errorCode;
    private final String context;
    
    /**
     * Creates a new MiniQException with a message.
     * 
     * @param message The error message
     */
    public MiniQException(String message) {
        super(message);
        this.errorCode = "MINIQ_ERROR";
        this.context = null;
    }
    
    /**
     * Creates a new MiniQException with a message and cause.
     * 
     * @param message The error message
     * @param cause The underlying cause
     */
    public MiniQException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = "MINIQ_ERROR";
        this.context = null;
    }
    
    /**
     * Creates a new MiniQException with a message, error code, and context.
     * 
     * @param message The error message
     * @param errorCode The specific error code
     * @param context Additional context information
     */
    public MiniQException(String message, String errorCode, String context) {
        super(message);
        this.errorCode = errorCode;
        this.context = context;
    }
    
    /**
     * Creates a new MiniQException with a message, cause, error code, and context.
     * 
     * @param message The error message
     * @param cause The underlying cause
     * @param errorCode The specific error code
     * @param context Additional context information
     */
    public MiniQException(String message, Throwable cause, String errorCode, String context) {
        super(message, cause);
        this.errorCode = errorCode;
        this.context = context;
    }
    
    /**
     * Gets the error code associated with this exception.
     * 
     * @return The error code
     */
    public String getErrorCode() {
        return errorCode;
    }
    
    /**
     * Gets the context information associated with this exception.
     * 
     * @return The context information, or null if none was provided
     */
    public String getContext() {
        return context;
    }
    
    /**
     * Returns a detailed string representation of this exception.
     * 
     * @return A formatted string with error code, context, and message
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getClass().getSimpleName());
        sb.append(" [").append(errorCode).append("]");
        if (context != null) {
            sb.append(" (").append(context).append(")");
        }
        sb.append(": ").append(getMessage());
        return sb.toString();
    }
}
