package cn.nexon.zerovector.core.exception;

public class CacheException extends RuntimeException {
    private final String cacheKey;
    private final String operation;
    private final String errorCode;
    private final ErrorLevel errorLevel;

    public CacheException(String cacheKey, String operation, String errorCode, ErrorLevel errorLevel, String message, Throwable cause) {
        super(String.format("[%s][%s] Cache operation '%s' failed for key '%s': %s", errorCode, errorLevel, operation, cacheKey, message), cause);
        this.cacheKey = cacheKey;
        this.operation = operation;
        this.errorCode = errorCode;
        this.errorLevel = errorLevel != null ? errorLevel : ErrorLevel.ERROR;
    }

    public CacheException(String cacheKey, String operation, String errorCode, ErrorLevel errorLevel, String message) {
        this(cacheKey, operation, errorCode, errorLevel, message, null);
    }

    public CacheException(String cacheKey, String operation, String errorCode, String message, Throwable cause) {
        this(cacheKey, operation, errorCode, ErrorLevel.ERROR, message, cause);
    }

    public CacheException(String cacheKey, String operation, String errorCode, String message) {
        this(cacheKey, operation, errorCode, ErrorLevel.ERROR, message, null);
    }

    public String getCacheKey() {
        return cacheKey;
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

    public static final String ERROR_CODE_KEY_NOT_FOUND = "CACHE_001";
    public static final String ERROR_CODE_PUT_FAILED = "CACHE_002";
    public static final String ERROR_CODE_GET_FAILED = "CACHE_003";
    public static final String ERROR_CODE_EVICT_FAILED = "CACHE_004";
    public static final String ERROR_CODE_CLEAR_FAILED = "CACHE_005";
    public static final String ERROR_CODE_CAPACITY_EXCEEDED = "CACHE_006";
    public static final String ERROR_CODE_SERIALIZATION_FAILED = "CACHE_007";
    public static final String ERROR_CODE_DESERIALIZATION_FAILED = "CACHE_008";
    public static final String ERROR_CODE_INVALID_KEY = "CACHE_009";
    public static final String ERROR_CODE_CACHE_FULL = "CACHE_010";
}
