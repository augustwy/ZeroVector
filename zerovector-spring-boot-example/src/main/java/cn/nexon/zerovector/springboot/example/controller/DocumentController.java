package cn.nexon.zerovector.springboot.example.controller;

import cn.nexon.zerovector.core.ai.LLMUsageStats;
import cn.nexon.zerovector.core.model.DocumentUploadResult;
import cn.nexon.zerovector.springboot.SemanticHub;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档控制器
 * 提供文档上传和搜索的 REST API
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final SemanticHub semanticHub;

    @Autowired
    public DocumentController(SemanticHub semanticHub) {
        this.semanticHub = semanticHub;
    }

    /**
     * 上传文档
     * 上传文档并进行语义理解处理
     *
     * @param file 上传的文件
     * @return 处理结果，包含 LLM 调用统计
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadDocument(
            @RequestParam("file") MultipartFile file) {
        Map<String, Object> response = new HashMap<>();

        try {
            if (file.isEmpty()) {
                response.put("success", false);
                response.put("message", "文件不能为空");
                return ResponseEntity.badRequest().body(response);
            }

            String originalFilename = file.getOriginalFilename();
            Path currentDir = Paths.get("").toAbsolutePath();
            String uploadDir = currentDir.resolve("uploads").toString();
            Path uploadPath = Paths.get(uploadDir);

            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            Path filePath = uploadPath.resolve(originalFilename);
            file.transferTo(filePath.toFile());

            DocumentUploadResult result = semanticHub.addDocument(filePath);

            response.put("success", result.success());
            response.put("message", result.message());
            response.put("documentId", result.documentId());
            response.put("documentTitle", result.documentTitle());
            response.put("skipped", result.skipped());
            response.put("filename", originalFilename);
            response.put("filePath", filePath.toString());

            LLMUsageStats stats = result.llmUsageStats();
            if (stats != null && stats.getCallCount() > 0) {
                Map<String, Object> llmStats = new HashMap<>();
                llmStats.put("callCount", stats.getCallCount());
                llmStats.put("totalTokens", stats.getTotalTokens());
                llmStats.put("inputTokens", stats.getTotalInputTokens());
                llmStats.put("outputTokens", stats.getTotalOutputTokens());
                llmStats.put("totalDurationMs", stats.getTotalDuration());
                llmStats.put("averageDurationMs", stats.getAverageDuration());
                response.put("llmUsageStats", llmStats);
            }

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            response.put("success", false);
            response.put("message", "文件上传失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 批量上传文档
     * 批量上传多个文档并进行语义理解处理
     *
     * @param files 上传的文件列表
     * @return 处理结果列表，包含每个文档的 LLM 调用统计
     */
    @PostMapping("/upload/batch")
    public ResponseEntity<Map<String, Object>> uploadDocuments(
            @RequestParam("files") MultipartFile[] files) {
        Map<String, Object> response = new HashMap<>();

        try {
            if (files == null || files.length == 0) {
                response.put("success", false);
                response.put("message", "文件列表不能为空");
                return ResponseEntity.badRequest().body(response);
            }

            Path currentDir = Paths.get("").toAbsolutePath();
            String uploadDir = currentDir.resolve("uploads").toString();
            Path uploadPath = Paths.get(uploadDir);

            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            List<Path> filePaths = new ArrayList<>();
            for (MultipartFile file : files) {
                if (!file.isEmpty()) {
                    String originalFilename = file.getOriginalFilename();
                    Path filePath = uploadPath.resolve(originalFilename);
                    file.transferTo(filePath.toFile());
                    filePaths.add(filePath);
                }
            }

            List<DocumentUploadResult> results = semanticHub.addDocuments(filePaths);

            int successCount = 0;
            int skippedCount = 0;
            int failureCount = 0;
            int totalTokens = 0;
            int totalCallCount = 0;
            long totalDurationMs = 0;

            List<Map<String, Object>> documentResults = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
                DocumentUploadResult result = results.get(i);
                Map<String, Object> docResult = new HashMap<>();
                docResult.put("documentId", result.documentId());
                docResult.put("documentTitle", result.documentTitle());
                docResult.put("success", result.success());
                docResult.put("skipped", result.skipped());
                docResult.put("message", result.message());
                docResult.put("filename", files[i].getOriginalFilename());

                if (result.success()) {
                    successCount++;
                } else if (result.skipped()) {
                    skippedCount++;
                } else {
                    failureCount++;
                }

                LLMUsageStats stats = result.llmUsageStats();
                if (stats != null) {
                    totalTokens += stats.getTotalTokens();
                    totalCallCount += stats.getCallCount();
                    totalDurationMs += stats.getTotalDuration();

                    Map<String, Object> llmStats = new HashMap<>();
                    llmStats.put("callCount", stats.getCallCount());
                    llmStats.put("totalTokens", stats.getTotalTokens());
                    llmStats.put("inputTokens", stats.getTotalInputTokens());
                    llmStats.put("outputTokens", stats.getTotalOutputTokens());
                    llmStats.put("totalDurationMs", stats.getTotalDuration());
                    llmStats.put("averageDurationMs", stats.getAverageDuration());
                    docResult.put("llmUsageStats", llmStats);
                }

                documentResults.add(docResult);
            }

            response.put("success", true);
            response.put("totalFiles", files.length);
            response.put("successCount", successCount);
            response.put("skippedCount", skippedCount);
            response.put("failureCount", failureCount);
            response.put("documents", documentResults);

            if (totalCallCount > 0) {
                Map<String, Object> totalLlmStats = new HashMap<>();
                totalLlmStats.put("callCount", totalCallCount);
                totalLlmStats.put("totalTokens", totalTokens);
                totalLlmStats.put("totalDurationMs", totalDurationMs);
                response.put("totalLlmUsageStats", totalLlmStats);
            }

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            response.put("success", false);
            response.put("message", "批量上传失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 搜索接口
     * 执行语义搜索并返回结果，包含 LLM 调用统计信息
     *
     * @param query 查询字符串
     * @return 搜索结果
     */
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@RequestParam("query") String query) {
        Map<String, Object> response = new HashMap<>();

        try {
            if (query == null || query.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "查询内容不能为空");
                return ResponseEntity.badRequest().body(response);
            }

            SemanticHub.SearchResult searchResult = semanticHub.search(query);

            response.put("success", true);
            response.put("query", query);
            response.put("reasoning", searchResult.reasoning());
            response.put("documents", searchResult.documents());
            response.put("path", searchResult.path());
            response.put("documentCount", searchResult.documents() != null ? searchResult.documents().size() : 0);

            LLMUsageStats stats = searchResult.llmUsageStats();
            if (stats != null && stats.getCallCount() > 0) {
                Map<String, Object> llmStats = new HashMap<>();
                llmStats.put("callCount", stats.getCallCount());
                llmStats.put("totalTokens", stats.getTotalTokens());
                llmStats.put("inputTokens", stats.getTotalInputTokens());
                llmStats.put("outputTokens", stats.getTotalOutputTokens());
                llmStats.put("totalDurationMs", stats.getTotalDuration());
                llmStats.put("averageDurationMs", stats.getAverageDuration());
                response.put("llmUsageStats", llmStats);
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "查询失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 健康检查接口
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "ok");
        response.put("message", "Document API is running");
        return ResponseEntity.ok(response);
    }
}
