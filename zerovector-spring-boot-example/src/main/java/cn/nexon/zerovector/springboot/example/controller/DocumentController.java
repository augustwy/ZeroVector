package cn.nexon.zerovector.springboot.example.controller;

import cn.nexon.zerovector.springboot.service.SemanticFacade;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final SemanticFacade semanticFacade;

    @Autowired
    public DocumentController(SemanticFacade semanticFacade) {
        this.semanticFacade = semanticFacade;
    }

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

            semanticFacade.addDocument(filePath);

            response.put("success", true);
            response.put("message", "文档上传并处理成功");
            response.put("filename", originalFilename);
            response.put("filePath", filePath.toString());

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            response.put("success", false);
            response.put("message", "文件上传失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@RequestParam("query") String query) {
        Map<String, Object> response = new HashMap<>();

        try {
            if (query == null || query.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "查询内容不能为空");
                return ResponseEntity.badRequest().body(response);
            }

            SemanticFacade.SearchResult searchResult = semanticFacade.search(query);

            response.put("success", true);
            response.put("query", query);
            response.put("reasoning", searchResult.reasoning());
            response.put("documents", searchResult.documents());
            response.put("path", searchResult.path());
            response.put("documentCount", searchResult.documents() != null ? searchResult.documents().size() : 0);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "查询失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "ok");
        response.put("message", "Document API is running");
        return ResponseEntity.ok(response);
    }
}
