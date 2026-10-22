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
import cn.nexon.zerovector.core.model.NavigationResult;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

record SearchResultImpl(
        String query,
        NavigationResult navigationResult,
        Function<DocumentChunk, String> contentLoader
) implements SearchResult {

    SearchResultImpl {
        Objects.requireNonNull(navigationResult, "navigationResult");
    }

    @Override
    public List<DocumentChunk> documents() {
        return navigationResult.documents();
    }

    @Override
    public String reasoning() {
        return navigationResult.reasoning();
    }

    @Override
    public List<NavigationPath> path() {
        return navigationResult.path();
    }

    @Override
    public LLMUsageStats llmUsageStats() {
        return navigationResult.llmUsageStats();
    }

    @Override
    public String toDocumentText() {
        List<DocumentChunk> docs = documents();
        if (docs == null || docs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            sb.append("[").append(i + 1).append("] ")
                    .append(getChunkContent(docs.get(i)))
                    .append("\n\n");
        }
        return sb.toString().trim();
    }

    @Override
    public String getChunkContent(DocumentChunk chunk) {
        if (chunk == null) {
            return "";
        }
        if (chunk.hasContent()) {
            return chunk.content();
        }
        try {
            String content = contentLoader.apply(chunk);
            if (content != null && !content.isEmpty()) {
                return content;
            }
        } catch (RuntimeException e) {
            // 读取失败时回退到摘要，由调用方决定是否提示用户
        }
        return chunk.summary() != null ? chunk.summary() : "";
    }
}
