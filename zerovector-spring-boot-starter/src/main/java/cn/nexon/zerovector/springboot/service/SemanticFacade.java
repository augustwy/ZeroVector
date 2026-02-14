package cn.nexon.zerovector.springboot.service;

import cn.nexon.zerovector.core.model.Document;
import cn.nexon.zerovector.core.model.DocumentChunk;
import cn.nexon.zerovector.core.model.NavigationPath;
import cn.nexon.zerovector.core.SemanticTreeManager;
import cn.nexon.zerovector.core.model.NavigationResult;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
public class SemanticFacade {
    
    private final SemanticTreeManager semanticTreeManager;
    
    public SemanticFacade(SemanticTreeManager semanticTreeManager) {
        this.semanticTreeManager = semanticTreeManager;
    }
    
    public SearchResult search(String query) {
        NavigationResult result = semanticTreeManager.navigate(query);
        
        return new SearchResult(
            query,
            result.documents(),
            result.reasoning(),
            result.path()
        );
    }
    
    public void addDocument(Path filePath) {
        semanticTreeManager.addDocument(filePath);
    }
    
    public CompletableFuture<Void> addDocumentAsync(Path filePath) {
        return semanticTreeManager.addDocumentAsync(filePath);
    }
    
    public void addDocuments(List<Path> filePaths) {
        semanticTreeManager.addDocuments(filePaths);
    }
    
    public CompletableFuture<Void> addDocumentsAsync(List<Path> filePaths) {
        return semanticTreeManager.addDocumentsAsync(filePaths);
    }
    
    public void buildTree(List<Document> documents) {
        semanticTreeManager.buildTree(documents);
    }
    
    public SearchResult getSearchResult(String query) {
        return search(query);
    }
    
    public record SearchResult(
        String query,
        List<DocumentChunk> documents,
        String reasoning,
        List<NavigationPath> path
    ) {}
}
