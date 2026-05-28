package cn.nexon.zerovector.core.ai;

/**
 * LLM 调用抽象。整个系统通过这一个方法调用大模型，缓存策略通过 RequestType 区分。
 */
public interface LLMProvider {

    /**
     * 调用 LLM。
     *
     * @param prompt 提示词
     * @param type   请求类型，用于缓存策略区分
     * @return LLM 响应（内容 + token 消耗 + 耗时）
     */
    LLMResponse chat(String prompt, SmartCacheStrategy.RequestType type);
}
