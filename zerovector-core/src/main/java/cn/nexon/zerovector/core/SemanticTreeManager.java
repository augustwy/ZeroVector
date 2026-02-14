package cn.nexon.zerovector.core;

import cn.nexon.zerovector.core.ai.LLMProvider;
import cn.nexon.zerovector.core.config.ConcurrencyProperties;
import cn.nexon.zerovector.core.document.DocumentProcessor;
import cn.nexon.zerovector.core.document.DocumentProcessorFactory;
import cn.nexon.zerovector.core.document.comprehend.DocumentComprehender;
import cn.nexon.zerovector.core.document.comprehend.DocumentComprehendResult;
import cn.nexon.zerovector.core.index.KeywordDictionary;
import cn.nexon.zerovector.core.model.DocumentChunk;
import cn.nexon.zerovector.core.model.NavigationResult;
import cn.nexon.zerovector.core.model.SemanticTree;
import cn.nexon.zerovector.core.navigator.HybridNavigator;
import cn.nexon.zerovector.core.storage.MMapDocumentStore;
import cn.nexon.zerovector.core.storage.ShardedTreeStorage;
import cn.nexon.zerovector.core.tree.Navigator;
import cn.nexon.zerovector.core.tree.TreeBuilder;
import cn.nexon.zerovector.core.util.MD5Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 语义树管理器
 * 整合树构建和导航功能，支持分片存储和文档处理
 * 
 * 支持两种存储模式：
 * 1. 分片存储模式：适用于大型文档集，将语义树分散存储在多个文件中，支持增量更新
 * 2. 非分片存储模式：适用于中小型文档集，将语义树存储在单个文件中，简单高效
 * 
 * 默认使用非分片模式，可通过构造函数参数或静态工厂方法指定存储模式
 */
public class SemanticTreeManager {
    private static final Logger logger = LoggerFactory.getLogger(SemanticTreeManager.class);

    private final LLMProvider llmProvider;
    private final DocumentComprehender documentComprehender;
    private KeywordDictionary keywordDictionary;
    private final Path storagePath;
    private final String treeFilePath;
    private final String treeStorageDir;
    private final String dictionaryFilePath;
    private final String documentsDir;
    private final boolean useShardedStorage;
    private final DocumentProcessor documentProcessor;
    private final ConcurrencyProperties concurrencyProperties;
    private MMapDocumentStore documentStore;
    private ShardedTreeStorage shardedTreeStorage;
    private SemanticTree semanticTree;
    private Navigator navigator;
    private DocumentProcessor.ProcessingConfig currentConfig;
    private HybridNavigator hybridNavigator;

    public SemanticTreeManager(LLMProvider llmProvider, Path storagePath) {
        this(llmProvider, storagePath, false, new ConcurrencyProperties());
    }

    public SemanticTreeManager(LLMProvider llmProvider, Path storagePath, ConcurrencyProperties concurrencyProperties) {
        this(llmProvider, storagePath, true, concurrencyProperties);
    }

    public SemanticTreeManager(LLMProvider llmProvider, Path storagePath, boolean useShardedStorage, ConcurrencyProperties concurrencyProperties) {
        this(llmProvider, new DocumentComprehender(llmProvider, 4000), storagePath, useShardedStorage, concurrencyProperties);
    }

    public SemanticTreeManager(LLMProvider llmProvider, DocumentComprehender documentComprehender, Path storagePath, boolean useShardedStorage, ConcurrencyProperties concurrencyProperties) {
        this.llmProvider = llmProvider;
        this.documentComprehender = documentComprehender;
        this.keywordDictionary = new KeywordDictionary();
        this.storagePath = storagePath;
        this.treeFilePath = storagePath.toString() + ".tree";
        this.treeStorageDir = storagePath.toString() + "_shards";
        this.dictionaryFilePath = storagePath.toString() + ".dict";
        this.documentsDir = storagePath.toString() + "_docs";
        this.useShardedStorage = useShardedStorage;
        this.documentProcessor = DocumentProcessorFactory.getInstance().getDefaultProcessor();
        this.currentConfig = DocumentProcessor.ProcessingConfig.DEFAULT;
        this.concurrencyProperties = concurrencyProperties;
    }
    
