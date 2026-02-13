package cn.nexon.zerovector.core.exception;

public class StorageException extends RuntimeException {
    private final String storagePath;
    private final String operation;
    
    public StorageException(String storagePath, String operation, Throwable cause) {
        super(String.format("Storage error at %s during %s", storagePath, operation), cause);
        this.storagePath = storagePath;
        this.operation = operation;
    }
    
    public StorageException(String storagePath, String operation, String message) {
        super(String.format("Storage error at %s during %s: %s", storagePath, operation, message));
        this.storagePath = storagePath;
        this.operation = operation;
    }
    
    public String getStoragePath() {
        return storagePath;
    }
    
    public String getOperation() {
        return operation;
    }
}
