package cn.nexon.zerovector.core.tree;

import cn.nexon.zerovector.core.ai.LLMProvider;
import cn.nexon.zerovector.core.config.ConcurrencyProperties;
import cn.nexon.zerovector.core.index.KeywordDictionary;
import cn.nexon.zerovector.core.model.*;
import cn.nexon.zerovector.core.storage.MMapDocumentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

/**
 * 语义树构建器
 * 目标：自动化构建语义树和关键词索引。利用虚拟线程并发处理。
 */
public class TreeBuilder {
    private static final Logger logger = LoggerFactory.getLogger(TreeBuilder.class);
    private static final int CLUSTERING_THRESHOLD = 5;
    
    private final LLMProvider llm;
    private final KeywordDictionary dictionary;
    private final MMapDocumentStore store;
    private final ConcurrencyProperties concurrencyConfig;
    private final Semaphore requestSemaphore;

    // 兼容旧构造函数
    public TreeBuilder(LLMProvider llm, KeywordDictionary dictionary, MMapDocumentStore store) {
        this(llm, dictionary, store, new ConcurrencyProperties());
    }
    
    // 支持自定义并发配置
    public TreeBuilder(LLMProvider llm, KeywordDictionary dictionary, MMapDocumentStore store, int maxConcurrentRequests) {
        this(llm, dictionary, store, createDefaultConfig(maxConcurrentRequests));
    }
    
    // 完整构造函数，支持所有并发配置
    public TreeBuilder(LLMProvider llm, KeywordDictionary dictionary, MMapDocumentStore store, ConcurrencyProperties concurrencyConfig) {
        this.llm = llm;
        this.dictionary = dictionary;
        this.store = store;
        this.concurrencyConfig = concurrencyConfig;
        this.requestSemaphore = new Semaphore(concurrencyConfig.getMaxConcurrentRequests());
    }
    
    /**
     * 创建默认配置
     */
    private static ConcurrencyProperties createDefaultConfig(int maxConcurrentRequests) {
        ConcurrencyProperties config = new ConcurrencyProperties();
        config.setMaxConcurrentRequests(maxConcurrentRequests);
        return config;
    }
    
    /**
     * 构建语义树
     */
    public SemanticTree build(List<DocumentChunk> chunks) {
        logger.info("开始构建语义树，共 {} 个文档块，最大并发数: {}", 
                   chunks.size(), concurrencyConfig.getMaxConcurrentRequests());
        
        List<DocumentChunk> processedChunks;
        
        // 根据配置选择处理方式
        if (concurrencyConfig.isEnableBatchProcessing()) {
            processedChunks = processWithBatching(chunks);
        } else {
            processedChunks = processWithConcurrencyLimit(chunks);
        }
        
        // 递归构建树并收集所有节点
        TreeBuildResult result = buildRecursiveWithNodes("Root", processedChunks);
        TreeNode root = result.root();
        
        // 存储文档块到mmap（仅存储非基于文件路径的文档块）
        for (DocumentChunk chunk : processedChunks) {
            try {
                // 只有非基于文件路径的文档块才需要存储内容
                if (!chunk.isFilePathBased()) {
                    store.addChunk(chunk.id(), chunk.content());
                }
            } catch (Exception e) {
                logger.error("Error storing chunk: {}", e.getMessage());
            }
        }
        
        // 持久化树和词典
        saveTree(root);
        saveDictionary(dictionary);
        
        Map<String, DocumentChunk> chunkMap = new HashMap<>();
        processedChunks.forEach(chunk -> chunkMap.put(chunk.id(), chunk));
        
        logger.info("语义树构建完成，共处理 {} 个文档块，包含 {} 个节点", processedChunks.size(), result.nodes().size());
        return new SemanticTree(root, result.nodes(), chunkMap);
    }
    
