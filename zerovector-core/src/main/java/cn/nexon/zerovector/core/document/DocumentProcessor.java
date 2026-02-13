package cn.nexon.zerovector.core.document;

import cn.nexon.zerovector.core.model.DocumentChunk;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 文档处理器接口
 * 负责将各种格式的文档转换为文档块列表
 */
public interface DocumentProcessor {
    
    /**
     * 从文件路径处理文档（自动分块）
     * @param filePath 文件路径
     * @return 文档块列表的异步结果
     */
    CompletableFuture<List<DocumentChunk>> processDocument(Path filePath);
    
    /**
     * 从输入流处理文档（自动分块）
     * @param inputStream 文档输入流
     * @param fileName 文件名（用于推断类型）
     * @return 文档块列表的异步结果
     */
    CompletableFuture<List<DocumentChunk>> processDocument(InputStream inputStream, String fileName);
    
    /**
     * 从字符串内容处理文档（自动分块）
     * @param content 文档内容
     * @param fileName 文件名（用于推断类型）
     * @return 文档块列表的异步结果
     */
    CompletableFuture<List<DocumentChunk>> processDocument(String content, String fileName);
    
    /**
     * 获取支持的文档格式
     * @return 支持的文件扩展名列表
     */
    List<String> getSupportedFormats();
    
    /**
     * 设置处理配置
     * @param config 处理配置
     */
    void setConfig(ProcessingConfig config);
    
    /**
     * 获取处理配置
     * @return 处理配置
     */
    ProcessingConfig getConfig();
    
    /**
     * 获取处理器名称
     * @return 处理器名称
     */
    String getName();
    
    /**
     * 获取处理器版本
     * @return 处理器版本
     */
    String getVersion();
    
    /**
     * 文档处理配置
     */
    record ProcessingConfig(
        int maxChunkSize,          // 最大块大小（字符数）
        int maxChunkSizeOverlap,   // 块之间重叠大小（字符数）
        boolean splitByParagraph,   // 是否按段落分割
        boolean splitBySentence,    // 是否按句子分割
        boolean splitByHeading,     // 是否按标题分割
        String headingPattern,      // 标题匹配模式
        boolean disableChunking     // 是否禁用分片（不分块）
    ) {
        public static ProcessingConfig DEFAULT = new ProcessingConfig(
            1000,    // 最大块大小1000字符
            100,     // 重叠100字符
            true,    // 按段落分割
            false,   // 不按句子分割
            true,    // 按标题分割
            "^#{1,6}\\s+", // Markdown标题模式
            false    // 默认启用分片
        );
        
        public static ProcessingConfig NO_CHUNKING = new ProcessingConfig(
            Integer.MAX_VALUE,    // 最大块大小设为最大值
            0,                    // 无重叠
            false,                // 不按段落分割
            false,                // 不按句子分割
            false,                // 不按标题分割
            "^#{1,6}\\s+",       // Markdown标题模式（虽然不会使用）
            true                  // 禁用分片
        );
    }
}