package cn.nexon.zerovector.core.document;

import cn.nexon.zerovector.core.document.impl.MarkdownDocumentProcessor;
import cn.nexon.zerovector.core.document.impl.TextDocumentProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 文档处理器工厂
 * 负责创建和管理文档处理器实例
 */
public class DocumentProcessorFactory {
    
    private static final DocumentProcessorFactory INSTANCE = new DocumentProcessorFactory();
    
    // 存储已注册的处理器，key为文件扩展名
    private final ConcurrentMap<String, DocumentProcessor> processorsByExtension = new ConcurrentHashMap<>();
    
    // 存储所有已注册的处理器
    private final List<DocumentProcessor> allProcessors = new ArrayList<>();
    
    // 默认处理器
    private DocumentProcessor defaultProcessor;
    
    private DocumentProcessorFactory() {
        // 私有构造函数，实现单例模式
        initializeDefaultProcessors();
        loadServiceLoaderProcessors();
    }
    
    /**
     * 获取工厂实例
     */
    public static DocumentProcessorFactory getInstance() {
        return INSTANCE;
    }
    
    /**
     * 根据文件名获取合适的文档处理器
     * @param fileName 文件名
     * @return 文档处理器
     */
    public DocumentProcessor getProcessor(String fileName) {
        String extension = getFileExtension(fileName);
        DocumentProcessor processor = processorsByExtension.get(extension);
        
        if (processor == null) {
            // 如果没有找到特定格式的处理器，返回默认处理器
            return defaultProcessor;
        }
        
        return processor;
    }
    
    /**
     * 注册文档处理器
     * @param processor 文档处理器
     */
    public void registerProcessor(DocumentProcessor processor) {
        allProcessors.add(processor);
        
        // 注册处理器支持的文件扩展名
        for (String format : processor.getSupportedFormats()) {
            processorsByExtension.put(format.toLowerCase(), processor);
        }
        
        // 如果没有设置默认处理器，将第一个注册的处理器设为默认
        if (defaultProcessor == null) {
            defaultProcessor = processor;
        }
    }
    
    /**
     * 设置默认处理器
     * @param processor 默认处理器
     */
    public void setDefaultProcessor(DocumentProcessor processor) {
        this.defaultProcessor = processor;
    }
    
    /**
     * 获取所有已注册的处理器
     * @return 处理器列表
     */
    public List<DocumentProcessor> getAllProcessors() {
        return new ArrayList<>(allProcessors);
    }
    
    /**
     * 获取支持的文件格式
     * @return 文件格式列表
     */
    public List<String> getSupportedFormats() {
        return new ArrayList<>(processorsByExtension.keySet());
    }
    
    /**
     * 初始化默认处理器
     */
    private void initializeDefaultProcessors() {
        // 注册内置的处理器
        registerProcessor(new MarkdownDocumentProcessor());
        registerProcessor(new TextDocumentProcessor());
    }
    
    /**
     * 通过ServiceLoader加载第三方处理器
     */
    private void loadServiceLoaderProcessors() {
        ServiceLoader<DocumentProcessor> loader = ServiceLoader.load(DocumentProcessor.class);
        
        for (DocumentProcessor processor : loader) {
            registerProcessor(processor);
        }
    }
    
    /**
     * 获取默认处理器
     * @return 默认处理器
     */
    public DocumentProcessor getDefaultProcessor() {
        return defaultProcessor;
    }
    
    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "";
        }
        
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex > 0 && lastDotIndex < fileName.length() - 1) {
            return fileName.substring(lastDotIndex + 1).toLowerCase();
        }
        
        return "";
    }
}