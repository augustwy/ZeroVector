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
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LangChain4j LLM 适配器。
 */
public class LangChain4jLLMProvider implements LLMProvider {

    private static final Logger logger = LoggerFactory.getLogger(LangChain4jLLMProvider.class);

    private final ChatModel chatModel;

    public LangChain4jLLMProvider(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public LLMResponse chat(String prompt, SmartCacheStrategy.RequestType type) {
        long startTime = System.currentTimeMillis();
        try {
            String result = chatModel.chat(prompt);
            logger.debug("LLM 原始响应 [{}]: {}", type, result);
            return LLMResponse.success(result, System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            logger.error("LLM 调用失败 [{}]", type, e);
            return LLMResponse.failure(e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }
}
