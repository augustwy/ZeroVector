package cn.nexon.zerovector.core.document.comprehend;

import cn.nexon.zerovector.core.model.KeywordDefinition;

import java.util.List;

public record DocumentComprehendResult(
    String summary,
    List<KeywordDefinition> keywordDefinitions,
    List<String> entities,
    List<String> exampleQuestions
) {
    public DocumentComprehendResult {
        if (keywordDefinitions == null) {
            keywordDefinitions = List.of();
        }
        if (entities == null) {
            entities = List.of();
        }
        if (exampleQuestions == null) {
            exampleQuestions = List.of();
        }
    }

    public static DocumentComprehendResult of(String summary, List<KeywordDefinition> keywordDefinitions) {
        return new DocumentComprehendResult(summary, keywordDefinitions, List.of(), List.of());
    }

    public static DocumentComprehendResult of(String summary, List<KeywordDefinition> keywordDefinitions, List<String> entities, List<String> exampleQuestions) {
        return new DocumentComprehendResult(summary, keywordDefinitions, entities, exampleQuestions);
    }
}