    /**
     * 使用并发限制处理文档块
     */
    private List<DocumentChunk> processWithConcurrencyLimit(List<DocumentChunk> chunks) {
        // [KEY] 使用 Java 25 Virtual Threads 处理并发摘要提取
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = chunks.stream()
                .map(c -> executor.submit(() -> {
                    try {
                        // 获取信号量许可
                        requestSemaphore.acquire();
                        return summarizeAndExtract(c);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted while waiting for semaphore", e);
                    } finally {
                        // 释放信号量许可
                        requestSemaphore.release();
                    }
                }))
                .toList();
            
            // 等待所有摘要完成
            List<DocumentChunk> processedChunks = new ArrayList<>();
            for (var future : futures) {
                try {
                    processedChunks.add(future.get());
                } catch (Exception e) {
                    logger.error("Error processing chunk: {}", e.getMessage());
                }
            }
            
            return processedChunks;
        } catch (Exception e) {
            throw new RuntimeException("Failed to process chunks with concurrency limit", e);
        }
    }
    
    /**
     * 使用批处理方式处理文档块
     */
    private List<DocumentChunk> processWithBatching(List<DocumentChunk> chunks) {
        List<List<DocumentChunk>> batches = partitionList(chunks, concurrencyConfig.getBatchSize());
        List<DocumentChunk> processedChunks = new ArrayList<>();
        
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < batches.size(); i++) {
                List<DocumentChunk> batch = batches.get(i);
                logger.info("处理批次 {}/{}，包含 {} 个文档块", i+1, batches.size(), batch.size());
                
                var futures = batch.stream()
                    .map(c -> executor.submit(() -> {
                        try {
                            // 获取信号量许可
                            requestSemaphore.acquire();
                            return summarizeAndExtract(c);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Interrupted while waiting for semaphore", e);
                        } finally {
                            // 释放信号量许可
                            requestSemaphore.release();
                        }
                    }))
                    .toList();
                
                // 等待当前批次完成
                for (var future : futures) {
                    try {
                        processedChunks.add(future.get());
                    } catch (Exception e) {
                        logger.error("Error processing chunk in batch {}: {}", i+1, e.getMessage());
                    }
                }
                
                // 批次间延迟，避免过于频繁的请求
                if (i < batches.size() - 1 && concurrencyConfig.getBatchDelayMs() > 0) {
                    try {
                        Thread.sleep(concurrencyConfig.getBatchDelayMs());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        logger.warn("Batch delay interrupted");
                        break;
                    }
                }
            }
        }
        
        return processedChunks;
    }
    
    /**
     * 将列表分割成指定大小的批次
     */
    private <T> List<List<T>> partitionList(List<T> list, int batchSize) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += batchSize) {
            partitions.add(list.subList(i, Math.min(i + batchSize, list.size())));
        }
        return partitions;
    }
    
    /**
     * 树构建结果
     */
    public record TreeBuildResult(
        TreeNode root,
        Map<String, TreeNode> nodes
    ) {}
    
    /**
     * 递归构建树节点并收集所有节点
     */
    private TreeBuildResult buildRecursiveWithNodes(String name, List<DocumentChunk> chunks) {
        Map<String, TreeNode> allNodes = new HashMap<>();
        
        if (chunks.size() <= CLUSTERING_THRESHOLD) {
            try {
                List<NodeCategory> categories = llm.clusterChunks(chunks);
                
                if (categories.size() == 1 && categories.get(0).getChunks().size() == chunks.size()) {
                    TreeNode leafNode = createLeafNode(name, chunks);
                    allNodes.put(leafNode.id(), leafNode);
                    return new TreeBuildResult(leafNode, allNodes);
                }
                
                if (categories.size() > 1) {
                    List<String> childIds = new ArrayList<>();
                    List<TreeNode> childNodes = new ArrayList<>();
                    
                    for (NodeCategory cat : categories) {
                        // 提取关键词和实体
                        String summariesText = String.join(" ", cat.getSummaries());
                        List<String> keywords = llm.extractKeywords(summariesText);
                        List<String> entities = llm.extractEntities(summariesText);
                        List<String> examples = llm.generateExampleQuestions(summariesText);
                        
                        // 添加到词典
                        dictionary.addEntries(keywords, cat.getNodeId());
                        
                        // 递归处理子节点
                        TreeBuildResult childResult = buildRecursiveWithNodes(cat.getName(), cat.getChunks());
                        TreeNode childNode = childResult.root();
                        childIds.add(childNode.id());
                        childNodes.add(childNode);
                        
                        // 收集子节点的所有节点
                        allNodes.putAll(childResult.nodes());
                    }
                    
                    // 创建当前节点
                    String nodeId = UUID.randomUUID().toString();
                    List<String> allContent = chunks.stream().map(DocumentChunk::content).collect(Collectors.toList());
                    List<String> nodeKeywords = llm.extractKeywords(String.join(" ", allContent));
                    List<String> nodeEntities = llm.extractEntities(String.join(" ", allContent));
                    List<String> nodeExamples = llm.generateExampleQuestions(String.join(" ", allContent));
                    
                    // 添加到词典
                    dictionary.addEntries(nodeKeywords, nodeId);
                    
                    TreeNode currentNode = new TreeNode(
                        nodeId,
                        name,
                        "Category node containing " + categories.size() + " subcategories",
                        NodeType.CATEGORY,
                        childIds,
                        List.of(),
                        nodeEntities,
                        nodeKeywords,
                        nodeExamples
                    );
                    
                    allNodes.put(nodeId, currentNode);
                    return new TreeBuildResult(currentNode, allNodes);
                }
            } catch (Exception e) {
                logger.warn("聚类失败，创建叶子节点: {}", e.getMessage());
            }
            
            // 聚类失败或只有一个类别，创建叶子节点
            TreeNode leafNode = createLeafNode(name, chunks);
            allNodes.put(leafNode.id(), leafNode);
            return new TreeBuildResult(leafNode, allNodes);
        }
        
        // 1. 调用 LLM 进行聚类
        List<NodeCategory> categories = llm.clusterChunks(chunks);
        
        // 2. 遍历分类，递归构建
        List<String> childIds = new ArrayList<>();
        List<TreeNode> childNodes = new ArrayList<>();
        
        for (NodeCategory cat : categories) {
            // [CRITICAL] 构建节点时提取关键词
            String summariesText = String.join(" ", cat.getSummaries());
            List<String> keywords = llm.extractKeywords(summariesText);
            List<String> entities = llm.extractEntities(summariesText);
            List<String> examples = llm.generateExampleQuestions(summariesText);
            
            // 添加到词典
            dictionary.addEntries(keywords, cat.getNodeId());
            
            // 递归处理子节点
            TreeBuildResult childResult = buildRecursiveWithNodes(cat.getName(), cat.getChunks());
            TreeNode childNode = childResult.root();
            childIds.add(childNode.id());
            childNodes.add(childNode);
            
            // 收集子节点的所有节点
            allNodes.putAll(childResult.nodes());
        }
        
        // 创建当前节点
        String nodeId = UUID.randomUUID().toString();
        List<String> allContent = chunks.stream().map(DocumentChunk::content).collect(Collectors.toList());
        List<String> nodeKeywords = llm.extractKeywords(String.join(" ", allContent));
        List<String> nodeEntities = llm.extractEntities(String.join(" ", allContent));
        List<String> nodeExamples = llm.generateExampleQuestions(String.join(" ", allContent));
        
        // 添加到词典
        dictionary.addEntries(nodeKeywords, nodeId);
        
        TreeNode currentNode = new TreeNode(
            nodeId,
            name,
            "Category node containing " + categories.size() + " subcategories",
            NodeType.CATEGORY,
            childIds,
            List.of(),
            nodeEntities,
            nodeKeywords,
            nodeExamples
        );
        
        allNodes.put(nodeId, currentNode);
        return new TreeBuildResult(currentNode, allNodes);
    }
    
    /**
     * 创建叶子节点
     */
    private TreeNode createLeafNode(String name, List<DocumentChunk> chunks) {
        String nodeId = UUID.randomUUID().toString();
        List<String> chunkIds = chunks.stream().map(DocumentChunk::id).collect(Collectors.toList());
        
        // 提取关键词和实体
        List<String> allKeywords = new ArrayList<>();
        List<String> allEntities = new ArrayList<>();
        List<String> allExamples = new ArrayList<>();
        
        for (DocumentChunk chunk : chunks) {
            List<String> keywords = llm.extractKeywords(chunk.content());
            List<String> entities = llm.extractEntities(chunk.content());
            List<String> examples = llm.generateExampleQuestions(chunk.content());
            
            allKeywords.addAll(keywords);
            allEntities.addAll(entities);
            allExamples.addAll(examples);
        }
        
        // 添加到词典
        dictionary.addEntries(allKeywords, nodeId);
        
        return new TreeNode(
            nodeId,
            name,
            "Leaf node containing " + chunks.size() + " documents",
            NodeType.LEAF,
            List.of(),
            chunkIds,
            allEntities,
            allKeywords,
            allExamples
        );
    }
    
    /**
     * 递归构建树节点
     */
    private TreeNode buildRecursive(String name, List<DocumentChunk> chunks) {
        // 如果块数量小于阈值，尝试进一步聚类以确保内容相关性
        if (chunks.size() <= CLUSTERING_THRESHOLD) {
            try {
                List<NodeCategory> categories = llm.clusterChunks(chunks);
                
                if (categories.size() == 1 && categories.get(0).getChunks().size() == chunks.size()) {
                    return createLeafNode(name, chunks);
                }
                
                if (categories.size() > 1) {
                    List<String> childIds = new ArrayList<>();
                    for (NodeCategory cat : categories) {
                        // 提取关键词和实体
                        String summariesText = String.join(" ", cat.getSummaries());
                        List<String> keywords = llm.extractKeywords(summariesText);
                        List<String> entities = llm.extractEntities(summariesText);
                        List<String> examples = llm.generateExampleQuestions(summariesText);
                        
                        // 添加到词典
                        dictionary.addEntries(keywords, cat.getNodeId());
                        
                        // 递归处理子节点
                        TreeNode childNode = buildRecursive(cat.getName(), cat.getChunks());
                        childIds.add(childNode.id());
                    }
                    
                    // 创建当前节点
                    String nodeId = UUID.randomUUID().toString();
                    List<String> allContent = chunks.stream().map(DocumentChunk::content).collect(Collectors.toList());
                    List<String> nodeKeywords = llm.extractKeywords(String.join(" ", allContent));
                    List<String> nodeEntities = llm.extractEntities(String.join(" ", allContent));
                    List<String> nodeExamples = llm.generateExampleQuestions(String.join(" ", allContent));
                    
                    // 添加到词典
                    dictionary.addEntries(nodeKeywords, nodeId);
                    
                    return new TreeNode(
                        nodeId,
                        name,
                        "Category node containing " + categories.size() + " subcategories",
                        NodeType.CATEGORY,
                        childIds,
                        List.of(),
                        nodeEntities,
                        nodeKeywords,
                        nodeExamples
                    );
                }
            } catch (Exception e) {
                logger.warn("聚类失败，创建叶子节点: {}", e.getMessage());
            }
            
            // 聚类失败或只有一个类别，创建叶子节点
            return createLeafNode(name, chunks);
        }
        
        // 1. 调用 LLM 进行聚类
        List<NodeCategory> categories = llm.clusterChunks(chunks);
        
        // 2. 遍历分类，递归构建
        List<String> childIds = new ArrayList<>();
        for (NodeCategory cat : categories) {
            // [CRITICAL] 构建节点时提取关键词
            String summariesText = String.join(" ", cat.getSummaries());
            List<String> keywords = llm.extractKeywords(summariesText);
            List<String> entities = llm.extractEntities(summariesText);
            List<String> examples = llm.generateExampleQuestions(summariesText);
            
            // 添加到词典
            dictionary.addEntries(keywords, cat.getNodeId());
            
            // 递归处理子节点
            TreeNode childNode = buildRecursive(cat.getName(), cat.getChunks());
            childIds.add(childNode.id());
        }
        
        // 创建当前节点
        String nodeId = UUID.randomUUID().toString();
        List<String> allContent = chunks.stream().map(DocumentChunk::content).collect(Collectors.toList());
        List<String> nodeKeywords = llm.extractKeywords(String.join(" ", allContent));
        List<String> nodeEntities = llm.extractEntities(String.join(" ", allContent));
        List<String> nodeExamples = llm.generateExampleQuestions(String.join(" ", allContent));
        
        // 添加到词典
        dictionary.addEntries(nodeKeywords, nodeId);
        
        return new TreeNode(
            nodeId,
            name,
            "Category node containing " + categories.size() + " subcategories",
            NodeType.CATEGORY,
            childIds,
            List.of(),
            nodeEntities,
            nodeKeywords,
            nodeExamples
        );
    }
    
    /**
     * 摘要和关键词提取
     */
    private DocumentChunk summarizeAndExtract(DocumentChunk chunk) {
        String content;
        
        // 如果是基于文件路径的文档块，需要从文件中读取内容
        if (chunk.isFilePathBased()) {
            try {
                content = Files.readString(Paths.get(chunk.filePath()));
            } catch (IOException e) {
                logger.error("Failed to read content from file: {}", chunk.filePath(), e);
                // 如果读取失败，跳过这个文档块
                return chunk;
            }
        } else {
            content = chunk.content();
        }
        
        // 如果内容为空，跳过摘要生成
        if (content == null || content.trim().isEmpty()) {
            return chunk;
        }
        
        String summary = llm.generateSummary(chunk.id(), content);
        return new DocumentChunk(
            chunk.id(),
            content,  // 对于基于文件路径的文档块，这里保存实际内容
            summary,
            chunk.filePath(),
            chunk.md5(),
            chunk.metadata()
        );
    }
    
    /**
     * 递归收集所有节点
     */
    private void collectAllNodes(TreeNode node, Map<String, TreeNode> nodeMap) {
        if (node == null) {
            return;
        }
        
        // 将当前节点添加到映射中
        nodeMap.put(node.id(), node);
        
        // 递归处理所有子节点
        // 注意：这里我们不能直接从nodeMap中获取子节点，因为子节点还没有被添加
        // 我们需要通过buildRecursive方法返回的节点来获取子节点
        // 但是在当前的树结构中，我们只能通过ID引用子节点，而没有直接的引用
        
        // 解决方案：我们需要修改TreeNode类，使其包含对子节点的直接引用
        // 或者我们需要在buildRecursive过程中收集所有节点
        
        // 暂时的解决方案：通过遍历所有可能的节点来查找子节点
        // 这是一个临时解决方案，效率不高
        for (String childId : node.childrenIds()) {
            // 这里我们无法直接获取子节点，因为我们没有存储所有节点的引用
            // 我们需要在buildRecursive过程中收集所有节点
            logger.warn("无法直接获取子节点 {}，需要修改TreeNode结构或构建过程", childId);
        }
    }
    
    /**
     * 递归构建节点映射
     */
    private void buildNodeMapRecursive(TreeNode node, Map<String, TreeNode> nodeMap) {
        if (node == null) {
            return;
        }
        
        // 将当前节点添加到映射中
        nodeMap.put(node.id(), node);
        
        // 递归处理所有子节点
        for (String childId : node.childrenIds()) {
            // 从节点映射中查找子节点
            TreeNode childNode = nodeMap.get(childId);
            if (childNode != null) {
                // 如果子节点已经在映射中，递归处理
                buildNodeMapRecursive(childNode, nodeMap);
            }
        }
    }
    
    /**
     * 构建节点映射（已弃用，使用buildNodeMapRecursive代替）
     */
    private void buildNodeMap(TreeNode node, Map<String, TreeNode> nodeMap) {
        nodeMap.put(node.id(), node);
        for (String childId : node.childrenIds()) {
            // 这里需要从某个地方获取子节点，简化处理
            // 实际实现中需要维护一个节点映射
        }
    }
    
    /**
     * 保存树结构
     */
    private void saveTree(TreeNode root) {
        // 实现树结构持久化
        // 可以使用JSON或其他格式
    }
    
    /**
     * 保存词典
     */
    private void saveDictionary(KeywordDictionary dictionary) {
        // 实现词典持久化
    }
    
    /**
     * 节点分类信息
     */
    public static class NodeCategory {
        private final String nodeId;
        private final String name;
        private final List<DocumentChunk> chunks;
        private final List<String> summaries;
        
        public NodeCategory(String nodeId, String name, List<DocumentChunk> chunks, List<String> summaries) {
            this.nodeId = nodeId;
            this.name = name;
            this.chunks = chunks;
            this.summaries = summaries;
        }
        
        public String getNodeId() { return nodeId; }
        public String getName() { return name; }
        public List<DocumentChunk> getChunks() { return chunks; }
        public List<String> getSummaries() { return summaries; }
    }
}