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

import cn.nexon.zerovector.core.util.JsonUtils;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
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

    /** 原子保存：先写临时文件再替换。 */
    public void saveToFile(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path tempPath = Paths.get(filePath + ".tmp");
        Files.writeString(tempPath, JsonUtils.toJson(this));
        try {
            Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static SemanticTree loadFromFile(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            SemanticTree tree = JsonUtils.parseJson(Files.readString(path), SemanticTree.class);
            if (tree == null) {
                throw new IOException("Empty semantic tree file: " + filePath);
            }
            return tree;
        } catch (RuntimeException e) {
            throw new IOException("Failed to deserialize semantic tree: " + filePath, e);
        }
    }

    public static SemanticTree empty() {
        return new SemanticTree(null, Map.of(), Map.of());
    }
}
