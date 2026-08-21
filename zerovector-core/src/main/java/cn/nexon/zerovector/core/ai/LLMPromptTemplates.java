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

package cn.nexon.zerovector.core.ai;

import java.util.List;

public final class LLMPromptTemplates {

    private LLMPromptTemplates() {
    }

    /**
     * 生成文档摘要。
     * <p>返回纯文本，直接作为 {@code summary} 使用，且会被截断为树节点名，需短。
     */
    public static String generateSummary(String title, String content) {
        return """
            你是知识库文档处理助手。请为以下文档生成一句话摘要（不超过50字）。
            要求：
            1. 开头即点明主题——该摘要会被截断为分类节点名，前半段最重要。
            2. 包含核心术语，便于后续聚类与检索。
            3. 直接输出摘要文本，不要输出任何前缀、标签或 JSON。

            标题：%s
            内容：%s
            """.formatted(title, content);
    }

    /**
     * 文档聚类。
     * <p>返回 {@code {"clusters":[{"name","chunkIndices"}]}} 结构。
     */
    public static String clusterDocumentChunks(List<String> chunks) {
        StringBuilder chunksBuilder = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            chunksBuilder.append("[").append(i).append("] ")
                    .append(chunks.get(i).substring(0, Math.min(200, chunks.get(i).length())))
                    .append("\n");
        }

        return """
            你是知识库分类助手。请将以下文档摘要按主题聚类，输出将用于构建分类树。
            要求：
            1. 类别名用 2-6 个字的短语，概括该类共同主题。
            2. 每个文档块必须且只能归入一个类别，用其索引号表示；所有文档块都要覆盖，不得遗漏。
            3. 只有主题真正相关的文档才归入同一类；不相关的宁可单独成类，也不要强行合并。
            4. 类别数量按实际主题决定，文档多可多分、文档少可少分，不强制固定数量。

            %s

            只返回一个 JSON 对象，不要用 markdown 代码块或多余文字包裹，格式：
            {"clusters": [{"name": "类别名", "chunkIndices": [0, 1, 2]}]}
            """.formatted(chunksBuilder.toString());
    }

    /**
     * 导航决策。
     * <p>返回 {@code NavigationDecisionResult}（action + selectedIndex/selectedIndexes + reasoning + confidence）。
     */
    public static String decideNavigation(String query, String currentNodeName, String currentNodeDescription, List<String> childNodes) {
        StringBuilder nodesBuilder = new StringBuilder();
        for (int i = 0; i < childNodes.size(); i++) {
            nodesBuilder.append("[").append(i).append("] ").append(childNodes.get(i)).append("\n");
        }

        return """
            你是知识库导航助手。请判断用户查询与以下各子节点的相关性，选择一个动作继续检索。

            用户查询：%s
            当前节点：%s - %s
            可选子节点：
            %s
            动作说明：
            - select_child：只与某一个子节点最相关。selectedIndex 填该子节点在上表 [i] 中的 i。
            - expand_multiple：与多个子节点都相关（查询有歧义或横跨多类）。selectedIndexes 填所有相关子节点的下标数组。
            - fallback_search：与任何子节点都不相关，建议改用关键词检索。
            - stop：与任何子节点都不相关且无需继续。

            confidence 校准（决定系统是否启用兜底检索）：
            - 0.9 以上 = 高度确定；0.5 左右 = 犹豫；0.3 以下 = 几乎不确定（系统会触发兜底检索）。
            所有下标必须是上面列出的有效下标；只返回一个 JSON 对象，不要用 markdown 代码块或多余文字包裹。

            返回格式示例：
            {"action":"select_child","selectedIndex":0,"reasoning":"查询中的某术语与该子节点主题一致","confidence":0.9}
            """.formatted(query, currentNodeName, currentNodeDescription, nodesBuilder.toString());
    }

    /**
     * 提取实体。
     * <p>返回 JSON 字符串数组，经 {@code JsonUtils.parseStringList} 解析。
     */
    public static String extractEntities(String content) {
        return """
            请从以下内容中提取 5-10 个专有实体（人名、地名、组织名、产品名、术语缩写等）。
            这些实体用于识别 LLM 本身不认识的新名词，请原样保留，不要翻译或改写。
            %s
            只返回一个 JSON 字符串数组，不要用 markdown 代码块或多余文字包裹。
            例如：["实体1", "实体2"]
            """.formatted(content);
    }

    /**
     * 提取关键词。
     * <p>返回 JSON 字符串数组，经 {@code JsonUtils.parseStringList} 解析，结果写入倒排索引。
     */
    public static String extractKeywords(String content) {
        return """
            请从以下内容中提取 5-10 个关键词，这些关键词将用于倒排索引检索。
            要求：
            1. 只提取内容中出现的原文术语（用户会以原文词形检索），勿改写、勿翻译、勿造词。
            2. 避免提取"我们""系统"这类无区分度的泛词。
            %s
            只返回一个 JSON 字符串数组，不要用 markdown 代码块或多余文字包裹。
            例如：["关键词1", "关键词2"]
            """.formatted(content);
    }

    /**
     * 生成示例问题。
     * <p>返回 JSON 字符串数组，经 {@code JsonUtils.parseStringList} 解析。
     */
    public static String generateExampleQuestions(String content) {
        return """
            请为以下内容生成 3 个示例问题（即用户可能就此内容提出的检索问题，具体、口语化，而非泛泛而问）：
            %s
            只返回一个 JSON 字符串数组，不要用 markdown 代码块或多余文字包裹。
            例如：["问题1", "问题2", "问题3"]
            """.formatted(content);
    }

    /**
     * 文档分块理解。
     * <p>返回 {@code {summary, keywordDefinitions, entities, exampleQuestions}} 结构。
     */
    public static String comprehendChunk(String context, int chunkIndex, int totalChunks) {
        return """
            你是知识库文档解析助手。请分析以下文档内容（第%s部分，共%s部分），提取结果将用于构建检索索引。

            %s

            请提取并只返回一个 JSON 对象（不要用 markdown 代码块或多余文字包裹），字段如下：
            - summary：本部分摘要，不超过100字，开头点明主题。
            - keywordDefinitions：5-10 个关键词及其定义。keyword 必须是内容中出现的原文术语（用户会用原文词形检索，勿改写、勿造词），definition 用一句话解释，context 为该词出现的上下文（不超过50字）。避免提取"我们""系统"这类无区分度的泛词。
            - entities：5-10 个专有实体（人名、地名、组织名、产品名、术语缩写），这些可能是 LLM 不认识的新名词，请原样保留。
            - exampleQuestions：1-3 个用户可能就此内容提出的检索问题。

            格式：
            {
              "summary": "摘要",
              "keywordDefinitions": [
                {"keyword": "原文术语", "definition": "定义", "context": "上下文"}
              ],
              "entities": ["实体1", "实体2"],
              "exampleQuestions": ["问题1", "问题2"]
            }
            """.formatted(chunkIndex, totalChunks, context);
    }

    /**
     * 从用户查询中提取关键词。
     * <p>返回纯文本多行，经 {@code HybridNavigator.parseKeywordsResponse} 按行解析。
     */
    public static String extractQueryKeywords(String query) {
        return """
            请从以下用户查询中提取 3-5 个最重要的关键词，这些关键词将用于在文档倒排索引中做精确匹配。

            %s

            严格要求：
            1. 只提取查询中出现的原文词形（英文保持小写），不要改写、不要翻译、不要扩展、不要补全同义词——否则会匹配不到索引中的词。
            2. 每行输出一个关键词，不要编号、不要 JSON、不要解释、不要多余文字。
            """.formatted(query);
    }
}
