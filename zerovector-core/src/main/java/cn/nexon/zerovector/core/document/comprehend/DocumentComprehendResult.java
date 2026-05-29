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

package cn.nexon.zerovector.core.document.comprehend;

import cn.nexon.zerovector.core.ai.LLMUsageStats;
import cn.nexon.zerovector.core.model.KeywordDefinition;

import java.util.List;

/**
 * 文档理解结果
 * 包含文档摘要、关键词定义、实体、示例问题以及 LLM 调用统计数据
 *
 * @param summary 文档摘要
 * @param keywordDefinitions 关键词定义列表
 * @param entities 实体列表
 * @param exampleQuestions 示例问题列表
 * @param llmUsageStats LLM 调用统计数据
 */
public record DocumentComprehendResult(
    String summary,
    List<KeywordDefinition> keywordDefinitions,
    List<String> entities,
    List<String> exampleQuestions,
    LLMUsageStats llmUsageStats
) {
    public DocumentComprehendResult {
        if (keywordDefinitions == null) {
            keywordDefinitions = List.of();
        }
        if (entities == null) {
            entities = List.of();
        }
        if (exampleQuestions == null) {
            exampleQuestions = List.of();
        }
        if (llmUsageStats == null) {
            llmUsageStats = new LLMUsageStats();
        }
    }

    public static DocumentComprehendResult of(String summary, List<KeywordDefinition> keywordDefinitions) {
        return new DocumentComprehendResult(summary, keywordDefinitions, List.of(), List.of(), new LLMUsageStats());
    }

    public static DocumentComprehendResult of(String summary, List<KeywordDefinition> keywordDefinitions, List<String> entities, List<String> exampleQuestions) {
        return new DocumentComprehendResult(summary, keywordDefinitions, entities, exampleQuestions, new LLMUsageStats());
    }

    public static DocumentComprehendResult of(String summary, List<KeywordDefinition> keywordDefinitions, List<String> entities, List<String> exampleQuestions, LLMUsageStats llmUsageStats) {
        return new DocumentComprehendResult(summary, keywordDefinitions, entities, exampleQuestions, llmUsageStats);
    }
}
