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

package cn.nexon.zerovector.springboot;

import cn.nexon.zerovector.core.KnowledgeBaseManager;
import cn.nexon.zerovector.core.SemanticTreeManager;
import cn.nexon.zerovector.core.ai.CacheConfig;
import cn.nexon.zerovector.core.ai.CacheStatistics;
import cn.nexon.zerovector.core.ai.CachedLLMProvider;
import cn.nexon.zerovector.core.ai.LLMProvider;
import cn.nexon.zerovector.core.ai.SmartCacheStrategy;
import cn.nexon.zerovector.core.model.Document;
import cn.nexon.zerovector.core.model.DocumentUploadResult;
import cn.nexon.zerovector.core.model.NavigationResult;
import cn.nexon.zerovector.core.model.SourceMode;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * ZeroVector 语义中心：文档上传、搜索、知识库管理与缓存运维的统一入口。
 */
public class SemanticHub {

    private final KnowledgeBaseManager knowledgeBaseManager;

    public SemanticHub(KnowledgeBaseManager knowledgeBaseManager) {
        this.knowledgeBaseManager = knowledgeBaseManager;
    }

    public SearchResult search(String query) {
        return search(query, null);
    }

    public SearchResult search(String query, String knowledgeBaseName) {
        SemanticTreeManager manager = getTargetManager(knowledgeBaseName);
        NavigationResult result = manager.navigate(query);
        return new SearchResultImpl(query, result, manager::readChunkContent);
    }

    public DocumentUploadResult addDocument(Path filePath) {
        return addDocument(filePath, (String) null);
    }

    public DocumentUploadResult addDocument(Path filePath, String knowledgeBaseName) {
        return getTargetManager(knowledgeBaseName).addDocument(filePath);
    }

    public DocumentUploadResult addDocument(Path filePath, SourceMode mode) {
        return getTargetManager(null).addDocument(filePath, mode);
    }

    public DocumentUploadResult addDocument(Path filePath, SourceMode mode, String knowledgeBaseName) {
        return getTargetManager(knowledgeBaseName).addDocument(filePath, mode);
    }

    public CompletableFuture<DocumentUploadResult> addDocumentAsync(Path filePath) {
        return addDocumentAsync(filePath, (String) null);
    }

    public CompletableFuture<DocumentUploadResult> addDocumentAsync(Path filePath, String knowledgeBaseName) {
        return getTargetManager(knowledgeBaseName).addDocumentAsync(filePath);
    }

    public CompletableFuture<DocumentUploadResult> addDocumentAsync(Path filePath, SourceMode mode) {
        return getTargetManager(null).addDocumentAsync(filePath, mode);
    }

    public CompletableFuture<DocumentUploadResult> addDocumentAsync(Path filePath, SourceMode mode,
                                                                    String knowledgeBaseName) {
        return getTargetManager(knowledgeBaseName).addDocumentAsync(filePath, mode);
    }

    public List<DocumentUploadResult> addDocuments(List<Path> filePaths) {
        return addDocuments(filePaths, (String) null);
    }

    public List<DocumentUploadResult> addDocuments(List<Path> filePaths, String knowledgeBaseName) {
        return getTargetManager(knowledgeBaseName).addDocuments(filePaths);
    }

    public List<DocumentUploadResult> addDocuments(List<Path> filePaths, SourceMode mode) {
        return getTargetManager(null).addDocuments(filePaths, mode);
    }

    public List<DocumentUploadResult> addDocuments(List<Path> filePaths, SourceMode mode,
                                                   String knowledgeBaseName) {
        return getTargetManager(knowledgeBaseName).addDocuments(filePaths, mode);
    }

    public CompletableFuture<List<DocumentUploadResult>> addDocumentsAsync(List<Path> filePaths) {
        return addDocumentsAsync(filePaths, (String) null);
    }

    public CompletableFuture<List<DocumentUploadResult>> addDocumentsAsync(List<Path> filePaths,
                                                                          String knowledgeBaseName) {
        return getTargetManager(knowledgeBaseName).addDocumentsAsync(filePaths);
    }

    public CompletableFuture<List<DocumentUploadResult>> addDocumentsAsync(List<Path> filePaths, SourceMode mode) {
        return getTargetManager(null).addDocumentsAsync(filePaths, mode);
    }

    public CompletableFuture<List<DocumentUploadResult>> addDocumentsAsync(List<Path> filePaths, SourceMode mode,
                                                                          String knowledgeBaseName) {
        return getTargetManager(knowledgeBaseName).addDocumentsAsync(filePaths, mode);
    }

    public void buildTree(List<Document> documents) {
        buildTree(documents, null);
    }

    public void buildTree(List<Document> documents, String knowledgeBaseName) {
        getTargetManager(knowledgeBaseName).buildTree(documents);
    }

    public SemanticTreeManager createKnowledgeBase(String name) {
        return knowledgeBaseManager.createKnowledgeBase(name);
    }

    public void deleteKnowledgeBase(String name) {
        knowledgeBaseManager.deleteKnowledgeBase(name);
    }

    public void switchKnowledgeBase(String name) {
        knowledgeBaseManager.switchKnowledgeBase(name);
    }

    public List<String> listKnowledgeBases() {
        return knowledgeBaseManager.listKnowledgeBases();
    }

    public SemanticTreeManager getCurrentKnowledgeBase() {
        return knowledgeBaseManager.getCurrentKnowledgeBase();
    }

    public boolean knowledgeBaseExists(String name) {
        return knowledgeBaseManager.exists(name);
    }

    private SemanticTreeManager getTargetManager(String knowledgeBaseName) {
        return knowledgeBaseName == null || knowledgeBaseName.isBlank()
                ? knowledgeBaseManager.getCurrentKnowledgeBase()
                : knowledgeBaseManager.getKnowledgeBase(knowledgeBaseName);
    }

    private Optional<CachedLLMProvider> getCachedProvider() {
        return knowledgeBaseManager.getLlmProvider() instanceof CachedLLMProvider cached
                ? Optional.of(cached) : Optional.empty();
    }

    public void clearCache() {
        getCachedProvider().ifPresent(CachedLLMProvider::clearCache);
    }

    public void clearCache(SmartCacheStrategy.RequestType type) {
        getCachedProvider().ifPresent(provider -> provider.clearCache(type));
    }

    public Map<SmartCacheStrategy.RequestType, CacheStatistics> getCacheStatistics() {
        return getCachedProvider().map(CachedLLMProvider::getStatistics).orElse(Map.of());
    }

    public CacheStatistics getCacheStatistics(SmartCacheStrategy.RequestType type) {
        return getCachedProvider().map(provider -> provider.getStatistics(type)).orElse(null);
    }

    public void warmupCache(Map<SmartCacheStrategy.RequestType, List<String>> warmupPrompts) {
        getCachedProvider().ifPresent(provider -> provider.warmupCache(warmupPrompts));
    }

    public void updateCacheConfig(SmartCacheStrategy.RequestType type, CacheConfig config) {
        getCachedProvider().ifPresent(provider -> provider.updateCacheConfig(type, config));
    }

    public long getTotalCacheSize() {
        return getCachedProvider().map(CachedLLMProvider::getTotalCacheSize).orElse(0L);
    }

    public long getCacheSize(SmartCacheStrategy.RequestType type) {
        return getCachedProvider().map(provider -> provider.getCacheSize(type)).orElse(0L);
    }

    public void logCacheStatistics() {
        getCachedProvider().ifPresent(CachedLLMProvider::logStatistics);
    }

    public LLMProvider getLlmProvider() {
        return knowledgeBaseManager.getLlmProvider();
    }
}
