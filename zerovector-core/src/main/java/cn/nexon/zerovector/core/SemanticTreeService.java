package cn.nexon.zerovector.core;

import cn.nexon.zerovector.core.ai.LLMService;
import cn.nexon.zerovector.core.config.ConcurrencyProperties;
import cn.nexon.zerovector.core.document.DocumentProcessor;
import cn.nexon.zerovector.core.document.DocumentProcessorFactory;
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
 * 语义树服务
 * 整合树构建和导航功能，支持分片存储和文档处理
 * 
 * 支持两种存储模式：
 * 1. 分片存储模式：适用于大型文档集，将语义树分散存储在多个文件中，支持增量更新
 * 2. 非分片存储模式：适用于中小型文档集，将语义树存储在单个文件中，简单高效
 * 
 * 默认使用非分片模式，可通过构造函数参数或静态工厂方法指定存储模式
 */
public class SemanticTreeService {
    private static final Logger logger = LoggerFactory.getLogger(SemanticTreeService.class);

    private final LLMService llmService;
    private KeywordDictionary keywordDictionary;  // 移除final修饰符，允许重新赋值
    private final Path storagePath;
    private final String treeFilePath;
    private final String treeStorageDir;
    private final String dictionaryFilePath;  // 添加关键词字典文件路径
    private final String documentsDir;  // 添加文档副本存储目录
    private final boolean useShardedStorage;
    private final DocumentProcessor documentProcessor;
    private final ConcurrencyProperties concurrencyProperties;
    private MMapDocumentStore documentStore;
    private ShardedTreeStorage shardedTreeStorage;
    private SemanticTree semanticTree;
    private Navigator navigator;
    private DocumentProcessor.ProcessingConfig currentConfig;
    private HybridNavigator hybridNavigator;

    public SemanticTreeService(LLMService llmService, Path storagePath) {
        this(llmService, storagePath, false, new ConcurrencyProperties());
    }

    public SemanticTreeService(LLMService llmService, Path storagePath, ConcurrencyProperties concurrencyProperties) {
        this(llmService, storagePath, true, concurrencyProperties);
    }

    public SemanticTreeService(LLMService llmService, Path storagePath, boolean useShardedStorage, ConcurrencyProperties concurrencyProperties) {
        this.llmService = llmService;
        this.keywordDictionary = new KeywordDictionary();
        this.storagePath = storagePath;
        this.treeFilePath = storagePath.toString() + ".tree";
        this.treeStorageDir = storagePath.toString() + "_shards";
        this.dictionaryFilePath = storagePath.toString() + ".dict";  // 初始化字典文件路径
        this.documentsDir = storagePath.toString() + "_docs";  // 初始化文档副本目录
        this.useShardedStorage = useShardedStorage;
        this.documentProcessor = DocumentProcessorFactory.getInstance().getDefaultProcessor();
        this.currentConfig = DocumentProcessor.ProcessingConfig.DEFAULT;
        this.concurrencyProperties = concurrencyProperties;
    }
    
    /**
     * 创建非分片模式的语义树服务
     * @param llmService LLM服务
     * @param storagePath 存储路径
     * @param concurrencyProperties 并发属性
     * @return 非分片模式的语义树服务实例
     */
    public static SemanticTreeService createNonSharded(LLMService llmService, Path storagePath, ConcurrencyProperties concurrencyProperties) {
        return new SemanticTreeService(llmService, storagePath, false, concurrencyProperties);
    }
    
    /**
     * 创建分片模式的语义树服务
     * @param llmService LLM服务
     * @param storagePath 存储路径
     * @param concurrencyProperties 并发属性
     * @return 分片模式的语义树服务实例
     */
    public static SemanticTreeService createSharded(LLMService llmService, Path storagePath, ConcurrencyProperties concurrencyProperties) {
        return new SemanticTreeService(llmService, storagePath, true, concurrencyProperties);
    }
    
    /**
     * 初始化服务
     */
    public void initialize() throws IOException {
        this.documentStore = MMapDocumentStore.open(storagePath.toString());
        
        // 确保文档副本目录存在
        Files.createDirectories(Paths.get(documentsDir));
        
        // 初始化分片存储（如果启用）
        if (useShardedStorage) {
            this.shardedTreeStorage = new ShardedTreeStorage(treeStorageDir);
        }
        
        // 尝试加载已保存的语义树
        if (useShardedStorage) {
            this.semanticTree = shardedTreeStorage.loadTree();
        } else {
            this.semanticTree = SemanticTree.loadFromFile(treeFilePath);
        }
        
        // 尝试加载已保存的关键词字典
        try {
            KeywordDictionary loadedDict = KeywordDictionary.loadFromFile(dictionaryFilePath);
            // 将加载的字典内容复制到当前字典
            // 由于KeywordDictionary没有提供直接复制内容的方法，我们需要使用反射或添加新方法
            // 这里我们简单地重新加载字典
            this.keywordDictionary = loadedDict;
            logger.info("已加载已保存的关键词字典");
        } catch (IOException e) {
            logger.warn("加载关键词字典失败，将使用新字典: {}", e.getMessage());
        }
        
        if (this.semanticTree != null) {
            this.navigator = new Navigator(semanticTree, llmService, documentStore);
            this.hybridNavigator = new HybridNavigator(semanticTree, keywordDictionary, llmService, documentStore);
            logger.info("已加载已保存的语义树");
        }
    }
    
