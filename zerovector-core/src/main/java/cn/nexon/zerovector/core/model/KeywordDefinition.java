package cn.nexon.zerovector.core.model;

import java.util.Objects;

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
        if (definition == null || definition.trim().isEmpty()) {
            throw new IllegalArgumentException("Definition cannot be null or empty");
        }
        if (context == null || context.trim().isEmpty()) {
            throw new IllegalArgumentException("Context cannot be null or empty");
        }
        if (documentId == null || documentId.trim().isEmpty()) {
            throw new IllegalArgumentException("DocumentId cannot be null or empty");
        }
        if (frequency <= 0) {
            frequency = 1;
        }
    }

    public static KeywordDefinition of(String keyword, String definition, String context, String documentId) {
        return new KeywordDefinition(keyword.trim(), definition.trim(), context.trim(), documentId, 1);
    }

    public static KeywordDefinition of(String keyword, String definition, String context, String documentId, int frequency) {
        return new KeywordDefinition(keyword.trim(), definition.trim(), context.trim(), documentId, frequency);
    }

    public String normalizedKeyword() {
        return keyword.toLowerCase().trim();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        KeywordDefinition that = (KeywordDefinition) o;
        return Objects.equals(normalizedKeyword(), that.normalizedKeyword()) &&
               Objects.equals(documentId, that.documentId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(normalizedKeyword(), documentId);
    }
}
