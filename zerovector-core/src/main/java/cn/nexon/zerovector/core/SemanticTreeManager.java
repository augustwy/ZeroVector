package cn.nexon.zerovector.core;

import cn.nexon.zerovector.core.ai.LLMProvider;
import cn.nexon.zerovector.core.ai.LLMUsageStats;
import cn.nexon.zerovector.core.config.ConcurrencyProperties;
import cn.nexon.zerovector.core.document.comprehend.DocumentComprehender;
import cn.nexon.zerovector.core.document.comprehend.DocumentComprehendResult;
import cn.nexon.zerovector.core.exception.NavigationException;
import cn.nexon.zerovector.core.exception.StorageException;
import cn.nexon.zerovector.core.hook.HookContext;
import cn.nexon.zerovector.core.hook.HookExecutor;
import cn.nexon.zerovector.core.hook.HookType;
import cn.nexon.zerovector.core.hook.DefaultHookExecutor;
import cn.nexon.zerovector.core.index.KeywordDictionary;
import cn.nexon.zerovector.core.model.Document;
import cn.nexon.zerovector.core.model.DocumentChunk;
import cn.nexon.zerovector.core.model.DocumentUploadResult;
import cn.nexon.zerovector.core.model.NavigationResult;
import cn.nexon.zerovector.core.model.SemanticTree;
import cn.nexon.zerovector.core.navigator.HybridNavigator;
import cn.nexon.zerovector.core.storage.MMapDocumentStore;
import cn.nexon.zerovector.core.storage.ShardedTreeStorage;
import cn.nexon.zerovector.core.tree.Navigator;
import cn.nexon.zerovector.core.tree.TreeBuilder;
import cn.nexon.zerovector.core.util.FileUtils;
import cn.nexon.zerovector.core.util.MD5Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 语义树管理器
 * 负责语义树的构建、更新和导航
 */
public class SemanticTreeManager {
    private static final Logger logger = LoggerFactory.getLogger(SemanticTreeManager.class);

    private final LLMProvider llmProvider;
    private final DocumentComprehender documentComprehender;
    private final HookExecutor hookExecutor;
    private KeywordDictionary keywordDictionary;
    private final Path storagePath;
    private final String fileBasePath;
    private final String treeFilePath;
    private final String treeStorageDir;
    private final String dictionaryFilePath;
    private final String documentsDir;
    private final boolean useShardedStorage;
    private final ConcurrencyProperties concurrencyProperties;
    private MMapDocumentStore documentStore;
    private ShardedTreeStorage shardedTreeStorage;
    private SemanticTree semanticTree;
    private Navigator navigator;
    private HybridNavigator hybridNavigator;

    public SemanticTreeManager(LLMProvider llmProvider, DocumentComprehender documentComprehender, Path storagePath, boolean useShardedStorage, ConcurrencyProperties concurrencyProperties) {
        this(llmProvider, documentComprehender, storagePath, useShardedStorage, concurrencyProperties, new DefaultHookExecutor());
    }

    public SemanticTreeManager(LLMProvider llmProvider, DocumentComprehender documentComprehender, Path storagePath, boolean useShardedStorage, ConcurrencyProperties concurrencyProperties, HookExecutor hookExecutor) {
        this.llmProvider = llmProvider;
        this.documentComprehender = documentComprehender;
        this.hookExecutor = hookExecutor != null ? hookExecutor : new DefaultHookExecutor();
        this.keywordDictionary = new KeywordDictionary();
        this.storagePath = storagePath;
        this.fileBasePath = Paths.get(storagePath.toString(), "zerovector_storage").toString();
        this.treeFilePath = this.fileBasePath + ".tree";
        this.treeStorageDir = this.fileBasePath + "_shards";
        this.dictionaryFilePath = this.fileBasePath + ".dict";
        this.documentsDir = this.fileBasePath + "_docs";
        this.useShardedStorage = useShardedStorage;
        this.concurrencyProperties = concurrencyProperties;
    }

