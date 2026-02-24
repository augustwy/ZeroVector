package cn.nexon.zerovector.core;

import cn.nexon.zerovector.core.ai.LLMProvider;
import cn.nexon.zerovector.core.config.ConcurrencyProperties;
import cn.nexon.zerovector.core.document.comprehend.DocumentComprehender;
import cn.nexon.zerovector.core.document.comprehend.DocumentComprehendResult;
import cn.nexon.zerovector.core.exception.NavigationException;
import cn.nexon.zerovector.core.exception.StorageException;
import cn.nexon.zerovector.core.index.KeywordDictionary;
import cn.nexon.zerovector.core.model.Document;
import cn.nexon.zerovector.core.model.DocumentChunk;
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
    private final ConcurrencyProperties concurrencyProperties;
    private MMapDocumentStore documentStore;
    private ShardedTreeStorage shardedTreeStorage;
    private SemanticTree semanticTree;
    private Navigator navigator;
    private HybridNavigator hybridNavigator;

    public SemanticTreeManager(LLMProvider llmProvider, DocumentComprehender documentComprehender, Path storagePath, boolean useShardedStorage, ConcurrencyProperties concurrencyProperties) {
        this.llmProvider = llmProvider;
        this.documentComprehender = documentComprehender;
        this.keywordDictionary = new KeywordDictionary();
        this.storagePath = Paths.get(storagePath.toString(), "zerovector_storage");
        this.treeFilePath = this.storagePath.toString() + ".tree";
        this.treeStorageDir = this.storagePath.toString() + "_shards";
        this.dictionaryFilePath = this.storagePath.toString() + ".dict";
        this.documentsDir = this.storagePath.toString() + "_docs";
        this.useShardedStorage = useShardedStorage;
        this.concurrencyProperties = concurrencyProperties;
    }

    public void initialize() throws IOException {
        try {
            Path storagePathObj = Paths.get(storagePath.toString());
            
            if (!Files.exists(storagePathObj)) {
                Files.createDirectories(storagePathObj);
                logger.info("创建存储目录: {}", storagePath);
            }
            
            String documentStoreFilePath = storagePath.toString() + ".data";
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
                logger.info("已加载已保存的关键词字典");
            } catch (IOException e) {
                logger.warn("加载关键词字典失败，将使用新字典: {}", e.getMessage());
            }
            
            if (this.semanticTree != null) {
                this.navigator = new Navigator(semanticTree, llmProvider, documentStore);
                this.hybridNavigator = new HybridNavigator(semanticTree, keywordDictionary, llmProvider, documentStore);
                logger.info("已加载已保存的语义树");
            }
        } catch (IOException e) {
            throw new StorageException(storagePath.toString(), "initialize", e);
        }
    }

    public void buildTree(List<Document> documents) {
        logger.info("构建语义树，包含 {} 个文档", documents.size());
        
        DocumentProcessingResult processingResult = processDocuments(documents);
        this.semanticTree = buildTreeInternal(processingResult.comprehendResultMap(), processingResult.chunkMap());
        this.navigator = new Navigator(semanticTree, llmProvider, documentStore);
        this.hybridNavigator = new HybridNavigator(semanticTree, keywordDictionary, llmProvider, documentStore);
        
        logger.info("语义树构建完成，包含 {} 个文档", documents.size());
        
        try {
            saveTree();
        } catch (IOException e) {
            throw new StorageException(treeFilePath, "saveTree", e);
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
    
    private SemanticTree buildTreeInternal(Map<String, DocumentComprehendResult> comprehendResultMap, Map<String, DocumentChunk> chunkMap) {
        TreeBuilder builder = new TreeBuilder(llmProvider, keywordDictionary, documentStore, concurrencyProperties);
        
        if (this.semanticTree == null || this.semanticTree.rootNode() == null) {
            return builder.build(comprehendResultMap, chunkMap);
        } else {
            return builder.updateTree(this.semanticTree, new ArrayList<>(comprehendResultMap.values()), chunkMap);
        }
    }

    public void addDocument(Path filePath) {
        try {
            String md5 = calculateFileMD5(filePath);
            
            if (isDocumentAlreadyExists(md5)) {
                logger.info("文件 {} 已存在（MD5: {}），跳过处理", filePath, md5);
                return;
            }
            
            Path copiedFile = createDocumentCopy(filePath);
            Document document = createDocumentFromFile(filePath, copiedFile, md5);
            
            DocumentProcessingResult processingResult = processSingleDocument(document);
            updateSemanticTreeFromDocuments(processingResult.comprehendResultMap(), processingResult.chunkMap());

            logger.debug("添加文档完成，当前语义树包含 {} 个文档", semanticTree.chunks().size());
            
            try {
                saveTree();
            } catch (IOException e) {
                throw new StorageException(treeFilePath, "saveTree", e);
            }
        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
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

    public void addDocuments(List<Path> filePaths) {
        try {
            List<Document> documents = prepareDocuments(filePaths);
            
            if (documents.isEmpty()) {
                logger.info("没有新文档需要处理");
                return;
            }
            
            DocumentProcessingResult processingResult = processDocuments(documents);
            updateSemanticTreeFromDocuments(processingResult.comprehendResultMap(), processingResult.chunkMap());

            logger.debug("批量添加文档完成，当前语义树包含 {} 个文档", semanticTree.chunks().size());
            
            try {
                saveTree();
            } catch (IOException e) {
                throw new StorageException(treeFilePath, "saveTree", e);
            }
        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException(storagePath.toString(), "addDocuments", e);
        }
    }
    
    private List<Document> prepareDocuments(List<Path> filePaths) {
        List<Document> documents = new ArrayList<>();
        long baseTimestamp = System.currentTimeMillis();
        int docIndex = 0;
        
        for (Path filePath : filePaths) {
            try {
                String md5 = MD5Util.calculateMD5(filePath);
                
                if (isDocumentAlreadyExists(md5)) {
                    logger.debug("文件 {} 已存在（MD5: {}），跳过处理", filePath, md5);
                    docIndex++;
                    continue;
                }
                
                Path copiedFile = createDocumentCopy(filePath);
                String fileName = FileUtils.getFileName(filePath);
                String docId = "doc_" + baseTimestamp + "_" + docIndex;
                docIndex++;
                
                Document document = Document.fromFile(docId, fileName, copiedFile.toString(), md5,
                    Map.of("original_file", filePath.toString()));
                
                documents.add(document);
            } catch (IOException e) {
                logger.error("处理文件 {} 失败: {}", filePath, e.getMessage());
            }
        }
        
        return documents;
    }

    public CompletableFuture<Void> addDocumentsAsync(List<Path> filePaths) {
        return CompletableFuture.runAsync(() -> addDocuments(filePaths));
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
    
    private void updateSemanticTreeFromDocuments(Map<String, DocumentComprehendResult> comprehendResultMap, Map<String, DocumentChunk> chunkMap) {
        this.semanticTree = buildTreeInternal(comprehendResultMap, chunkMap);
        updateNavigators();
    }
    
    private void updateNavigators() {
        this.navigator = new Navigator(semanticTree, llmProvider, documentStore);
        this.hybridNavigator = new HybridNavigator(semanticTree, keywordDictionary, llmProvider, documentStore);
    }
    
    public NavigationResult navigate(String query) {
        if (semanticTree == null) {
            throw new NavigationException(query, null, NavigationException.ERROR_CODE_TREE_NOT_INITIALIZED, 
                "Semantic tree not built yet");
        }
        
        if (hybridNavigator != null) {
            try {
                return hybridNavigator.navigate(query);
            } catch (Exception e) {
                throw new NavigationException(query, semanticTree.rootNode().id(), 
                    NavigationException.ERROR_CODE_LLM_DECISION_FAILED, "Navigation failed", e);
            }
        } else if (navigator != null) {
            try {
                return navigator.navigate(query);
            } catch (Exception e) {
                throw new NavigationException(query, semanticTree.rootNode().id(), 
                    NavigationException.ERROR_CODE_LLM_DECISION_FAILED, "Navigation failed", e);
            }
        } else {
            throw new NavigationException(query, null, NavigationException.ERROR_CODE_NO_NAVIGATOR, 
                "No navigator available");
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
