package cn.nexon.zerovector.core.ai;

public record LLMResponse(
    String content,
    int inputTokens,
    int outputTokens,
    int totalTokens,
    long duration,
    boolean success,
    String errorMessage
) {
    public static LLMResponse success(String content, int inputTokens, int outputTokens, long duration) {
        return new LLMResponse(
            content,
            inputTokens,
            outputTokens,
            inputTokens + outputTokens,
            duration,
            true,
            null
        );
    }

    public static LLMResponse success(String content, long duration) {
        return new LLMResponse(
            content,
            0,
            0,
            0,
            duration,
            true,
            null
        );
    }

    public static LLMResponse failure(String errorMessage, long duration) {
        return new LLMResponse(
            "",
            0,
            0,
            0,
            duration,
            false,
            errorMessage
        );
    }

    public static LLMResponse failure(String errorMessage) {
        return failure(errorMessage, 0);
    }
}
