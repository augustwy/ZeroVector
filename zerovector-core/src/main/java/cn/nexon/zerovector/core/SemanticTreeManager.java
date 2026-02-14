package cn.nexon.zerovector.core;

import cn.nexon.zerovector.core.ai.LLMProvider;
import cn.nexon.zerovector.core.config.ConcurrencyProperties;
import cn.nexon.zerovector.core.document.comprehend.DocumentComprehender;
import cn.nexon.zerovector.core.document.comprehend.DocumentComprehendResult;
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
import cn.nexon.zerovector.core.util.MD5Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
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
        this.storagePath = storagePath;
        this.treeFilePath = storagePath.toString() + ".tree";
        this.treeStorageDir = storagePath.toString() + "_shards";
        this.dictionaryFilePath = storagePath.toString() + ".dict";
        this.documentsDir = storagePath.toString() + "_docs";
        this.useShardedStorage = useShardedStorage;
        this.concurrencyProperties = concurrencyProperties;
    }

    public void initialize() throws IOException {
        Path storagePathObj = Paths.get(storagePath.toString());
        
        if (!Files.exists(storagePathObj)) {
            Files.createDirectories(storagePathObj.getParent());
            logger.info("创建存储目录: {}", storagePath);
        }
        
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

    public void buildTree(List<Document> documents) {
        logger.info("构建语义树，包含 {} 个文档", documents.size());
        
        Map<String, DocumentComprehendResult> comprehendResultMap = new HashMap<>();
        Map<String, DocumentChunk> chunkMap = new HashMap<>();
        
        for (Document document : documents) {
            logger.debug("  - {}: {}", document.id(), document.title());
            DocumentComprehendResult result = documentComprehender.comprehend(document);
            comprehendResultMap.put(document.id(), result);
            
            DocumentChunk chunk = new DocumentChunk(
                document.id(),
                null,
                result.summary(),
                document.filePath(),
                document.md5(),
                document.metadata()
            );
            chunkMap.put(document.id(), chunk);
        }
        
        this.semanticTree = buildTreeInternal(comprehendResultMap, chunkMap);
        this.navigator = new Navigator(semanticTree, llmProvider, documentStore);
        this.hybridNavigator = new HybridNavigator(semanticTree, keywordDictionary, llmProvider, documentStore);
        
        logger.info("语义树构建完成，包含 {} 个文档", documents.size());
        
        try {
            saveTree();
        } catch (IOException e) {
            logger.error("保存语义树和关键词字典失败: {}", e.getMessage());
        }
    }
    
    private SemanticTree buildTreeInternal(Map<String, DocumentComprehendResult> comprehendResultMap, Map<String, DocumentChunk> chunkMap) {
        TreeBuilder builder = new TreeBuilder(llmProvider, keywordDictionary, documentStore, concurrencyProperties);
        
        if (this.semanticTree == null || this.semanticTree.rootNode() == null) {
            return builder.build(comprehendResultMap, chunkMap);
        } else {
            return builder.updateTree(this.semanticTree, new ArrayList<>(comprehendResultMap.values()), chunkMap);
        }
    }

    public void addDocument(Path filePath) {
        String md5;
        try {
            md5 = MD5Util.calculateMD5(filePath);
        } catch (IOException e) {
            throw new RuntimeException("计算文件MD5失败", e);
        }
        
        boolean alreadyExists = false;
        if (semanticTree != null && semanticTree.chunks() != null) {
            alreadyExists = semanticTree.chunks().values().stream()
                .anyMatch(chunk -> chunk.md5() != null && chunk.md5().equals(md5));
        }
        
        if (alreadyExists) {
            logger.info("文件 {} 已存在（MD5: {}），跳过处理", filePath, md5);
            return;
        }
        
        Path copiedFile = createDocumentCopy(filePath);
        
        String fileName = filePath.getFileName().toString();
        String docId = "doc_" + System.currentTimeMillis();
        
        Document document = Document.fromFile(docId, fileName, copiedFile.toString(), md5, 
            Map.of("original_file", filePath.toString()));
        
        DocumentComprehendResult result = documentComprehender.comprehend(document);
        
        Map<String, DocumentComprehendResult> comprehendResultMap = new HashMap<>();
        comprehendResultMap.put(docId, result);
        
        DocumentChunk chunk = new DocumentChunk(
            docId,
            null,
            result.summary(),
            copiedFile.toString(),
            md5,
            document.metadata()
        );
        
        Map<String, DocumentChunk> chunkMap = new HashMap<>();
        chunkMap.put(docId, chunk);
        
        updateSemanticTreeFromDocuments(comprehendResultMap, chunkMap);
        
        logger.info("添加文档完成，当前语义树包含 {} 个文档", semanticTree.chunks().size());
        
        try {
            saveTree();
        } catch (IOException e) {
            logger.error("保存语义树和关键词字典失败: {}", e.getMessage());
        }
    }

    public CompletableFuture<Void> addDocumentAsync(Path filePath) {
        return CompletableFuture.runAsync(() -> addDocument(filePath));
    }

    public void addDocuments(List<Path> filePaths) {
        List<Document> documents = new ArrayList<>();
        long baseTimestamp = System.currentTimeMillis();
        int docIndex = 0;
        
        for (Path filePath : filePaths) {
            try {
                String md5 = MD5Util.calculateMD5(filePath);
                
                boolean alreadyExists = false;
                if (semanticTree != null && semanticTree.chunks() != null) {
                    alreadyExists = semanticTree.chunks().values().stream()
                        .anyMatch(chunk -> chunk.md5() != null && chunk.md5().equals(md5));
                }
                
                if (alreadyExists) {
                    logger.info("文件 {} 已存在（MD5: {}），跳过处理", filePath, md5);
                    docIndex++;
                    continue;
                }
                
                Path copiedFile = createDocumentCopy(filePath);
                String fileName = filePath.getFileName().toString();
                String docId = "doc_" + baseTimestamp + "_" + docIndex;
                docIndex++;
                
                Document document = Document.fromFile(docId, fileName, copiedFile.toString(), md5,
                    Map.of("original_file", filePath.toString()));
                
                documents.add(document);
            } catch (IOException e) {
                logger.error("处理文件 {} 失败: {}", filePath, e.getMessage());
            }
        }
        
        if (documents.isEmpty()) {
            logger.info("没有新文档需要处理");
            return;
        }
        
        Map<String, DocumentComprehendResult> comprehendResultMap = new HashMap<>();
        Map<String, DocumentChunk> chunkMap = new HashMap<>();
        
        for (Document document : documents) {
            DocumentComprehendResult result = documentComprehender.comprehend(document);
            comprehendResultMap.put(document.id(), result);
            
            DocumentChunk chunk = new DocumentChunk(
                document.id(),
                null,
                result.summary(),
                document.filePath(),
                document.md5(),
                document.metadata()
            );
            chunkMap.put(document.id(), chunk);
        }
        
        updateSemanticTreeFromDocuments(comprehendResultMap, chunkMap);
        
        logger.info("批量添加文档完成，当前语义树包含 {} 个文档", semanticTree.chunks().size());
        
        try {
            saveTree();
        } catch (IOException e) {
            logger.error("保存语义树和关键词字典失败: {}", e.getMessage());
        }
    }

    public CompletableFuture<Void> addDocumentsAsync(List<Path> filePaths) {
        return CompletableFuture.runAsync(() -> addDocuments(filePaths));
    }

    private Path createDocumentCopy(Path originalFile) {
        try {
            String fileName = originalFile.getFileName().toString();
            String timestamp = String.valueOf(System.currentTimeMillis());
            String copiedFileName = timestamp + "_" + fileName;
            Path copiedFile = Paths.get(documentsDir, copiedFileName);
            
            Files.copy(originalFile, copiedFile, StandardCopyOption.REPLACE_EXISTING);
            logger.info("已创建文档副本: {} -> {}", originalFile, copiedFile);
            
            return copiedFile.toAbsolutePath();
        } catch (IOException e) {
            throw new RuntimeException("创建文档副本失败", e);
        }
    }
    
    private void updateSemanticTreeFromDocuments(Map<String, DocumentComprehendResult> comprehendResultMap, Map<String, DocumentChunk> chunkMap) {
        TreeBuilder builder = new TreeBuilder(llmProvider, keywordDictionary, documentStore, concurrencyProperties);
        
        if (this.semanticTree == null || this.semanticTree.rootNode() == null) {
            this.semanticTree = builder.build(comprehendResultMap, chunkMap);
        } else {
            this.semanticTree = builder.updateTree(this.semanticTree, new ArrayList<>(comprehendResultMap.values()), chunkMap);
        }
        
        this.navigator = new Navigator(semanticTree, llmProvider, documentStore);
        this.hybridNavigator = new HybridNavigator(semanticTree, keywordDictionary, llmProvider, documentStore);
    }
    
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
        logger.info("已保存关键词字典到文件: {}", dictionaryFilePath);
    }

    public void resetNavigator() {
        if (navigator != null) {
            navigator.reset();
        }
    }

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
