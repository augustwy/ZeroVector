package cn.nexon.zerovector.core.ai;

import java.util.List;

public interface LLMProvider {
    
    /**
     * 理解文档块
     * 使用LLM分析文档块内容，提取摘要、关键词、实体和示例问题
     * 
     * @param prompt 包含文档块内容和上下文的提示词
     * @return LLM返回的JSON格式响应，包含摘要、关键词定义、实体和示例问题
     */
    String comprehendChunk(String prompt);
    
    /**
     * 生成摘要
     * 基于多个文档块的摘要生成整体摘要
     * 
     * @param prompt 包含文档标题和多个文档块摘要的提示词
     * @return LLM生成的整体摘要
     */
    String generateSummary(String prompt);
    
    /**
     * 聚类文档
     * 将多个文档块按照语义相似性进行聚类分组
     * 
     * @param prompt 包含多个文档块摘要的提示词
     * @return LLM返回的JSON格式响应，包含聚类结果（类别名称和对应的文档索引）
     */
    String clusterDocuments(String prompt);
    
    /**
     * 提取关键词
     * 从文本内容中提取关键词
     * 
     * @param prompt 包含文本内容的提示词
     * @return LLM返回的JSON格式响应，包含提取的关键词列表
     */
    String extractKeywords(String prompt);
    
    /**
     * 提取实体
     * 从文本内容中提取命名实体（如人名、地名、组织名等）
     * 
     * @param prompt 包含文本内容的提示词
     * @return LLM返回的JSON格式响应，包含提取的实体列表
     */
    String extractEntities(String prompt);
    
    /**
     * 生成示例问题
     * 基于文本内容生成可能的查询问题
     * 
     * @param prompt 包含文本内容的提示词
     * @return LLM返回的JSON格式响应，包含生成的示例问题列表
     */
    String generateExampleQuestions(String prompt);
    
    /**
     * 决定导航
     * 根据查询和当前节点的子节点信息，决定下一步导航方向
     * 
     * @param prompt 包含查询、当前节点信息和子节点描述的提示词
     * @return LLM返回的JSON格式响应，包含选择的子节点索引、推理过程和置信度
     */
    String decideNavigation(String prompt);
}