    /**
     * 创建非分片模式的语义树管理器
     * @param llmProvider LLM提供者
     * @param storagePath 存储路径
     * @param concurrencyProperties 并发属性
     * @return 非分片模式的语义树管理器实例
     */
    public static SemanticTreeManager createNonSharded(LLMProvider llmProvider, Path storagePath, ConcurrencyProperties concurrencyProperties) {
        return new SemanticTreeManager(llmProvider, storagePath, false, concurrencyProperties);
    }
    
    /**
     * 创建分片模式的语义树管理器
     * @param llmProvider LLM提供者
     * @param storagePath 存储路径
     * @param concurrencyProperties 并发属性
     * @return 分片模式的语义树管理器实例
     */
    public static SemanticTreeManager createSharded(LLMProvider llmProvider, Path storagePath, ConcurrencyProperties concurrencyProperties) {
        return new SemanticTreeManager(llmProvider, storagePath, true, concurrencyProperties);
    }
    
    /**
     * 初始化管理器
     */
    public void initialize() throws IOException {
        this.documentStore = MMapDocumentStore.open(storagePath.toString());
        
        Files.createDirectories(Paths.get(documentsDir));
        
        if (useShardedStorage) {
            this.shardedTreeStorage = new ShardedTreeStorage(treeStorageDir);
        }
        
        if (useShardedStorage) {
            this.semanticTree = shardedTreeStorage.loadTree();
        } else {
            this.semanticTree = SemanticTree.loadFromFile(treeFilePath);
        }
        
        try {
            KeywordDictionary loadedDict = KeywordDictionary.loadFromFile(dictionaryFilePath);
            this.keywordDictionary = loadedDict;
            logger.info("已加载已保存的关键词字典");
        } catch (IOException e) {
            logger.warn("加载关键词字典失败，将使用新字典: {}", e.getMessage());
        }
        
        if (this.semanticTree != null) {
            this.navigator = new Navigator(semanticTree, llmProvider, documentStore);
            this.hybridNavigator = new HybridNavigator(semanticTree, keywordDictionary, llmProvider, documentStore);
            logger.info("已加载已保存的语义树");
        }
    }
    
    /**
     * 从文档块构建语义树
     */
    public void buildTree(List<DocumentChunk> chunks) {
        logger.info("构建语义树，包含 {} 个文档块", chunks.size());
        
        List<DocumentChunk> comprehendedChunks = new ArrayList<>();
        for (DocumentChunk chunk : chunks) {
            logger.debug("  - {}: {}", chunk.id(), chunk.summary());
            DocumentComprehendResult result = documentComprehender.comprehend(chunk);
            
            DocumentChunk comprehendedChunk = new DocumentChunk(
                    chunk.id(),
                    chunk.content(),
                    result.summary(),
                    chunk.filePath(),
                    chunk.md5(),
                    chunk.metadata()
            );
            comprehendedChunks.add(comprehendedChunk);
        }
        
        TreeBuilder builder = new TreeBuilder(llmProvider, keywordDictionary, documentStore, concurrencyProperties);
        this.semanticTree = builder.build(comprehendedChunks);
        this.navigator = new Navigator(semanticTree, llmProvider, documentStore);
        this.hybridNavigator = new HybridNavigator(semanticTree, keywordDictionary, llmProvider, documentStore);
        
        logger.info("语义树构建完成，包含 {} 个文档块", semanticTree.chunks().size());
        
        if (!useShardedStorage) {
            try {
                semanticTree.saveToFile(treeFilePath);
                logger.info("非分片模式：语义树已自动保存到文件");
            } catch (IOException e) {
                logger.error("自动保存语义树失败: {}", e.getMessage());
            }
        }
    }
    