    /**
     * 从文档块构建语义树
     */
    public void buildTree(List<DocumentChunk> chunks) {
        logger.info("构建语义树，包含 {} 个文档块", chunks.size());
        for (DocumentChunk chunk : chunks) {
            logger.debug("  - {}: {}", chunk.id(), chunk.summary());
        }
        
        TreeBuilder builder = new TreeBuilder(llmService, keywordDictionary, documentStore, concurrencyProperties);
        this.semanticTree = builder.build(chunks);
        this.navigator = new Navigator(semanticTree, llmService, documentStore);
        this.hybridNavigator = new HybridNavigator(semanticTree, keywordDictionary, llmService, documentStore);
        
        logger.info("语义树构建完成，包含 {} 个文档块", semanticTree.chunks().size());
        
        // 在非分片模式下自动保存构建的树
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
            // 如果没有现有语义树，则构建新树
            logger.info("没有现有语义树，构建新树");
            buildTree(newChunks);
            return;
        }
        
        logger.info("现有语义树包含 {} 个文档块", semanticTree.chunks().size());
        
        // 保存旧的语义树引用，用于增量更新
        SemanticTree oldTree = semanticTree;
        
        TreeBuilder builder = new TreeBuilder(llmService, keywordDictionary, documentStore, concurrencyProperties);
        // 重新构建整个树，因为新的TreeBuilder不再支持addToExistingTree
        List<DocumentChunk> allChunks = new ArrayList<>(semanticTree.chunks().values());
        allChunks.addAll(newChunks);
        this.semanticTree = builder.build(allChunks);
        this.navigator = new Navigator(semanticTree, llmService, documentStore);
        
        logger.info("更新后语义树包含 {} 个文档块", semanticTree.chunks().size());
        
        // 如果使用分片存储，进行增量更新
        if (useShardedStorage && shardedTreeStorage != null) {
            try {
                shardedTreeStorage.updateTreeIncremental(oldTree, semanticTree);
            } catch (IOException e) {
                logger.error("增量更新失败，回退到完整保存: {}", e.getMessage());
                try {
                    shardedTreeStorage.saveTree(semanticTree);
                } catch (IOException ex) {
                    logger.error("完整保存也失败: {}", ex.getMessage());
                }
            }
        } else {
            // 非分片存储模式下，直接保存更新的语义树
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
        // 计算当前文件的MD5值
        String currentMd5;
        try {
            currentMd5 = MD5Util.calculateMD5(filePath);
        } catch (IOException e) {
            throw new RuntimeException("计算文件MD5失败", e);
        }
        
        // 确保语义树已加载
        if (semanticTree == null) {
            try {
                if (useShardedStorage) {
                    this.semanticTree = shardedTreeStorage.loadTree();
                } else {
                    this.semanticTree = SemanticTree.loadFromFile(treeFilePath);
                }
                
                if (this.semanticTree != null) {
                    this.navigator = new Navigator(semanticTree, llmService, documentStore);
                    this.hybridNavigator = new HybridNavigator(semanticTree, keywordDictionary, llmService, documentStore);
                }
            } catch (IOException e) {
                logger.warn("加载语义树失败: {}", e.getMessage());
                this.semanticTree = new SemanticTree(null, new HashMap<>(), new HashMap<>());
            }
        }
        
        // 检查是否已存在相同MD5的文档
        boolean alreadyExists = false;
        if (semanticTree != null && semanticTree.chunks() != null) {
            alreadyExists = semanticTree.chunks().values().stream()
                .anyMatch(chunk -> chunk.md5() != null && chunk.md5().equals(currentMd5));
        }
        
        if (alreadyExists) {
            logger.info("文件 {} 已存在（MD5: {}），跳过处理", filePath, currentMd5);
            return CompletableFuture.completedFuture(null);
        }
        
        // 创建文件副本
        Path copiedFile = createDocumentCopy(filePath);
        
        // 根据文件类型选择合适的处理器
        DocumentProcessor processor = DocumentProcessorFactory.getInstance().getProcessor(filePath.toString());
        // 使用当前配置
        processor.setConfig(currentConfig);
            
        return processor.processDocument(filePath)
            .thenApply(chunks -> {
                // 将文档块转换为基于文件路径的文档块（不存储内容）
                return chunks.stream()
                    .map(chunk -> DocumentChunk.withFilePath(
                        chunk.id(),
                        chunk.summary(),
                        copiedFile.toString(),
                        chunk.md5(),  // 传递MD5值
                        chunk.metadata()
                    ))
                    .collect(Collectors.toList());
            })
            .thenAccept(this::addDocumentChunks);
    }
    
