package cn.nexon.zerovector.core.ai;

import cn.nexon.zerovector.core.model.DocumentChunk;
import cn.nexon.zerovector.core.model.NavigationAction;
import cn.nexon.zerovector.core.model.TreeNode;
import cn.nexon.zerovector.core.tree.TreeBuilder.NodeCategory;

import java.util.List;

public interface LLMService {
    String generateSummary(String title, String content);
    
    /**
     * 将文档块聚类为树节点
     * @param chunks 文档块列表
     * @param clusterSize 聚类大小
     * @return 聚类后的树节点列表
     */
    List<TreeNode> clusterChunks(List<String> chunks, int clusterSize);
    
    /**
     * 将文档块聚类为分类
     * @param chunks 文档块列表
     * @return 分类列表
     */
    List<NodeCategory> clusterChunks(List<DocumentChunk> chunks);
    
    /**
     * 提取关键词
     * @param content 文档内容
     * @return 关键词列表
     */
    List<String> extractKeywords(String content);
    
    /**
     * 提取实体
     * @param content 文档内容
     * @return 实体列表
     */
    List<String> extractEntities(String content);
    
    /**
     * 生成示例问题
     * @param content 文档内容
     * @return 示例问题列表
     */
    List<String> generateExampleQuestions(String content);
    
    /**
     * 根据用户查询和当前节点信息，决定下一步导航动作
     * @param prompt 提示信息或用户查询
     * @param currentNode 当前节点
     * @param childNodes 当前节点的子节点
     * @return 导航动作
     */
    NavigationAction decideNavigation(String prompt, TreeNode currentNode, List<TreeNode> childNodes);
    
    /**
     * 生成节点的描述
     * @param node 节点信息
     * @param relatedChunks 相关文档块
     * @return 节点描述
     */
    String generateNodeDescription(TreeNode node, List<String> relatedChunks);
}