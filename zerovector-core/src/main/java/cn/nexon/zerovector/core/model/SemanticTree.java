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

import java.util.Map;

/**
 * 语义树快照（根节点 + 节点表 + 分片元数据表）。
 */
public record SemanticTree(
        TreeNode rootNode,
        Map<String, TreeNode> nodes,
        Map<String, DocumentChunk> chunks
) {
    public SemanticTree {
        if (nodes == null) {
            nodes = Map.of();
        }
        if (chunks == null) {
            chunks = Map.of();
        }
    }

    public TreeNode getNode(String id) {
        return nodes.get(id);
    }

    public DocumentChunk getChunk(String id) {
        return chunks.get(id);
    }

    public static SemanticTree empty() {
        return new SemanticTree(null, Map.of(), Map.of());
    }
}
