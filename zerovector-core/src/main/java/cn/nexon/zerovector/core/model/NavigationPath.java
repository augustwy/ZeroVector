package cn.nexon.zerovector.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 导航路径记录
 */
public record NavigationPath(
        String query,
        List<String> visitedNodes,
        String reasoning,
        List<DocumentChunk> documents
) {
    @JsonCreator
    public NavigationPath(
            @JsonProperty("query") String query,
            @JsonProperty("visitedNodes") List<String> visitedNodes,
            @JsonProperty("reasoning") String reasoning,
            @JsonProperty("documents") List<DocumentChunk> documents
    ) {
        this.query = query;
        this.visitedNodes = visitedNodes != null ? visitedNodes : List.of();
        this.reasoning = reasoning != null ? reasoning : "";
        this.documents = documents != null ? documents : List.of();
    }
    
    // 兼容旧构造函数
    public NavigationPath(String query, List<String> visitedNodes, String reasoning) {
        this(query, visitedNodes, reasoning, List.of());
    }
}