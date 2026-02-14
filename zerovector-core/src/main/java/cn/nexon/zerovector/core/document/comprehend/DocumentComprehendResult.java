package cn.nexon.zerovector.core.document.comprehend;

import java.util.List;

public record DocumentComprehendResult(
    String summary,
    List<String> keywords,
    List<String> entities,
    List<String> exampleQuestions
) {
}
