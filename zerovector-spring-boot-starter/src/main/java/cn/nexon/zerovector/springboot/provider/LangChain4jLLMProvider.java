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
import cn.nexon.zerovector.core.util.MD5Util;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.concurrent.TimeUnit;

public class LangChain4jLLMProvider implements LLMProvider {

    private static final Logger logger = LoggerFactory.getLogger(LangChain4jLLMProvider.class);

    private final ChatModel chatModel;

    private final Cache<String, String> summaryCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterAccess(30, TimeUnit.MINUTES)
            .build();

    public LangChain4jLLMProvider(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public LLMResponse chat(String prompt, SmartCacheStrategy.RequestType type) {
        if (type == SmartCacheStrategy.RequestType.GENERATE_SUMMARY) {
            String cacheKey = "summary_" + MD5Util.calculateMD5(prompt);
            String cached = summaryCache.getIfPresent(cacheKey);
            if (cached != null) {
                return LLMResponse.success(cached, 0, 0, 0);
            }
            LLMResponse response = execute(prompt, "生成摘要");
            if (response.success()) {
                summaryCache.put(cacheKey, response.content());
            }
            return response;
        }
        String opName = type.name().toLowerCase().replace('_', ' ');
        return execute(prompt, opName);
    }

    private LLMResponse execute(String prompt, String operationName) {
        long startTime = System.currentTimeMillis();
        try {
            String result = chatModel.chat(prompt);
            long duration = System.currentTimeMillis() - startTime;
            logger.debug("LLM原始响应: {}", result);
            return LLMResponse.success(result, duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("{}失败", operationName, e);
            return LLMResponse.failure(e.getMessage(), duration);
        }
    }
}
