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

package cn.nexon.zerovector.core.model;

import java.util.Locale;
import java.util.Objects;

/**
 * 关键词定义。
 *
 * <p>只有 {@code keyword} 是必需字段：LLM 偶尔会省略 definition/context，
 * 此时不应让整篇文档解析失败，缺失字段按空串处理。
 */
public record KeywordDefinition(
        String keyword,
        String definition,
        String context,
        String documentId,
        int frequency
) {
    public KeywordDefinition {
        if (keyword == null || keyword.trim().isEmpty()) {
            throw new IllegalArgumentException("Keyword cannot be null or empty");
        }
        keyword = keyword.trim();
        definition = definition == null ? "" : definition.trim();
        context = context == null ? "" : context.trim();
        documentId = documentId == null ? "" : documentId.trim();
        if (frequency <= 0) {
            frequency = 1;
        }
    }

    public static KeywordDefinition of(String keyword, String definition, String context, String documentId) {
        return new KeywordDefinition(keyword, definition, context, documentId, 1);
    }

    public static KeywordDefinition of(String keyword, String definition, String context, String documentId,
                                       int frequency) {
        return new KeywordDefinition(keyword, definition, context, documentId, frequency);
    }

    public String normalizedKeyword() {
        return keyword.toLowerCase(Locale.ROOT).trim();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        KeywordDefinition that = (KeywordDefinition) o;
        return Objects.equals(normalizedKeyword(), that.normalizedKeyword())
                && Objects.equals(documentId, that.documentId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(normalizedKeyword(), documentId);
    }
}
