package cn.nexon.zerovector.core.exception;

public class StorageException extends RuntimeException {
    private final String storagePath;
    private final String operation;
    private final String errorCode;
    private final ErrorLevel errorLevel;
    
    public StorageException(String storagePath, String operation, String errorCode, ErrorLevel errorLevel, String message, Throwable cause) {
        super(String.format("[%s][%s] Storage error at %s during %s: %s", errorCode, errorLevel, storagePath, operation, message), cause);
        this.storagePath = storagePath;
        this.operation = operation;
        this.errorCode = errorCode;
        this.errorLevel = errorLevel != null ? errorLevel : ErrorLevel.ERROR;
    }
    
    public StorageException(String storagePath, String operation, String errorCode, ErrorLevel errorLevel, String message) {
        this(storagePath, operation, errorCode, errorLevel, message, null);
    }
    
    public StorageException(String storagePath, String operation, String errorCode, String message, Throwable cause) {
        this(storagePath, operation, errorCode, ErrorLevel.ERROR, message, cause);
    }
    
    public StorageException(String storagePath, String operation, String errorCode, String message) {
        this(storagePath, operation, errorCode, ErrorLevel.ERROR, message, null);
    }
    
    public StorageException(String storagePath, String operation, Throwable cause) {
        this(storagePath, operation, ERROR_CODE_GENERAL, ErrorLevel.ERROR, "General storage error", cause);
    }
    
    public StorageException(String storagePath, String operation, String message) {
        this(storagePath, operation, ERROR_CODE_GENERAL, ErrorLevel.ERROR, message, null);
    }
    
    public String getStoragePath() {
        return storagePath;
    }
    
    public String getOperation() {
        return operation;
    }
    
    public String getErrorCode() {
        return errorCode;
    }
    
    public ErrorLevel getErrorLevel() {
        return errorLevel;
    }
    
    public static final String ERROR_CODE_GENERAL = "STORAGE_001";
    public static final String ERROR_CODE_FILE_NOT_FOUND = "STORAGE_002";
    public static final String ERROR_CODE_IO_ERROR = "STORAGE_003";
    public static final String ERROR_CODE_PERMISSION_DENIED = "STORAGE_004";
    public static final String ERROR_CODE_DISK_FULL = "STORAGE_005";
    public static final String ERROR_CODE_FILE_CORRUPT = "STORAGE_006";
    public static final String ERROR_CODE_INDEX_CORRUPT = "STORAGE_007";
    public static final String ERROR_CODE_COMPACT_FAILED = "STORAGE_008";
    public static final String ERROR_CODE_BUFFER_OVERFLOW = "STORAGE_009";
} 
