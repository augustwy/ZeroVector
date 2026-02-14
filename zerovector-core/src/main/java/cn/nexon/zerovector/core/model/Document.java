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