    /**
     * 增量添加文档到现有语义树
     */
    public void addDocumentChunks(List<DocumentChunk> newChunks) {
        logger.info("添加 {} 个新文档块到语义树", newChunks.size());
        for (DocumentChunk chunk : newChunks) {
            logger.debug("  - {}: {}", chunk.id(), chunk.summary());
        }
        
        if (semanticTree == null) {
            logger.info("没有现有语义树，构建新树");
            buildTree(newChunks);
            return;
        }
        
        logger.info("现有语义树包含 {} 个文档块", semanticTree.chunks().size());
        
        SemanticTree oldTree = semanticTree;
        
        List<DocumentChunk> allChunks = new ArrayList<>(semanticTree.chunks().values());
        allChunks.addAll(newChunks);
        
        List<DocumentChunk> comprehendedChunks = new ArrayList<>();
        for (DocumentChunk chunk : allChunks) {
            DocumentComprehendResult result = documentComprehender.comprehend(chunk);
            DocumentChunk comprehendedChunk = new DocumentChunk(
                    chunk.id(),
                    chunk.content(),
                    result.summary(),
                    chunk.filePath(),
                    chunk.md5(),
                    chunk.metadata()
            );
            comprehendedChunks.add(comprehendedChunk);
        }
        
        TreeBuilder builder = new TreeBuilder(llmProvider, keywordDictionary, documentStore, concurrencyProperties);
        SemanticTree newTree = builder.build(comprehendedChunks);
        this.semanticTree = newTree;
        this.navigator = new Navigator(semanticTree, llmProvider, documentStore);
        this.hybridNavigator = new HybridNavigator(semanticTree, keywordDictionary, llmProvider, documentStore);
        
        logger.info("更新后语义树包含 {} 个文档块", semanticTree.chunks().size());
        
        if (useShardedStorage && shardedTreeStorage != null) {
            try {
                shardedTreeStorage.updateTreeIncremental(oldTree, newTree);
            } catch (IOException e) {
                logger.error("增量更新失败，回退到完整保存: {}", e.getMessage());
                try {
                    shardedTreeStorage.saveTree(newTree);
                } catch (IOException ex) {
                    logger.error("完整保存也失败: {}", ex.getMessage());
                }
            }
        } else {
            try {
                semanticTree.saveToFile(treeFilePath);
                logger.info("非分片模式：语义树已保存到文件");
            } catch (IOException e) {
                logger.error("保存语义树失败: {}", e.getMessage());
            }
        }
    }
    
    /**
     * 从文件路径添加文档（自动处理和分块）
     * @param filePath 文件路径
     * @return 处理结果的异步Future
     */
    public CompletableFuture<Void> addDocumentAsync(Path filePath) {
        String currentMd5;
        try {
            currentMd5 = MD5Util.calculateMD5(filePath);
        } catch (IOException e) {
            throw new RuntimeException("计算文件MD5失败", e);
        }
        
        if (semanticTree == null) {
            try {
                if (useShardedStorage) {
                    this.semanticTree = shardedTreeStorage.loadTree();
                } else {
                    this.semanticTree = SemanticTree.loadFromFile(treeFilePath);
                }
                
                if (this.semanticTree != null) {
                    this.navigator = new Navigator(semanticTree, llmProvider, documentStore);
                    this.hybridNavigator = new HybridNavigator(semanticTree, keywordDictionary, llmProvider, documentStore);
                }
            } catch (IOException e) {
                logger.warn("加载语义树失败: {}", e.getMessage());
                this.semanticTree = new SemanticTree(null, new HashMap<>(), new HashMap<>());
            }
        }
        
        boolean alreadyExists = false;
        if (semanticTree != null && semanticTree.chunks() != null) {
            alreadyExists = semanticTree.chunks().values().stream()
                .anyMatch(chunk -> chunk.md5() != null && chunk.md5().equals(currentMd5));
        }
        
        if (alreadyExists) {
            logger.info("文件 {} 已存在（MD5: {}），跳过处理", filePath, currentMd5);
            return CompletableFuture.completedFuture(null);
        }
        
        Path copiedFile = createDocumentCopy(filePath);
        
        return DocumentProcessorFactory.getInstance().getProcessor(filePath.toString())
                .processDocument(filePath)
                .thenApply(chunks -> {
                    List<DocumentChunk> comprehendedChunks = new ArrayList<>();
                    for (DocumentChunk chunk : chunks) {
                        DocumentComprehendResult result = documentComprehender.comprehend(chunk);
                        DocumentChunk comprehendedChunk = new DocumentChunk(
                                chunk.id(),
                                chunk.content(),
                                result.summary(),
                                copiedFile.toString(),
                                currentMd5,
                                Map.of("original_file", filePath.toString())
                        );
                        comprehendedChunks.add(comprehendedChunk);
                    }
                    return comprehendedChunks;
                })
                .thenAccept(this::addDocumentChunks)
                .thenApply(ignored -> null);
    }
    
