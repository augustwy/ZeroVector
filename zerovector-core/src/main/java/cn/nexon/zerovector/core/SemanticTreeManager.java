package cn.nexon.zerovector.core;

import cn.nexon.zerovector.core.ai.LLMProvider;
import cn.nexon.zerovector.core.ai.LLMUsageStats;
import cn.nexon.zerovector.core.config.ConcurrencyProperties;
import cn.nexon.zerovector.core.document.comprehend.DocumentComprehender;
import cn.nexon.zerovector.core.document.comprehend.DocumentComprehendResult;
import cn.nexon.zerovector.core.exception.NavigationException;
import cn.nexon.zerovector.core.exception.StorageException;
import cn.nexon.zerovector.core.hook.DefaultHookExecutor;
import cn.nexon.zerovector.core.hook.HookContext;
import cn.nexon.zerovector.core.hook.HookExecutor;
import cn.nexon.zerovector.core.hook.HookType;
import cn.nexon.zerovector.core.index.KeywordDictionary;
import cn.nexon.zerovector.core.model.Document;
import cn.nexon.zerovector.core.model.DocumentChunk;
import cn.nexon.zerovector.core.model.DocumentUploadResult;
import cn.nexon.zerovector.core.model.NavigationResult;
import cn.nexon.zerovector.core.model.SemanticTree;
import cn.nexon.zerovector.core.navigator.HybridNavigator;
import cn.nexon.zerovector.core.storage.MMapDocumentStore;
import cn.nexon.zerovector.core.storage.ShardedTreeStorage;
import cn.nexon.zerovector.core.storage.config.ChunkStorageConfig;
import cn.nexon.zerovector.core.storage.config.DictionaryStorageConfig;
import cn.nexon.zerovector.core.storage.config.DocumentCopyStorageConfig;
import cn.nexon.zerovector.core.storage.local.LocalFileChunkStorage;
import cn.nexon.zerovector.core.storage.local.LocalFileDictionaryStorage;
import cn.nexon.zerovector.core.storage.local.LocalFileDocumentCopyStorage;
import cn.nexon.zerovector.core.storage.spi.ChunkStorage;
import cn.nexon.zerovector.core.storage.spi.DictionaryStorage;
import cn.nexon.zerovector.core.storage.spi.DocumentCopyStorage;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 语义树管理器
 * 
 * <p>负责语义树的构建、更新和导航
 * 
 * <p>支持通过存储接口扩展不同的存储后端
 */
public class SemanticTreeManager {
    private static final Logger logger = LoggerFactory.getLogger(SemanticTreeManager.class);

    private final LLMProvider llmProvider;
    private final DocumentComprehender documentComprehender;
    private final HookExecutor hookExecutor;
    private final ConcurrencyProperties concurrencyProperties;

    private final ChunkStorage chunkStorage;
    private final DictionaryStorage dictionaryStorage;
    private final DocumentCopyStorage documentCopyStorage;

    private final Path storagePath;
    private final String fileBasePath;
    private final String treeFilePath;
    private final String treeStorageDir;
    private final boolean useShardedStorage;

    private MMapDocumentStore documentStore;
    private ShardedTreeStorage shardedTreeStorage;
    private SemanticTree semanticTree;
    private Navigator navigator;
    private HybridNavigator hybridNavigator;

    /**
     * 创建语义树管理器（使用默认本地存储）
     *
     * @param llmProvider LLM 提供者
     * @param documentComprehender 文档理解器
     * @param storagePath 存储路径
     * @param useShardedStorage 是否使用分片存储
     * @param concurrencyProperties 并发配置
     */
    public SemanticTreeManager(LLMProvider llmProvider, DocumentComprehender documentComprehender, 
            Path storagePath, boolean useShardedStorage, ConcurrencyProperties concurrencyProperties) {
        this(llmProvider, documentComprehender, storagePath, useShardedStorage, concurrencyProperties, 
            new DefaultHookExecutor());
    }

