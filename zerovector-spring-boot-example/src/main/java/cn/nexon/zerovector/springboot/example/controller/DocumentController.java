/*
 * Copyright 2025 nexonlab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cn.nexon.zerovector.springboot.example.controller;

import cn.nexon.zerovector.core.ai.LLMResponse;
import cn.nexon.zerovector.core.ai.LLMUsageStats;
import cn.nexon.zerovector.core.ai.SmartCacheStrategy;
import cn.nexon.zerovector.core.model.DocumentChunk;
import cn.nexon.zerovector.core.model.DocumentUploadResult;
import cn.nexon.zerovector.core.model.SourceMode;
import cn.nexon.zerovector.springboot.SearchResult;
import cn.nexon.zerovector.springboot.SemanticHub;
import cn.nexon.zerovector.springboot.example.util.DocumentTextExtractor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文档上传与检索 REST API。
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final SemanticHub semanticHub;

    public DocumentController(SemanticHub semanticHub) {
        this.semanticHub = semanticHub;
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "mode", defaultValue = "COPY") SourceMode mode) {
        Map<String, Object> response = new HashMap<>();
        if (file == null || file.isEmpty()) {
            response.put("success", false);
            response.put("message", "文件不能为空");
            return ResponseEntity.badRequest().body(response);
        }

        Path uploaded = null;
        Path prepared = null;
        try {
            uploaded = saveUpload(file);
            prepared = DocumentTextExtractor.toText(uploaded, uploaded.getParent());
            DocumentUploadResult result = semanticHub.addDocument(prepared, mode, null);

            response.put("success", result.success());
            response.put("message", result.message());
            response.put("documentId", result.documentId());
            response.put("documentTitle", result.documentTitle());
            response.put("skipped", result.skipped());
            response.put("filename", file.getOriginalFilename());
            Map<String, Object> stats = toStatsMap(result.llmUsageStats());
            if (stats != null) {
                response.put("llmUsageStats", stats);
            }
            return ResponseEntity.ok(response);
        } catch (IOException | RuntimeException e) {
            response.put("success", false);
            response.put("message", "文件处理失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        } finally {
            // REFERENCE 直接依赖上传文件，必须保留；COPY 的正文已写入托管副本，可以清理
            if (mode != SourceMode.REFERENCE) {
                deleteQuietly(prepared, uploaded);
            }
        }
    }

    @PostMapping("/upload/batch")
    public ResponseEntity<Map<String, Object>> uploadDocuments(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "mode", defaultValue = "COPY") SourceMode mode) {
        Map<String, Object> response = new HashMap<>();
        if (files == null || files.length == 0) {
            response.put("success", false);
            response.put("message", "文件列表不能为空");
            return ResponseEntity.badRequest().body(response);
        }

        List<Path> prepared = new ArrayList<>();
        List<Path> uploaded = new ArrayList<>();
        List<MultipartFile> accepted = new ArrayList<>();
        try {
            for (MultipartFile file : files) {
                if (file == null || file.isEmpty()) {
                    continue;
                }
                Path saved = saveUpload(file);
                uploaded.add(saved);
                prepared.add(DocumentTextExtractor.toText(saved, saved.getParent()));
                accepted.add(file);
            }

            List<DocumentUploadResult> results = semanticHub.addDocuments(prepared, mode, null);
            int successCount = 0;
            int skippedCount = 0;
            int failureCount = 0;
            List<Map<String, Object>> documents = new ArrayList<>();

            for (int i = 0; i < results.size(); i++) {
                DocumentUploadResult result = results.get(i);
                Map<String, Object> item = new HashMap<>();
                item.put("documentId", result.documentId());
                item.put("documentTitle", result.documentTitle());
                item.put("success", result.success());
                item.put("skipped", result.skipped());
                item.put("message", result.message());
                item.put("filename", accepted.get(i).getOriginalFilename());

                if (result.success() && !result.skipped()) {
                    successCount++;
                } else if (result.skipped()) {
                    skippedCount++;
                } else {
                    failureCount++;
                }
                documents.add(item);
            }

            response.put("success", true);
            response.put("totalFiles", accepted.size());
            response.put("successCount", successCount);
            response.put("skippedCount", skippedCount);
            response.put("failureCount", failureCount);
            response.put("documents", documents);
            return ResponseEntity.ok(response);
        } catch (IOException | RuntimeException e) {
            response.put("success", false);
            response.put("message", "批量上传失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        } finally {
            if (mode != SourceMode.REFERENCE) {
                for (Path path : prepared) {
                    deleteQuietly(path, null);
                }
                for (Path path : uploaded) {
                    deleteQuietly(path, null);
                }
            }
        }
    }

    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@RequestParam("query") String query) {
        Map<String, Object> response = new HashMap<>();
        if (query == null || query.trim().isEmpty()) {
            response.put("success", false);
            response.put("message", "查询内容不能为空");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            SearchResult result = semanticHub.search(query);
            response.put("success", true);
            response.put("query", query);
            response.put("reasoning", result.reasoning());
            response.put("documents", result.documents());
            response.put("path", result.path());
            response.put("documentCount", result.documents().size());
            Map<String, Object> stats = toStatsMap(result.llmUsageStats());
            if (stats != null) {
                response.put("llmUsageStats", stats);
            }
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            response.put("success", false);
            response.put("message", "查询失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PostMapping("/ask")
    public ResponseEntity<Map<String, Object>> ask(@RequestParam("query") String query) {
        Map<String, Object> response = new HashMap<>();
        if (query == null || query.trim().isEmpty()) {
            response.put("success", false);
            response.put("message", "问题不能为空");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            SearchResult searchResult = semanticHub.search(query);
            List<DocumentChunk> chunks = searchResult.documents();
            if (chunks.isEmpty()) {
                response.put("success", false);
                response.put("message", "未找到相关文档");
                return ResponseEntity.ok(response);
            }

            String prompt = """
                    根据以下文档内容回答用户的问题。如果文档中没有相关信息，请如实说明。

                    用户问题：%s

                    相关文档：
                    %s

                    请用中文回答。
                    """.formatted(query, searchResult.toDocumentText());

            LLMResponse llmResponse = semanticHub.getLlmProvider()
                    .chat(prompt, SmartCacheStrategy.RequestType.ANSWER_QUESTION);
            if (!llmResponse.success()) {
                response.put("success", false);
                response.put("message", "回答生成失败: " + llmResponse.errorMessage());
                return ResponseEntity.internalServerError().body(response);
            }

            LLMUsageStats totalStats = new LLMUsageStats();
            totalStats.add(llmResponse);
            totalStats.merge(searchResult.llmUsageStats());

            List<Map<String, Object>> sources = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                DocumentChunk chunk = chunks.get(i);
                sources.add(Map.of(
                        "index", i + 1,
                        "id", chunk.id(),
                        "summary", chunk.summary()));
            }

            response.put("success", true);
            response.put("query", query);
            response.put("answer", llmResponse.content());
            response.put("sources", sources);
            response.put("sourceCount", sources.size());
            response.put("searchReasoning", searchResult.reasoning());
            Map<String, Object> stats = toStatsMap(totalStats);
            if (stats != null) {
                response.put("llmUsageStats", stats);
            }
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            response.put("success", false);
            response.put("message", "问答失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "ok", "message", "Document API is running"));
    }

    private Path saveUpload(MultipartFile file) throws IOException {
        Path uploadDir = Paths.get("uploads").toAbsolutePath();
        Files.createDirectories(uploadDir);
        String original = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        String safeName = Paths.get(original).getFileName().toString().replaceAll("[\\\\/:*?\"<>|]", "_");
        Path target = uploadDir.resolve(UUID.randomUUID() + "_" + safeName);
        file.transferTo(target);
        return target;
    }

    private static void deleteQuietly(Path first, Path second) {
        try {
            if (first != null) {
                Files.deleteIfExists(first);
            }
        } catch (IOException ignored) {
            // best effort
        }
        if (second != null && !second.equals(first)) {
            try {
                Files.deleteIfExists(second);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    private static Map<String, Object> toStatsMap(LLMUsageStats stats) {
        if (stats == null || stats.getCallCount() <= 0) {
            return null;
        }
        Map<String, Object> result = new HashMap<>();
        result.put("callCount", stats.getCallCount());
        result.put("totalTokens", stats.getTotalTokens());
        result.put("inputTokens", stats.getTotalInputTokens());
        result.put("outputTokens", stats.getTotalOutputTokens());
        result.put("totalDurationMs", stats.getTotalDuration());
        result.put("averageDurationMs", stats.getAverageDuration());
        return result;
    }
}