    /**
     * 从输入流添加文档（自动处理和分块）
     * @param inputStream 文档输入流
     * @param fileName 文件名
     * @return 处理结果的异步Future
     */
    public CompletableFuture<Void> addDocumentAsync(InputStream inputStream, String fileName) {
        return DocumentProcessorFactory.getInstance().getProcessor(fileName)
                .processDocument(inputStream, fileName)
                .thenApply(chunks -> {
                    List<DocumentChunk> comprehendedChunks = new ArrayList<>();
                    for (DocumentChunk chunk : chunks) {
                        DocumentComprehendResult result = documentComprehender.comprehend(chunk);
                        DocumentChunk comprehendedChunk = new DocumentChunk(
                                chunk.id(),
                                chunk.content(),
                                result.summary(),
                                null,
                                chunk.md5(),
                                Map.of("original_file", fileName)
                        );
                        comprehendedChunks.add(comprehendedChunk);
                    }
                    return comprehendedChunks;
                })
                .thenAccept(this::addDocumentChunks);
    }
    
    /**
     * 从内容添加文档（自动处理和分块）
     * @param content 文档内容
     * @param fileName 文件名
     * @return 处理结果的异步Future
     */
    public CompletableFuture<Void> addDocumentAsync(String content, String fileName) {
        return DocumentProcessorFactory.getInstance().getProcessor(fileName)
                .processDocument(content, fileName)
                .thenApply(chunks -> {
                    List<DocumentChunk> comprehendedChunks = new ArrayList<>();
                    for (DocumentChunk chunk : chunks) {
                        DocumentComprehendResult result = documentComprehender.comprehend(chunk);
                        DocumentChunk comprehendedChunk = new DocumentChunk(
                                chunk.id(),
                                chunk.content(),
                                result.summary(),
                                null,
                                chunk.md5(),
                                Map.of("original_file", fileName)
                        );
                        comprehendedChunks.add(comprehendedChunk);
                    }
                    return comprehendedChunks;
                })
                .thenAccept(this::addDocumentChunks);
    }
    
    /**
     * 从文件路径添加文档（同步，阻塞直到完成）
     * @param filePath 文件路径
     */
    public void addDocument(Path filePath) {
        String currentMd5;
        try {
            currentMd5 = MD5Util.calculateMD5(filePath);
        } catch (IOException e) {
            throw new RuntimeException("计算文件MD5失败", e);
        }
        
        boolean alreadyExists = false;
        if (semanticTree != null && semanticTree.chunks() != null) {
            alreadyExists = semanticTree.chunks().values().stream()
                .anyMatch(chunk -> chunk.md5() != null && chunk.md5().equals(currentMd5));
        }
        
        if (alreadyExists) {
            logger.info("文件 {} 已存在（MD5: {}），跳过处理", filePath, currentMd5);
            return;
        }
        
        Path copiedFile = createDocumentCopy(filePath);
        
        DocumentProcessor processor = DocumentProcessorFactory.getInstance().getProcessor(filePath.toString());
        processor.setConfig(currentConfig);
            
        try {
            List<DocumentChunk> chunks = processor.processDocument(filePath).get();
            
            List<DocumentChunk> filePathBasedChunks = chunks.stream()
                .map(chunk -> DocumentChunk.withFilePath(
                    chunk.id(),
                    chunk.summary(),
                    copiedFile.toString(),
                    chunk.md5(),
                    chunk.metadata()
                ))
                .collect(Collectors.toList());
                
            addDocumentChunks(filePathBasedChunks);
        } catch (Exception e) {
            logger.error("添加文档 {} 失败 ", filePath, e);
            throw new RuntimeException("添加文档失败", e);
        }
    }
    
