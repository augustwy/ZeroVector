/*
 * Copyright 2025 nexonlab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cn.nexon.zerovector.springboot.provider;

import cn.nexon.zerovector.core.ai.LLMProvider;
import cn.nexon.zerovector.core.ai.LLMResponse;
import cn.nexon.zerovector.core.ai.SmartCacheStrategy;
import cn.nexon.zerovector.springboot.autoconfigure.ZeroVectorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;

/**
 * Spring AI LLM 适配器。
 *
 * <p>按 heavy / light / fast 三档模型路由请求；缓存统一交给
 * {@code CachedLLMProvider}，这里不再维护第二层缓存。
 */
public class SpringAiLLMProvider implements LLMProvider {

    private static final Logger logger = LoggerFactory.getLogger(SpringAiLLMProvider.class);

    private final ChatClient heavyClient;
    private final ChatClient lightClient;
    private final ChatClient fastClient;

    public SpringAiLLMProvider(ChatModel chatModel, ZeroVectorProperties.Model model) {
        double temperature = model.temperature() >= 0 ? model.temperature() : 0.7;
        this.heavyClient = client(chatModel, model.heavy(), temperature, model.maxTokens());
        this.lightClient = client(chatModel, model.light(), temperature, model.maxTokens());
        this.fastClient = client(chatModel, model.fast(), temperature, model.maxTokens());
    }

    private static ChatClient client(ChatModel chatModel, String modelName, double temperature, int maxTokens) {
        ChatOptions.Builder options = ChatOptions.builder()
                .model(modelName)
                .temperature(temperature);
        if (maxTokens > 0) {
            options.maxTokens(maxTokens);
        }
        return ChatClient.builder(chatModel).defaultOptions(options).build();
    }

    @Override
    public LLMResponse chat(String prompt, SmartCacheStrategy.RequestType type) {
        ChatClient client = switch (type) {
            case COMPREHEND_CHUNK, CLUSTER_DOCUMENTS, GENERATE_SUMMARY -> heavyClient;
            case EXTRACT_KEYWORDS, EXTRACT_ENTITIES, GENERATE_EXAMPLE_QUESTIONS -> lightClient;
            case DECIDE_NAVIGATION, EXTRACT_QUERY_KEYWORDS, ANSWER_QUESTION -> fastClient;
        };
        return execute(client, prompt, type);
    }

    private LLMResponse execute(ChatClient client, String prompt, SmartCacheStrategy.RequestType type) {
        long startTime = System.currentTimeMillis();
        try {
            ChatResponse response = client.prompt().user(prompt).call().chatResponse();
            String content = response.getResult().getOutput().getText();
            logger.debug("LLM 原始响应 [{}]: {}", type, content);
            Integer promptTokens = response.getMetadata() != null && response.getMetadata().getUsage() != null
                    ? response.getMetadata().getUsage().getPromptTokens() : null;
            Integer completionTokens = response.getMetadata() != null && response.getMetadata().getUsage() != null
                    ? response.getMetadata().getUsage().getCompletionTokens() : null;
            return LLMResponse.success(content,
                    promptTokens != null ? promptTokens : 0,
                    completionTokens != null ? completionTokens : 0,
                    System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            logger.error("LLM 调用失败 [{}]", type, e);
            return LLMResponse.failure(e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }
}
