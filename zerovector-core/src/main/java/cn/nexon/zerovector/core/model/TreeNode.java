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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 语义树节点记录
 * [CRITICAL] 包含 keyEntities 和 keywords，用于解决 LLM 认知盲区
 */
public record TreeNode(
        String id,
        String name,
        String description,
        NodeType type,
        List<String> childrenIds,
        List<String> chunkIds,
        // [KEY] 增强字段：用于关键词匹配和 Prompt 强化
        List<String> keyEntities,    // 核心实体：如 "项目A", "张三"
        List<String> keywords,       // 关键词：如 "部署", "报错"
        List<String> exampleQuestions // 示例问题：帮助 LLM 理解语境
) {
    @JsonCreator
    public TreeNode(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("type") NodeType type,
            @JsonProperty("childrenIds") List<String> childrenIds,
            @JsonProperty("chunkIds") List<String> chunkIds,
            @JsonProperty("keyEntities") List<String> keyEntities,
            @JsonProperty("keywords") List<String> keywords,
            @JsonProperty("exampleQuestions") List<String> exampleQuestions
    ) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.type = type;
        this.childrenIds = childrenIds != null ? childrenIds : List.of();
        this.chunkIds = chunkIds != null ? chunkIds : List.of();
        this.keyEntities = keyEntities != null ? keyEntities : List.of();
        this.keywords = keywords != null ? keywords : List.of();
        this.exampleQuestions = exampleQuestions != null ? exampleQuestions : List.of();
    }
    
    @JsonIgnore
    public boolean isLeaf() {
        return type == NodeType.LEAF;
    }
    
    @JsonIgnore
    public boolean hasChildren() {
        return !childrenIds.isEmpty();
    }
    
    @JsonIgnore
    public boolean hasChunks() {
        return !chunkIds.isEmpty();
    }
}