    /**
     * 创建文档副本
     */
    private Path createDocumentCopy(Path originalFile) {
        try {
            String fileName = originalFile.getFileName().toString();
            String timestamp = String.valueOf(System.currentTimeMillis());
            String copiedFileName = timestamp + "_" + fileName;
            Path copiedFile = Paths.get(documentsDir, copiedFileName);
            
            Files.copy(originalFile, copiedFile, StandardCopyOption.REPLACE_EXISTING);
            logger.info("已创建文档副本: {} -> {}", originalFile, copiedFile);
            
            return copiedFile;
        } catch (IOException e) {
            throw new RuntimeException("创建文档副本失败", e);
        }
    }
    
    /**
     * 从输入流添加文档（同步，阻塞直到完成）
     * @param inputStream 文档输入流
     * @param fileName 文件名
     */
    public void addDocument(InputStream inputStream, String fileName) {
        DocumentProcessor processor = DocumentProcessorFactory.getInstance().getProcessor(fileName);
        processor.setConfig(currentConfig);
            
        try {
            List<DocumentChunk> chunks = processor.processDocument(inputStream, fileName).get();
            addDocumentChunks(chunks);
        } catch (Exception e) {
            throw new RuntimeException("添加文档失败", e);
        }
    }
    
    /**
     * 从内容添加文档（同步，阻塞直到完成）
     * @param content 文档内容
     * @param fileName 文件名
     */
    public void addDocument(String content, String fileName) {
        DocumentProcessor processor = DocumentProcessorFactory.getInstance().getProcessor(fileName);
        processor.setConfig(currentConfig);
            
        try {
            List<DocumentChunk> chunks = processor.processDocument(content, fileName).get();
            addDocumentChunks(chunks);
        } catch (Exception e) {
            throw new RuntimeException("添加文档失败", e);
        }
    }
    
