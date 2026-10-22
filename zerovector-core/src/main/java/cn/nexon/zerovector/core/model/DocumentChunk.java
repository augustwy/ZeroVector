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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 章节级文档分片。
 *
 * <p>正文有两种来源：
 * <ul>
 *   <li>{@code content} 非空：小文档/接口直传，正文存放在 {@code ChunkStorage}（mmap）；</li>
 *   <li>{@code source} 非空：大文件章节，语义树只保存源文件引用和字节区间，
 *       回答时才按 offset/length 读取原文。</li>
 * </ul>
 * 语义树持久化时使用 {@link #metadataOnly()}，不会把 {@code content} 写进树文件。
 */
public record DocumentChunk(
        String id,
        @JsonInclude(JsonInclude.Include.NON_NULL) String content,
        String summary,
        @JsonInclude(JsonInclude.Include.NON_NULL) String md5,
        Map<String, Object> metadata,
        @JsonInclude(JsonInclude.Include.NON_NULL) SourceRef source,
        long sourceOffset,
        int sourceLength,
        @JsonInclude(JsonInclude.Include.NON_NULL) String sourceCharset
) {
    @JsonCreator
    public DocumentChunk(
            @JsonProperty("id") String id,
            @JsonProperty("content") String content,
            @JsonProperty("summary") String summary,
            @JsonProperty("md5") String md5,
            @JsonProperty("metadata") Map<String, Object> metadata,
            @JsonProperty("source") SourceRef source,
            @JsonProperty("sourceOffset") long sourceOffset,
            @JsonProperty("sourceLength") int sourceLength,
            @JsonProperty("sourceCharset") String sourceCharset
    ) {
        this.id = id;
        this.content = content;
        this.summary = summary != null ? summary : "";
        this.md5 = md5;
        this.metadata = metadata != null
                ? java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(metadata))
                : Map.of();
        this.source = source;
        this.sourceOffset = Math.max(0, sourceOffset);
        this.sourceLength = Math.max(0, sourceLength);
        this.sourceCharset = sourceCharset;
    }

    /** 携带内联正文的章节（写入 ChunkStorage）。 */
    public static DocumentChunk withContent(String id, String content, String summary, Map<String, Object> metadata) {
        return new DocumentChunk(id, content, summary, null, metadata, null, 0, 0, null);
    }

    public static DocumentChunk withContent(String id, String content, String summary, String md5,
                                            Map<String, Object> metadata) {
        return new DocumentChunk(id, content, summary, md5, metadata, null, 0, 0, null);
    }

    /** 指向源文件章节的分片；语义树中只保存这个形式。 */
    public static DocumentChunk withSource(String id, String summary, String md5, Map<String, Object> metadata,
                                           SourceRef source, long sourceOffset, int sourceLength,
                                           String sourceCharset) {
        return new DocumentChunk(id, null, summary, md5, metadata, source,
                sourceOffset, sourceLength, sourceCharset);
    }

    /** 去掉正文，保留摘要、MD5、源文件区间等元数据。 */
    public DocumentChunk metadataOnly() {
        if (content == null) {
            return this;
        }
        return new DocumentChunk(id, null, summary, md5, metadata, source,
                sourceOffset, sourceLength, sourceCharset);
    }

    public static DocumentChunk metadataOnly(String id, String summary, String md5, Map<String, Object> metadata) {
        return new DocumentChunk(id, null, summary, md5, metadata, null, 0, 0, null);
    }

    @JsonIgnore
    public boolean hasContent() {
        return content != null && !content.isEmpty();
    }

    @JsonIgnore
    public boolean hasSource() {
        return source != null && sourceLength > 0;
    }
}
