package cn.nexon.zerovector.core.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 语义树记录
 */
public record SemanticTree(
        TreeNode rootNode,
        Map<String, TreeNode> nodes,
        Map<String, DocumentChunk> chunks
) {
    private static final Logger logger = LoggerFactory.getLogger(SemanticTree.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    public SemanticTree {
        if (nodes == null) {
            nodes = Map.of();
        }
        if (chunks == null) {
            chunks = Map.of();
        }
    }
    
    public TreeNode getNode(String id) {
        return nodes.get(id);
    }
    
    public DocumentChunk getChunk(String id) {
        return chunks.get(id);
    }
    
    /**
     * 保存语义树到文件
     */
    public void saveToFile(String filePath) throws IOException {
        try {
            String json = objectMapper.writeValueAsString(this);
            Files.writeString(Paths.get(filePath), json);
            logger.debug("语义树已保存到: {}", filePath);
        } catch (JsonProcessingException e) {
            throw new IOException("序列化语义树失败", e);
        }
    }
    
    /**
     * 从文件加载语义树
     */
    public static SemanticTree loadFromFile(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            return null;
        }
        
        try {
            String json = Files.readString(path);
            SemanticTree tree = objectMapper.readValue(json, SemanticTree.class);
            logger.info("语义树已从文件加载: {}", filePath);
            return tree;
        } catch (JsonProcessingException e) {
            throw new IOException("反序列化语义树失败", e);
        }
    }
    
    /**
     * 创建一个空的语义树
     */
    public static SemanticTree empty() {
        return new SemanticTree(null, new HashMap<>(), new HashMap<>());
    }
}