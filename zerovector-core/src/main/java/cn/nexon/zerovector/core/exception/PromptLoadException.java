package cn.nexon.zerovector.core.exception;

public class PromptLoadException extends RuntimeException {
    private final String promptName;
    private final String errorCode;
    private final ErrorLevel errorLevel;

    public PromptLoadException(String promptName, String errorCode, ErrorLevel errorLevel, String message, Throwable cause) {
        super(String.format("[%s][%s] Failed to load prompt '%s': %s", errorCode, errorLevel, promptName, message), cause);
        this.promptName = promptName;
        this.errorCode = errorCode;
        this.errorLevel = errorLevel != null ? errorLevel : ErrorLevel.ERROR;
    }

    public PromptLoadException(String promptName, String errorCode, String message, Throwable cause) {
        this(promptName, errorCode, ErrorLevel.ERROR, message, cause);
    }

    public PromptLoadException(String promptName, String errorCode, ErrorLevel errorLevel, String message) {
        this(promptName, errorCode, errorLevel, message, null);
    }

    public PromptLoadException(String promptName, String errorCode, String message) {
        this(promptName, errorCode, ErrorLevel.ERROR, message, null);
    }

    public String getPromptName() {
        return promptName;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public ErrorLevel getErrorLevel() {
        return errorLevel;
    }

    public static final String ERROR_CODE_FILE_NOT_FOUND = "PROMPT_001";
    public static final String ERROR_CODE_PARSE_FAILED = "PROMPT_002";
    public static final String ERROR_CODE_INVALID_FORMAT = "PROMPT_003";
    public static final String ERROR_CODE_TEMPLATE_MISSING = "PROMPT_004";
    public static final String ERROR_CODE_LOAD_FAILED = "PROMPT_005";
}
