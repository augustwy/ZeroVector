package cn.nexon.zerovector.core.exception;

import java.util.List;

public class NavigationException extends RuntimeException {
    private final String query;
    private final String currentNodeId;
    private final String errorCode;
    private final ErrorLevel errorLevel;
    private final List<String> navigationPath;
    
    public NavigationException(String query, String currentNodeId, String errorCode, String message, Throwable cause) {
        super(String.format("[%s][%s] Navigation failed for query '%s' at node '%s': %s", errorCode, ErrorLevel.ERROR, query, currentNodeId, message), cause);
        this.query = query;
        this.currentNodeId = currentNodeId;
        this.errorCode = errorCode;
        this.errorLevel = ErrorLevel.ERROR;
        this.navigationPath = null;
    }
    
    public NavigationException(String query, String currentNodeId, String errorCode, String message) {
        super(String.format("[%s][%s] Navigation failed for query '%s' at node '%s': %s", errorCode, ErrorLevel.ERROR, query, currentNodeId, message));
        this.query = query;
        this.currentNodeId = currentNodeId;
        this.errorCode = errorCode;
        this.errorLevel = ErrorLevel.ERROR;
        this.navigationPath = null;
    }
    
    public NavigationException(String query, String currentNodeId, String errorCode, ErrorLevel errorLevel, String message, List<String> navigationPath) {
        super(String.format("[%s][%s] Navigation failed for query '%s' at node '%s': %s", errorCode, errorLevel, query, currentNodeId, message));
        this.query = query;
        this.currentNodeId = currentNodeId;
        this.errorCode = errorCode;
        this.errorLevel = errorLevel;
        this.navigationPath = navigationPath;
    }
    
    public NavigationException(String query, String currentNodeId, String errorCode, ErrorLevel errorLevel, String message, List<String> navigationPath, Throwable cause) {
        super(String.format("[%s][%s] Navigation failed for query '%s' at node '%s': %s", errorCode, errorLevel, query, currentNodeId, message), cause);
        this.query = query;
        this.currentNodeId = currentNodeId;
        this.errorCode = errorCode;
        this.errorLevel = errorLevel;
        this.navigationPath = navigationPath;
    }
    
    public String getQuery() {
        return query;
    }
    
    public String getCurrentNodeId() {
        return currentNodeId;
    }
    
    public String getErrorCode() {
        return errorCode;
    }
    
    public ErrorLevel getErrorLevel() {
        return errorLevel;
    }
    
    public List<String> getNavigationPath() {
        return navigationPath;
    }
    
    public enum ErrorLevel {
        WARNING,
        ERROR,
        CRITICAL
    }
    
    public static final String ERROR_CODE_TREE_NOT_INITIALIZED = "NAV_001";
    public static final String ERROR_CODE_NODE_NOT_FOUND = "NAV_002";
    public static final String ERROR_CODE_NO_NAVIGATOR = "NAV_003";
    public static final String ERROR_CODE_LLM_DECISION_FAILED = "NAV_004";
    public static final String ERROR_CODE_MAX_STEPS_EXCEEDED = "NAV_005";
    public static final String ERROR_CODE_NO_RESULTS = "NAV_006";
    public static final String ERROR_CODE_INVALID_QUERY = "NAV_007";
}