    /**
     * 从文件路径添加多个文档（自动处理和分块）
     * @param filePaths 文件路径列表
     * @return 处理结果的异步Future
     */
    public CompletableFuture<Void> addDocumentsAsync(List<Path> filePaths) {
        List<Path> filesToProcess = new ArrayList<>();
        Map<Path, String> md5Map = new HashMap<>();
        
        if (semanticTree != null && semanticTree.chunks() != null) {
            Set<String> existingMd5s = semanticTree.chunks().values().stream()
                .filter(chunk -> chunk.md5() != null)
                .map(DocumentChunk::md5)
                .collect(Collectors.toSet());
            
            for (Path filePath : filePaths) {
                try {
                    String md5 = cn.nexon.zerovector.core.util.MD5Util.calculateMD5(filePath);
                    md5Map.put(filePath, md5);
                    
                    if (!existingMd5s.contains(md5)) {
                        filesToProcess.add(filePath);
                    } else {
                        logger.info("文件 {} 已存在（MD5: {}），跳过处理", filePath, md5);
                    }
                } catch (IOException e) {
                    logger.error("计算文件MD5失败: {}, 错误: {}", filePath, e.getMessage());
                    filesToProcess.add(filePath);
                }
            }
        } else {
            filesToProcess.addAll(filePaths);
        }
        
        if (filesToProcess.isEmpty()) {
            logger.info("所有文件都已存在，无需处理");
            return CompletableFuture.completedFuture(null);
        }
        
        List<CompletableFuture<List<DocumentChunk>>> futures = filesToProcess.stream()
            .map(filePath -> {
                DocumentProcessor processor = DocumentProcessorFactory.getInstance().getProcessor(filePath.toString());
                processor.setConfig(currentConfig);
                    
                return processor.processDocument(filePath)
                    .thenApply(chunks -> {
                        String md5 = md5Map.get(filePath);
                        return chunks.stream()
                            .map(chunk -> DocumentChunk.withFilePath(
                                chunk.id(),
                                chunk.summary(),
                                createDocumentCopy(filePath).toString(),
                                md5,
                                chunk.metadata()
                            ))
                            .collect(Collectors.toList());
                    });
            })
            .toList();
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .toList())
            .thenAccept(this::addDocumentChunks);
    }
    
    /**
     * 添加多个文档（自动分块）
     * @param documents 文档列表，每个元素包含[content, fileName]
     */
    public void addDocumentsFromContent(List<DocumentInfo> documents) {
        List<DocumentChunk> allChunks = new ArrayList<>();
        
        for (DocumentInfo doc : documents) {
            try {
                List<DocumentChunk> chunks = documentProcessor.processDocument(doc.content(), doc.fileName()).get();
                allChunks.addAll(chunks);
            } catch (Exception e) {
                throw new RuntimeException("处理文档失败: " + doc.fileName(), e);
            }
        }
        
        addDocumentChunks(allChunks);
    }
    
    /**
     * 从文件路径构建语义树（自动处理和分块）
     * @param filePaths 文件路径列表
     * @return 处理结果的异步Future
     */
    public CompletableFuture<Void> buildTreeFromDocumentsAsync(List<Path> filePaths) {
        List<CompletableFuture<List<DocumentChunk>>> futures = filePaths.stream()
            .map(documentProcessor::processDocument)
            .toList();
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .toList())
            .thenAccept(this::buildTree);
    }
    
    /**
     * 构建语义树（从文档内容，自动分块）
     * @param documents 文档列表，每个元素包含[content, fileName]
     */
    public void buildTreeFromDocuments(List<DocumentInfo> documents) {
        List<DocumentChunk> allChunks = new ArrayList<>();
        
        for (DocumentInfo doc : documents) {
            try {
                List<DocumentChunk> chunks = documentProcessor.processDocument(doc.content(), doc.fileName()).get();
                allChunks.addAll(chunks);
            } catch (Exception e) {
                throw new RuntimeException("处理文档失败: " + doc.fileName(), e);
            }
        }
        
        buildTree(allChunks);
    }
    
    /**
     * 文档信息记录
     */
    public record DocumentInfo(String content, String fileName) {}
    
    /**
     * 设置文档处理配置
     * @param config 处理配置
     */
    public void setDocumentProcessingConfig(DocumentProcessor.ProcessingConfig config) {
        this.currentConfig = config;
        documentProcessor.setConfig(config);
    }
    
    /**
     * 获取文档处理器
     * @return 文档处理器实例
     */
    public DocumentProcessor getDocumentProcessor() {
        return documentProcessor;
    }
    
    /**
     * 获取支持的文档格式
     * @return 支持的文件扩展名列表
     */
    public List<String> getSupportedFormats() {
        return documentProcessor.getSupportedFormats();
    }

    /**
     * 执行查询导航
     */
    public NavigationResult navigate(String query) {
        if (semanticTree == null) {
            throw new IllegalStateException("Semantic tree not built yet");
        }
        
        if (hybridNavigator != null) {
            return hybridNavigator.navigate(query);
        } else if (navigator != null) {
            return navigator.navigate(query);
        } else {
            throw new IllegalStateException("No navigator available");
        }
    }
    
    /**
     * 获取语义树
     */
    public SemanticTree getSemanticTree() {
        return semanticTree;
    }
    
    /**
     * 检查语义树是否已加载
     */
    public boolean isTreeLoaded() {
        return semanticTree != null;
    }
    
    /**
     * 检查是否使用分片存储
     * @return true表示使用分片存储，false表示使用非分片存储
     */
    public boolean isUsingShardedStorage() {
        return useShardedStorage;
    }
    
    /**
     * 保存语义树
     */
    public void saveTree() throws IOException {
        if (semanticTree != null) {
            if (useShardedStorage) {
                shardedTreeStorage.saveTree(semanticTree);
            } else {
                semanticTree.saveToFile(treeFilePath);
            }
        }
        
        keywordDictionary.saveToFile(dictionaryFilePath);
        logger.info("已保存关键词字典到文件: {}", dictionaryFilePath);
    }
    
    /**
     * 重置导航器
     */
    public void resetNavigator() {
        if (navigator != null) {
            navigator.reset();
        }
    }
    
    /**
     * 关闭管理器
     */
    public void close() throws IOException {
        saveTree();
        
        if (documentStore != null) {
            documentStore.close();
        }
        
        if (shardedTreeStorage != null) {
            shardedTreeStorage.close();
        }
    }
}
