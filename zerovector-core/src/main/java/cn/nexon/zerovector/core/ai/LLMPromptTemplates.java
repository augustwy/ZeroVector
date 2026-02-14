package cn.nexon.zerovector.core.ai;

import java.util.List;

public final class LLMPromptTemplates {

    private LLMPromptTemplates() {
    }

    public static String generateSummary(String title, String content) {
        return """
                请为以下文档生成一个简洁的摘要(不超过50字):
                标题：%s
                内容：%s
                """.formatted(title, content);
    }

    public static String clusterDocumentChunks(List<String> chunks) {
        StringBuilder chunksBuilder = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            chunksBuilder.append("[").append(i).append("] ")
                    .append(chunks.get(i).substring(0, Math.min(200, chunks.get(i).length())))
                    .append("\n");
        }

        return """
                请将以下文档块按内容主题相似性聚类为3-5个类别。
                重要：只有内容真正相关的文档才能放在同一类别中。
                如果文档内容完全不相关，请将它们分到不同的类别，即使某些类别可能只有一个文档。
                %s
                请按以下JSON格式返回结果:
                {
                  "clusters": [
                    {
                      "name": "类别名称",
                      "chunks": [0, 1, 2]
                    }
                  ]
                }
                """.formatted(chunksBuilder);

    }

    public static String decideNavigation(String query, String currentNodeName, String currentNodeDescription, List<String> childNodes) {
        StringBuilder nodesBuilder = new StringBuilder();
        for (int i = 0; i < childNodes.size(); i++) {
            nodesBuilder.append("[").append(i).append("] ").append(childNodes.get(i)).append("\n");
        }

        return """
                用户查询：%s
                当前节点：%s - %s
                可选子节点：
                %s
                请分析用户查询，选择最相关的子节点。
                返回JSON格式: {"selectedIndex": 0, "reasoning": "选择原因", "confidence": 0.8}
                如果没有相关子节点，请返回{"selectedIndex": -1, "reasoning": "没有相关子节点", "confidence": 0.0}
                """.formatted(query, currentNodeName, currentNodeDescription, nodesBuilder);
    }

    public static String extractEntities(String content) {
        return """
                请从以下内容中提取5个重要的实体(如人名、地名、组织名、产品名等):
                %s
                请只列出实体，每行一个：""".formatted(content);
    }

    public static String extractKeywords(String content) {
        return "请从以下内容中提取5个关键词:" +
                content +
                "请只列出关键词，每行一个：";
    }

    public static String generateExampleQuestions(String content) {
        return "请为以下内容生成3个示例问题:" +
                content +
                "请生成3个简洁的问题,每行一个:";
    }
}
