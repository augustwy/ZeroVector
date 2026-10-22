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

package cn.nexon.zerovector.springboot;

import cn.nexon.zerovector.core.ai.LLMUsageStats;
import cn.nexon.zerovector.core.model.DocumentChunk;
import cn.nexon.zerovector.core.model.NavigationPath;

import java.util.List;

/**
 * 搜索结果，封装文档列表与正文读取。
 */
public interface SearchResult {
    String query();

    List<DocumentChunk> documents();

    String reasoning();

    List<NavigationPath> path();

    LLMUsageStats llmUsageStats();

    /** 将所有文档正文拼接为纯文本；读取失败时回退到摘要。 */
    String toDocumentText();

    /** 获取单个分片的正文。 */
    String getChunkContent(DocumentChunk chunk);
}
