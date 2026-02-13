package cn.nexon.zerovector.springboot.service;

import cn.nexon.zerovector.core.model.DocumentChunk;
import cn.nexon.zerovector.core.model.NavigationPath;
import cn.nexon.zerovector.core.SemanticTreeService;
import cn.nexon.zerovector.core.document.DocumentProcessor;
import cn.nexon.zerovector.core.model.NavigationResult;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * ZeroVector服务类，提供高级API
 */
@Service
public class ZeroVectorService {
    
    private final SemanticTreeService semanticTreeService;
    
    public ZeroVectorService(SemanticTreeService semanticTreeService) {
        this.semanticTreeService = semanticTreeService;
    }
    
    /**
     * 搜索文档
     * @param query 查询字符串
     * @return 搜索结果
     */
    public SearchResult search(String query) {
        NavigationResult result = semanticTreeService.navigate(query);
        
        return new SearchResult(
            query,
            result.documents(),
            result.reasoning(),
            result.path()
        );
    }
    
    /**
     * 添加文档
     * @param documents 文档列表
     */
    public void addDocumentChunks(List<DocumentChunk> documents) {
        if (!semanticTreeService.isTreeLoaded()) {
            semanticTreeService.buildTree(documents);
        } else {
            // 使用增量添加功能
            semanticTreeService.addDocumentChunks(documents);
        }
    }
    
    /**
     * 从文件路径添加文档（自动处理和分块）
     * @param filePath 文件路径
     * @return 处理结果的异步Future
     */
    public CompletableFuture<Void> addDocumentAsync(Path filePath) {
        return semanticTreeService.addDocumentAsync(filePath);
    }
    
    /**
     * 从输入流添加文档（自动处理和分块）
     * @param inputStream 文档输入流
     * @param fileName 文件名
     * @return 处理结果的异步Future
     */
    public CompletableFuture<Void> addDocumentAsync(InputStream inputStream, String fileName) {
        return semanticTreeService.addDocumentAsync(inputStream, fileName);
    }
    
    /**
     * 从内容添加文档（自动处理和分块）
     * @param content 文档内容
     * @param fileName 文件名
     * @return 处理结果的异步Future
     */
    public CompletableFuture<Void> addDocumentAsync(String content, String fileName) {
        return semanticTreeService.addDocumentAsync(content, fileName);
    }
    
    /**
     * 从文件路径添加文档（同步，阻塞直到完成）
     * @param filePath 文件路径
     */
    public void addDocument(Path filePath) {
        semanticTreeService.addDocument(filePath);
    }
    
    /**
     * 从输入流添加文档（同步，阻塞直到完成）
     * @param inputStream 文档输入流
     * @param fileName 文件名
     */
    public void addDocument(InputStream inputStream, String fileName) {
        semanticTreeService.addDocument(inputStream, fileName);
    }
    
    /**
     * 从内容添加文档（同步，阻塞直到完成）
     * @param content 文档内容
     * @param fileName 文件名
     */
    public void addDocument(String content, String fileName) {
        semanticTreeService.addDocument(content, fileName);
    }
    
    /**
     * 从文件路径添加多个文档（自动处理和分块）
     * @param filePaths 文件路径列表
     * @return 处理结果的异步Future
     */
    public CompletableFuture<Void> addDocumentsAsync(List<Path> filePaths) {
        return semanticTreeService.addDocumentsAsync(filePaths);
    }
    
    /**
     * 添加多个文档（自动处理和分块）
     * @param documents 文档列表，每个元素包含[content, fileName]
     */
    public void addDocumentsFromContent(List<SemanticTreeService.DocumentInfo> documents) {
        semanticTreeService.addDocumentsFromContent(documents);
    }
    
    /**
     * 从文件路径构建语义树（自动处理和分块）
     * @param filePaths 文件路径列表
     * @return 处理结果的异步Future
     */
    public CompletableFuture<Void> rebuildIndexAsync(List<Path> filePaths) {
        return semanticTreeService.buildTreeFromDocumentsAsync(filePaths);
    }
    
    /**
     * 重建索引（从文档内容，自动处理和分块）
     * @param documents 文档列表，每个元素包含[content, fileName]
     */
    public void rebuildIndexFromContent(List<SemanticTreeService.DocumentInfo> documents) {
        semanticTreeService.buildTreeFromDocuments(documents);
    }
    
    /**
     * 设置文档处理配置
     * @param maxChunkSize 最大块大小（字符数）
     * @param maxChunkSizeOverlap 块之间重叠大小（字符数）
     * @param splitByParagraph 是否按段落分割
     * @param splitBySentence 是否按句子分割
     * @param splitByHeading 是否按标题分割
     * @param disableChunking 是否禁用分片（不分块）
     */
    public void setDocumentProcessingConfig(int maxChunkSize, int maxChunkSizeOverlap, 
                                       boolean splitByParagraph, boolean splitBySentence, boolean splitByHeading, boolean disableChunking) {
        DocumentProcessor.ProcessingConfig config = new DocumentProcessor.ProcessingConfig(
            maxChunkSize,
            maxChunkSizeOverlap,
            splitByParagraph,
            splitBySentence,
            splitByHeading,
            "^#{1,6}\\s+", // 默认Markdown标题模式
            disableChunking
        );
        semanticTreeService.setDocumentProcessingConfig(config);
    }
    
    /**
     * 设置文档处理配置（保持向后兼容）
     * @param maxChunkSize 最大块大小（字符数）
     * @param maxChunkSizeOverlap 块之间重叠大小（字符数）
     * @param splitByParagraph 是否按段落分割
     * @param splitBySentence 是否按句子分割
     * @param splitByHeading 是否按标题分割
     */
    public void setDocumentProcessingConfig(int maxChunkSize, int maxChunkSizeOverlap, 
                                       boolean splitByParagraph, boolean splitBySentence, boolean splitByHeading) {
        setDocumentProcessingConfig(maxChunkSize, maxChunkSizeOverlap, splitByParagraph, splitBySentence, splitByHeading, false);
    }
    
    /**
     * 设置文档处理配置（不分片模式）
     * 整个文档将作为一个单独的块处理
     */
    public void setNoChunkingMode() {
        semanticTreeService.setDocumentProcessingConfig(DocumentProcessor.ProcessingConfig.NO_CHUNKING);
    }
    
    /**
     * 获取支持的文档格式
     * @return 支持的文件扩展名列表
     */
    public List<String> getSupportedFormats() {
        return semanticTreeService.getSupportedFormats();
    }
    
    /**
     * 搜索结果
     */
    public record SearchResult(
        String query,
        List<DocumentChunk> documents,
        String reasoning,
        List<NavigationPath> path
    ) {}
}