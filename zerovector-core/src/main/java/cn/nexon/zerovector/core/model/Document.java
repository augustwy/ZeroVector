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

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

public record Document(
    String id,
    String title,
    String content,
    String filePath,
    String md5,
    Instant createdAt,
    Map<String, Object> metadata
) {
    public Document {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("Document id cannot be null or empty");
        }
        if (title == null || title.isEmpty()) {
            throw new IllegalArgumentException("Document title cannot be null or empty");
        }
        if ((content == null || content.isEmpty()) && (filePath == null || filePath.isEmpty())) {
            throw new IllegalArgumentException("Document content or filePath must be provided");
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (metadata == null) {
            metadata = Map.of();
        }
    }

    public static Document fromContent(String id, String title, String content, Map<String, Object> metadata) {
        return new Document(id, title, content, null, null, Instant.now(), metadata);
    }

    public static Document fromFile(String id, String title, String filePath, String md5, Map<String, Object> metadata) {
        return new Document(id, title, null, filePath, md5, Instant.now(), metadata);
    }

    public boolean isFileBased() {
        return filePath != null && !filePath.isEmpty();
    }
}