    /**
     * 从输入流添加文档（自动处理和分块）
     * @param inputStream 文档输入流
     * @param fileName 文件名
     * @return 处理结果的异步Future
     */
    public CompletableFuture<Void> addDocumentAsync(InputStream inputStream, String fileName) {
        // 根据文件类型选择合适的处理器
        DocumentProcessor processor = DocumentProcessorFactory.getInstance().getProcessor(fileName);
        // 使用当前配置
        processor.setConfig(currentConfig);
            
        return processor.processDocument(inputStream, fileName)
            .thenAccept(this::addDocumentChunks);
    }
    
    /**
     * 从内容添加文档（自动处理和分块）
     * @param content 文档内容
     * @param fileName 文件名
     * @return 处理结果的异步Future
     */
    public CompletableFuture<Void> addDocumentAsync(String content, String fileName) {
        // 根据文件类型选择合适的处理器
        DocumentProcessor processor = DocumentProcessorFactory.getInstance().getProcessor(fileName);
        // 使用当前配置
        processor.setConfig(currentConfig);
            
        return processor.processDocument(content, fileName)
            .thenAccept(this::addDocumentChunks);
    }
    
    /**
     * 从文件路径添加文档（同步，阻塞直到完成）
     * @param filePath 文件路径
     */
    public void addDocument(Path filePath) {
        // 计算当前文件的MD5值
        String currentMd5;
        try {
            currentMd5 = MD5Util.calculateMD5(filePath);
        } catch (IOException e) {
            throw new RuntimeException("计算文件MD5失败", e);
        }
        
        // 检查是否已存在相同MD5的文档
        boolean alreadyExists = false;
        if (semanticTree != null && semanticTree.chunks() != null) {
            alreadyExists = semanticTree.chunks().values().stream()
                .anyMatch(chunk -> chunk.md5() != null && chunk.md5().equals(currentMd5));
        }
        
        if (alreadyExists) {
            logger.info("文件 {} 已存在（MD5: {}），跳过处理", filePath, currentMd5);
            return;
        }
        
        // 创建文件副本
        Path copiedFile = createDocumentCopy(filePath);
        
        // 根据文件类型选择合适的处理器
        DocumentProcessor processor = DocumentProcessorFactory.getInstance().getProcessor(filePath.toString());
        // 使用当前配置
        processor.setConfig(currentConfig);
            
        try {
            List<DocumentChunk> chunks = processor.processDocument(filePath).get();
            
            // 将文档块转换为基于文件路径的文档块（不存储内容）
            List<DocumentChunk> filePathBasedChunks = chunks.stream()
                .map(chunk -> DocumentChunk.withFilePath(
                    chunk.id(),
                    chunk.summary(),
                    copiedFile.toString(),
                    chunk.md5(),  // 传递MD5值
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
        // 根据文件类型选择合适的处理器
        DocumentProcessor processor = DocumentProcessorFactory.getInstance().getProcessor(fileName);
        // 使用当前配置
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
        // 根据文件类型选择合适的处理器
        DocumentProcessor processor = DocumentProcessorFactory.getInstance().getProcessor(fileName);
        // 使用当前配置
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
        // 过滤掉已存在的文档（基于MD5）
        List<Path> filesToProcess = new ArrayList<>();
        Map<Path, String> md5Map = new HashMap<>();
        
        if (semanticTree != null && semanticTree.chunks() != null) {
            // 获取所有已存在的MD5值
            Set<String> existingMd5s = semanticTree.chunks().values().stream()
                .filter(chunk -> chunk.md5() != null)
                .map(DocumentChunk::md5)
                .collect(Collectors.toSet());
            
            // 计算每个文件的MD5并过滤
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
                    // 即使MD5计算失败，也处理该文件
                    filesToProcess.add(filePath);
                }
            }
        } else {
            // 如果没有现有语义树，处理所有文件
            filesToProcess.addAll(filePaths);
        }
        
        if (filesToProcess.isEmpty()) {
            logger.info("所有文件都已存在，无需处理");
            return CompletableFuture.completedFuture(null);
        }
        
        List<CompletableFuture<List<DocumentChunk>>> futures = filesToProcess.stream()
            .map(filePath -> {
                // 根据文件类型选择合适的处理器
                DocumentProcessor processor = DocumentProcessorFactory.getInstance().getProcessor(filePath.toString());
                // 使用当前配置
                processor.setConfig(currentConfig);
                    
                return processor.processDocument(filePath)
                    .thenApply(chunks -> {
                        // 将文档块转换为基于文件路径的文档块（不存储内容）
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
        
        // 优先使用 HybridNavigator，如果不可用则回退到普通 Navigator
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
        
        // 保存关键词字典
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
     * 关闭服务
     */
    public void close() throws IOException {
        // 保存语义树
        saveTree();
        
        // 关闭文档存储
        if (documentStore != null) {
            documentStore.close();
        }
        
        // 关闭分片存储
        if (shardedTreeStorage != null) {
            shardedTreeStorage.close();
        }
    }
}