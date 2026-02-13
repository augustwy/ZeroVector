package cn.nexon.zerovector.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * 文档块记录
 */
public record DocumentChunk(
        String id,
        @JsonInclude(JsonInclude.Include.NON_NULL) String content,
        String summary,
        @JsonInclude(JsonInclude.Include.NON_NULL) String filePath,  // 文件路径字段，用于指向实际文件位置
        @JsonInclude(JsonInclude.Include.NON_NULL) String md5,      // MD5字段，用于文件内容校验
        Map<String, Object> metadata
) {
    @JsonCreator
    public DocumentChunk(
            @JsonProperty("id") String id,
            @JsonProperty("content") String content,
            @JsonProperty("summary") String summary,
            @JsonProperty("filePath") String filePath,
            @JsonProperty("md5") String md5,
            @JsonProperty("metadata") Map<String, Object> metadata
    ) {
        this.id = id;
        this.content = content;
        this.summary = summary;
        this.filePath = filePath;  // 文件路径可以为null，表示内容直接存储在content中
        this.md5 = md5;            // MD5值可以为null，表示未计算或不需要校验
        this.metadata = metadata != null ? metadata : Map.of();
    }

    /**
     * 创建一个基于内容的文档块（兼容旧版本）
     */
    public static DocumentChunk withContent(String id, String content, String summary, Map<String, Object> metadata) {
        return new DocumentChunk(id, content, summary, null, null, metadata);
    }

    /**
     * 创建一个基于内容的文档块（带MD5）
     */
    public static DocumentChunk withContent(String id, String content, String summary, String md5, Map<String, Object> metadata) {
        return new DocumentChunk(id, content, summary, null, md5, metadata);
    }

    /**
     * 创建一个基于文件路径的文档块
     */
    public static DocumentChunk withFilePath(String id, String summary, String filePath, Map<String, Object> metadata) {
        return new DocumentChunk(id, null, summary, filePath, null, metadata);
    }

    /**
     * 创建一个基于文件路径的文档块（带MD5）
     */
    public static DocumentChunk withFilePath(String id, String summary, String filePath, String md5, Map<String, Object> metadata) {
        return new DocumentChunk(id, null, summary, filePath, md5, metadata);
    }
    
    /**
     * 检查是否是基于文件路径的文档块
     */
    @JsonIgnore
    public boolean isFilePathBased() {
        return filePath != null && !filePath.isEmpty();
    }
    
    /**
     * 获取实际内容
     * 如果是基于文件路径的文档块，返回null
     * 如果是基于内容的文档块，返回content字段
     */
    @JsonIgnore
    public String getActualContent() {
        return isFilePathBased() ? null : content;
    }
}