    /**
     * 创建语义树管理器（使用默认本地存储，带 Hook 执行器）
     *
     * @param llmProvider LLM 提供者
     * @param documentComprehender 文档理解器
     * @param storagePath 存储路径
     * @param useShardedStorage 是否使用分片存储
     * @param concurrencyProperties 并发配置
     * @param hookExecutor Hook 执行器
     */
    public SemanticTreeManager(LLMProvider llmProvider, DocumentComprehender documentComprehender, 
            Path storagePath, boolean useShardedStorage, ConcurrencyProperties concurrencyProperties,
            HookExecutor hookExecutor) {
        this.llmProvider = llmProvider;
        this.documentComprehender = documentComprehender;
        this.hookExecutor = hookExecutor != null ? hookExecutor : new DefaultHookExecutor();
        this.concurrencyProperties = concurrencyProperties;
        this.storagePath = storagePath;
        this.useShardedStorage = useShardedStorage;

        this.fileBasePath = Paths.get(storagePath.toString(), "zerovector_storage").toString();
        this.treeFilePath = this.fileBasePath + ".tree";
        this.treeStorageDir = this.fileBasePath + "_shards";

        ChunkStorageConfig chunkConfig = new ChunkStorageConfig(this.fileBasePath);
        chunkConfig.setSharded(useShardedStorage);
        this.chunkStorage = createDefaultChunkStorage(chunkConfig);

        DictionaryStorageConfig dictConfig = new DictionaryStorageConfig(this.fileBasePath + ".dict");
        this.dictionaryStorage = createDefaultDictionaryStorage(dictConfig);

        DocumentCopyStorageConfig docConfig = new DocumentCopyStorageConfig(this.fileBasePath + "_docs");
        this.documentCopyStorage = createDefaultDocumentCopyStorage(docConfig);
    }

    /**
     * 创建语义树管理器（使用自定义存储实现）
     *
     * @param llmProvider LLM 提供者
     * @param documentComprehender 文档理解器
     * @param chunkStorage 文档分片存储
     * @param dictionaryStorage 字典存储
     * @param documentCopyStorage 文件副本存储
     * @param storagePath 存储路径
     * @param treeFilePath 语义树文件路径
     * @param useShardedStorage 是否使用分片存储
     * @param concurrencyProperties 并发配置
     * @param hookExecutor Hook 执行器
     */
    public SemanticTreeManager(LLMProvider llmProvider, DocumentComprehender documentComprehender,
            ChunkStorage chunkStorage, DictionaryStorage dictionaryStorage, DocumentCopyStorage documentCopyStorage,
            Path storagePath, String treeFilePath, boolean useShardedStorage,
            ConcurrencyProperties concurrencyProperties, HookExecutor hookExecutor) {
        this.llmProvider = llmProvider;
        this.documentComprehender = documentComprehender;
        this.chunkStorage = chunkStorage;
        this.dictionaryStorage = dictionaryStorage;
        this.documentCopyStorage = documentCopyStorage;
        this.hookExecutor = hookExecutor != null ? hookExecutor : new DefaultHookExecutor();
        this.concurrencyProperties = concurrencyProperties;
        this.storagePath = storagePath;
        this.treeFilePath = treeFilePath;
        this.treeStorageDir = treeFilePath + "_shards";
        this.useShardedStorage = useShardedStorage;
        this.fileBasePath = treeFilePath.replace(".tree", "");
    }

    private ChunkStorage createDefaultChunkStorage(ChunkStorageConfig config) {
        LocalFileChunkStorage storage = new LocalFileChunkStorage();
        try {
            storage.initialize(config);
        } catch (StorageException e) {
            throw new RuntimeException("初始化 ChunkStorage 失败", e);
        }
        return storage;
    }

    private DictionaryStorage createDefaultDictionaryStorage(DictionaryStorageConfig config) {
        LocalFileDictionaryStorage storage = new LocalFileDictionaryStorage();
        try {
            storage.initialize(config);
        } catch (StorageException e) {
            throw new RuntimeException("初始化 DictionaryStorage 失败", e);
        }
        return storage;
    }

    private DocumentCopyStorage createDefaultDocumentCopyStorage(DocumentCopyStorageConfig config) {
        LocalFileDocumentCopyStorage storage = new LocalFileDocumentCopyStorage();
        try {
            storage.initialize(config);
        } catch (StorageException e) {
            throw new RuntimeException("初始化 DocumentCopyStorage 失败", e);
        }
        return storage;
    }