    public void initialize() throws IOException {
        try {
            Path storagePathObj = Paths.get(storagePath.toString());
            
            if (!Files.exists(storagePathObj)) {
                Files.createDirectories(storagePathObj);
                logger.debug("创建存储目录: {}", storagePath);
            }
            
            String documentStoreFilePath = this.fileBasePath + ".data";
            this.documentStore = MMapDocumentStore.open(documentStoreFilePath);
            
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
                logger.debug("已加载已保存的关键词字典");
            } catch (IOException e) {
                logger.warn("加载关键词字典失败，将使用新字典: {}", e.getMessage());
            }
            
            if (this.semanticTree != null) {
                this.navigator = new Navigator(semanticTree, llmProvider, documentStore, hookExecutor);
                this.hybridNavigator = new HybridNavigator(semanticTree, keywordDictionary, llmProvider, documentStore, hookExecutor);
                logger.debug("已加载已保存的语义树");
            }
        } catch (IOException e) {
            throw new StorageException(storagePath.toString(), "initialize", e);
        }
    }

    /**
     * 构建语义树
     *
     * @param documents 文档列表
     */
    public void buildTree(List<Document> documents) {
        long startTime = System.currentTimeMillis();
        LLMUsageStats totalStats = new LLMUsageStats();
        
        hookExecutor.executeHooks(HookType.TREE_BUILD_START, 
            HookContext.builder(HookType.TREE_BUILD_START)
                .data("documentCount", documents.size())
        );
        
        try {
            DocumentProcessingResult processingResult = processDocuments(documents);
            
            for (DocumentComprehendResult result : processingResult.comprehendResultMap().values()) {
                totalStats.merge(result.llmUsageStats());
            }
            
            TreeBuilder.TreeBuildResult buildResult = buildTreeInternal(processingResult.comprehendResultMap(), processingResult.chunkMap());
            this.semanticTree = buildResult.tree();
            totalStats.merge(buildResult.llmUsageStats());
            
            this.navigator = new Navigator(semanticTree, llmProvider, documentStore, hookExecutor);
            this.hybridNavigator = new HybridNavigator(semanticTree, keywordDictionary, llmProvider, documentStore, hookExecutor);
            
            long duration = System.currentTimeMillis() - startTime;
            hookExecutor.executeHooks(HookType.TREE_BUILD_END,
                HookContext.builder(HookType.TREE_BUILD_END)
                    .data("documentCount", documents.size())
                    .data("nodeCount", semanticTree.nodes().size())
                    .data("llmUsageStats", totalStats)
                    .durationMillis(duration)
            );
            
            try {
                saveTree();
            } catch (IOException e) {
                throw new StorageException(treeFilePath, "saveTree", e);
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            hookExecutor.executeHooks(HookType.TREE_BUILD_ERROR,
                HookContext.builder(HookType.TREE_BUILD_ERROR)
                    .data("documentCount", documents.size())
                    .data("llmUsageStats", totalStats)
                    .durationMillis(duration)
                    .error(e)
            );
            throw e;
        }
    }
    
    private DocumentProcessingResult processDocuments(List<Document> documents) {
        Map<String, DocumentComprehendResult> comprehendResultMap = new HashMap<>();
        Map<String, DocumentChunk> chunkMap = new HashMap<>();
        
        for (Document document : documents) {
            logger.debug("  - {}: {}", document.id(), document.title());
            DocumentComprehendResult result = documentComprehender.comprehend(document);
            comprehendResultMap.put(document.id(), result);
            
            DocumentChunk chunk = createDocumentChunk(document, result);
            chunkMap.put(document.id(), chunk);
        }
        
        return new DocumentProcessingResult(comprehendResultMap, chunkMap);
    }
    
    private DocumentChunk createDocumentChunk(Document document, DocumentComprehendResult result) {
        return new DocumentChunk(
            document.id(),
            null,
            result.summary(),
            document.filePath(),
            document.md5(),
            document.metadata()
        );
    }
    
    private record DocumentProcessingResult(
        Map<String, DocumentComprehendResult> comprehendResultMap,
        Map<String, DocumentChunk> chunkMap
    ) {}
    
    private TreeBuilder.TreeBuildResult buildTreeInternal(Map<String, DocumentComprehendResult> comprehendResultMap, Map<String, DocumentChunk> chunkMap) {
        TreeBuilder builder = new TreeBuilder(llmProvider, keywordDictionary, documentStore, concurrencyProperties, hookExecutor);
        
        if (this.semanticTree == null || this.semanticTree.rootNode() == null) {
            return builder.build(comprehendResultMap, chunkMap);
        } else {
            return builder.updateTree(this.semanticTree, new ArrayList<>(comprehendResultMap.values()), chunkMap);
        }
    }

    /**
     * 添加单个文档
     *
     * @param filePath 文件路径
     * @return 文档上传结果，包含 LLM 调用统计
     */
    public DocumentUploadResult addDocument(Path filePath) {
        long startTime = System.currentTimeMillis();
        LLMUsageStats totalStats = new LLMUsageStats();
        
        hookExecutor.executeHooks(HookType.DOCUMENT_UPLOAD_START,
            HookContext.builder(HookType.DOCUMENT_UPLOAD_START)
                .data("filePath", filePath.toString())
        );
        
        try {
            String md5 = calculateFileMD5(filePath);
            
            if (isDocumentAlreadyExists(md5)) {
                long duration = System.currentTimeMillis() - startTime;
                hookExecutor.executeHooks(HookType.DOCUMENT_UPLOAD_END,
                    HookContext.builder(HookType.DOCUMENT_UPLOAD_END)
                        .data("filePath", filePath.toString())
                        .data("skipped", true)
                        .data("reason", "Document already exists")
                        .data("llmUsageStats", totalStats)
                        .durationMillis(duration)
                );
                return DocumentUploadResult.skipped(null, "文档已存在，跳过处理");
            }
            
            Path copiedFile = createDocumentCopy(filePath);
            Document document = createDocumentFromFile(filePath, copiedFile, md5);
            
            DocumentProcessingResult processingResult = processSingleDocument(document);
            totalStats.merge(processingResult.comprehendResultMap().get(document.id()).llmUsageStats());
            
            TreeBuilder.TreeBuildResult buildResult = updateSemanticTreeFromDocuments(processingResult.comprehendResultMap(), processingResult.chunkMap());
            totalStats.merge(buildResult.llmUsageStats());

            long duration = System.currentTimeMillis() - startTime;
            hookExecutor.executeHooks(HookType.DOCUMENT_UPLOAD_END,
                HookContext.builder(HookType.DOCUMENT_UPLOAD_END)
                    .data("filePath", filePath.toString())
                    .data("documentTitle", document.title())
                    .data("documentCount", semanticTree.chunks().size())
                    .data("llmUsageStats", totalStats)
                    .durationMillis(duration)
            );
            
            try {
                saveTree();
            } catch (IOException e) {
                throw new StorageException(treeFilePath, "saveTree", e);
            }
            
            return DocumentUploadResult.success(document.id(), document.title(), totalStats);
        } catch (StorageException e) {
            long duration = System.currentTimeMillis() - startTime;
            hookExecutor.executeHooks(HookType.DOCUMENT_UPLOAD_ERROR,
                HookContext.builder(HookType.DOCUMENT_UPLOAD_ERROR)
                    .data("filePath", filePath.toString())
                    .data("llmUsageStats", totalStats)
                    .durationMillis(duration)
                    .error(e)
            );
            throw e;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            hookExecutor.executeHooks(HookType.DOCUMENT_UPLOAD_ERROR,
                HookContext.builder(HookType.DOCUMENT_UPLOAD_ERROR)
                    .data("filePath", filePath.toString())
                    .data("llmUsageStats", totalStats)
                    .durationMillis(duration)
                    .error(e)
            );
            throw new StorageException(filePath.toString(), "addDocument", e);
        }
    }
    
    private String calculateFileMD5(Path filePath) throws IOException {
        return MD5Util.calculateMD5(filePath);
    }
    
    private boolean isDocumentAlreadyExists(String md5) {
        if (semanticTree != null && semanticTree.chunks() != null) {
            return semanticTree.chunks().values().stream()
                .anyMatch(chunk -> chunk.md5() != null && chunk.md5().equals(md5));
        }
        return false;
    }
    
    private Document createDocumentFromFile(Path originalPath, Path copiedPath, String md5) {
        String fileName = FileUtils.getFileName(originalPath);
        String docId = "doc_" + System.currentTimeMillis();
        
        return Document.fromFile(docId, fileName, copiedPath.toString(), md5, 
            Map.of("original_file", originalPath.toString()));
    }
    
    private DocumentProcessingResult processSingleDocument(Document document) {
        DocumentComprehendResult result = documentComprehender.comprehend(document);
        
        Map<String, DocumentComprehendResult> comprehendResultMap = new HashMap<>();
        comprehendResultMap.put(document.id(), result);
        
        DocumentChunk chunk = createDocumentChunk(document, result);
        
        Map<String, DocumentChunk> chunkMap = new HashMap<>();
        chunkMap.put(document.id(), chunk);
        
        return new DocumentProcessingResult(comprehendResultMap, chunkMap);
    }

    public CompletableFuture<Void> addDocumentAsync(Path filePath) {
        return CompletableFuture.runAsync(() -> addDocument(filePath));
    }

    /**
     * 批量添加文档
     *
     * @param filePaths 文件路径列表
     * @return 每个文档的上传结果列表，包含 LLM 调用统计
     */
    public List<DocumentUploadResult> addDocuments(List<Path> filePaths) {
        List<DocumentUploadResult> results = new ArrayList<>();
        
        for (Path filePath : filePaths) {
            try {
                DocumentUploadResult result = addDocument(filePath);
                results.add(result);
            } catch (Exception e) {
                logger.error("处理文件 {} 失败: {}", filePath, e.getMessage());
                results.add(DocumentUploadResult.failure(null, "处理失败: " + e.getMessage()));
            }
        }
        
        return results;
    }

    public CompletableFuture<List<DocumentUploadResult>> addDocumentsAsync(List<Path> filePaths) {
        return CompletableFuture.supplyAsync(() -> addDocuments(filePaths));
    }

    private Path createDocumentCopy(Path originalFile) {
        try {
            Path targetDir = Paths.get(documentsDir);
            FileUtils.createDirectories(targetDir);
            return FileUtils.copyFileWithTimestamp(originalFile, targetDir);
        } catch (IOException e) {
            throw new StorageException(originalFile.toString(), "createDocumentCopy", e);
        }
    }
    
    private TreeBuilder.TreeBuildResult updateSemanticTreeFromDocuments(Map<String, DocumentComprehendResult> comprehendResultMap, Map<String, DocumentChunk> chunkMap) {
        TreeBuilder.TreeBuildResult result = buildTreeInternal(comprehendResultMap, chunkMap);
        this.semanticTree = result.tree();
        updateNavigators();
        return result;
    }
    
    private void updateNavigators() {
        this.navigator = new Navigator(semanticTree, llmProvider, documentStore, hookExecutor);
        this.hybridNavigator = new HybridNavigator(semanticTree, keywordDictionary, llmProvider, documentStore, hookExecutor);
    }

    /**
     * 执行导航查询
     *
     * @param query 查询字符串
     * @return 导航结果，包含 LLM 调用统计数据
     */
    public NavigationResult navigate(String query) {
        long startTime = System.currentTimeMillis();
        LLMUsageStats stats = new LLMUsageStats();
        
        hookExecutor.executeHooks(HookType.NAVIGATION_START,
            HookContext.builder(HookType.NAVIGATION_START)
                .data("query", query)
        );
        
        try {
            if (semanticTree == null) {
                throw new NavigationException(query, null, NavigationException.ERROR_CODE_TREE_NOT_INITIALIZED, 
                    "Semantic tree not built yet");
            }
            
            NavigationResult result;
            if (hybridNavigator != null) {
                result = hybridNavigator.navigate(query);
            } else if (navigator != null) {
                result = navigator.navigate(query);
            } else {
                throw new NavigationException(query, null, NavigationException.ERROR_CODE_NO_NAVIGATOR, 
                    "No navigator available");
            }
            
            stats.merge(result.llmUsageStats());
            
            long duration = System.currentTimeMillis() - startTime;
            hookExecutor.executeHooks(HookType.NAVIGATION_END,
                HookContext.builder(HookType.NAVIGATION_END)
                    .data("query", query)
                    .data("resultCount", result.documents().size())
                    .data("llmUsageStats", stats)
                    .durationMillis(duration)
            );
            
            return result;
        } catch (NavigationException e) {
            long duration = System.currentTimeMillis() - startTime;
            hookExecutor.executeHooks(HookType.NAVIGATION_ERROR,
                HookContext.builder(HookType.NAVIGATION_ERROR)
                    .data("query", query)
                    .data("llmUsageStats", stats)
                    .durationMillis(duration)
                    .error(e)
            );
            throw e;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            NavigationException navException = new NavigationException(query, semanticTree != null ? semanticTree.rootNode().id() : null, 
                NavigationException.ERROR_CODE_LLM_DECISION_FAILED, "Navigation failed", e);
            hookExecutor.executeHooks(HookType.NAVIGATION_ERROR,
                HookContext.builder(HookType.NAVIGATION_ERROR)
                    .data("query", query)
                    .data("llmUsageStats", stats)
                    .durationMillis(duration)
                    .error(navException)
            );
            throw navException;
        }
    }

    public SemanticTree getSemanticTree() {
        return semanticTree;
    }

    public boolean isTreeLoaded() {
        return semanticTree != null;
    }

    public boolean isUsingShardedStorage() {
        return useShardedStorage;
    }

    public void saveTree() throws IOException {
        if (semanticTree != null) {
            if (useShardedStorage) {
                shardedTreeStorage.saveTree(semanticTree);
            } else {
                semanticTree.saveToFile(treeFilePath);
            }
        }

        keywordDictionary.saveToFile(dictionaryFilePath);
        logger.debug("已保存关键词字典到文件: {}", dictionaryFilePath);
    }

    public void resetNavigator() {
        if (navigator != null) {
            navigator.reset();
        }
    }

    public void close() throws IOException {
        try {
            saveTree();
        } catch (IOException e) {
            throw new StorageException(storagePath.toString(), "close", e);
        }
        
        if (documentStore != null) {
            try {
                documentStore.close();
            } catch (IOException e) {
                throw new StorageException(storagePath.toString(), "close", e);
            }
        }
        
        if (shardedTreeStorage != null) {
            try {
                shardedTreeStorage.close();
            } catch (IOException e) {
                throw new StorageException(storagePath.toString(), "close", e);
            }
        }
    }
}