    /**
     * 初始化语义树管理器
     *
     * @throws IOException 初始化失败
     */
    public void initialize() throws IOException {
        try {
            Path storagePathObj = Paths.get(storagePath.toString());

            if (!Files.exists(storagePathObj)) {
                Files.createDirectories(storagePathObj);
                logger.debug("创建存储目录: {}", storagePath);
            }

            if (chunkStorage instanceof LocalFileChunkStorage localChunkStorage) {
                this.documentStore = localChunkStorage.getMMapStore();
            }

            if (useShardedStorage) {
                this.shardedTreeStorage = new ShardedTreeStorage(treeStorageDir);
            }

            if (useShardedStorage) {
                this.semanticTree = shardedTreeStorage.loadTree();
            } else {
                this.semanticTree = SemanticTree.loadFromFile(treeFilePath);
            }

            if (this.semanticTree != null) {
                this.navigator = new Navigator(semanticTree, llmProvider, documentStore, hookExecutor);
                this.hybridNavigator = new HybridNavigator(semanticTree, getKeywordDictionary(), 
                    llmProvider, documentStore, hookExecutor);
                logger.debug("已加载已保存的语义树");
            }
        } catch (IOException e) {
            throw new StorageException(storagePath.toString(), "initialize", e);
        }
    }

    /**
     * 获取关键词字典
     *
     * @return 关键词字典实例
     */
    public KeywordDictionary getKeywordDictionary() {
        if (dictionaryStorage instanceof LocalFileDictionaryStorage localStorage) {
            return localStorage.getDictionary();
        }
        throw new UnsupportedOperationException("当前字典存储实现不支持直接访问 KeywordDictionary");
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
            this.hybridNavigator = new HybridNavigator(semanticTree, getKeywordDictionary(), llmProvider, documentStore, hookExecutor);

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
        Map<String, DocumentComprehendResult> comprehendResultMap = new LinkedHashMap<>();
        Map<String, DocumentChunk> chunkMap = new LinkedHashMap<>();

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
        TreeBuilder builder = new TreeBuilder(llmProvider, getKeywordDictionary(), documentStore, concurrencyProperties, hookExecutor);

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

        Map<String, DocumentComprehendResult> comprehendResultMap = new LinkedHashMap<>();
        comprehendResultMap.put(document.id(), result);

        DocumentChunk chunk = createDocumentChunk(document, result);

        Map<String, DocumentChunk> chunkMap = new LinkedHashMap<>();
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
            String fileId = documentCopyStorage.saveCopy(originalFile);
            Optional<Path> copiedPath = documentCopyStorage.getFilePath(fileId);
            if (copiedPath.isPresent()) {
                return copiedPath.get();
            }
            throw new StorageException(fileId, "createDocumentCopy", 
                new IllegalStateException("无法获取复制后的文件路径"));
        } catch (StorageException e) {
            throw e;
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
        this.hybridNavigator = new HybridNavigator(semanticTree, getKeywordDictionary(), llmProvider, documentStore, hookExecutor);
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

    public ChunkStorage getChunkStorage() {
        return chunkStorage;
    }

    public DictionaryStorage getDictionaryStorage() {
        return dictionaryStorage;
    }

    public DocumentCopyStorage getDocumentCopyStorage() {
        return documentCopyStorage;
    }

    public void saveTree() throws IOException {
        if (semanticTree != null) {
            if (useShardedStorage) {
                shardedTreeStorage.saveTree(semanticTree);
            } else {
                semanticTree.saveToFile(treeFilePath);
            }
        }

        dictionaryStorage.persist();
        logger.debug("已保存关键词字典");
    }

    public void resetNavigator() {
        if (navigator != null) {
            navigator.reset();
        }
    }

    public void close() throws Exception {
        try {
            saveTree();
        } catch (IOException e) {
            throw new StorageException(storagePath.toString(), "close", e);
        }

        if (chunkStorage != null) {
            chunkStorage.close();
        }

        if (dictionaryStorage != null) {
            dictionaryStorage.close();
        }

        if (documentCopyStorage != null) {
            documentCopyStorage.close();
        }

        if (shardedTreeStorage != null) {
            shardedTreeStorage.close();
        }
    }
}
