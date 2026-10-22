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

package cn.nexon.zerovector.core;

import cn.nexon.zerovector.core.ai.LLMProvider;
import cn.nexon.zerovector.core.ai.LLMUsageStats;
import cn.nexon.zerovector.core.config.ConcurrencyProperties;
import cn.nexon.zerovector.core.document.DocumentChunker;
import cn.nexon.zerovector.core.document.StreamingDocumentChunker;
import cn.nexon.zerovector.core.document.comprehend.DocumentComprehendResult;
import cn.nexon.zerovector.core.document.comprehend.DocumentComprehender;
import cn.nexon.zerovector.core.exception.DocumentProcessingException;
import cn.nexon.zerovector.core.exception.NavigationException;
import cn.nexon.zerovector.core.exception.StorageException;
import cn.nexon.zerovector.core.hook.DefaultHookExecutor;
import cn.nexon.zerovector.core.hook.HookContext;
import cn.nexon.zerovector.core.hook.HookExecutor;
import cn.nexon.zerovector.core.hook.HookType;
import cn.nexon.zerovector.core.index.KeywordDictionary;
import cn.nexon.zerovector.core.model.ChunkingPolicy;
import cn.nexon.zerovector.core.model.Document;
import cn.nexon.zerovector.core.model.DocumentChunk;
import cn.nexon.zerovector.core.model.DocumentUploadResult;
import cn.nexon.zerovector.core.model.KeywordDefinition;
import cn.nexon.zerovector.core.model.NavigationResult;
import cn.nexon.zerovector.core.model.SemanticTree;
import cn.nexon.zerovector.core.model.SourceMode;
import cn.nexon.zerovector.core.model.SourceRef;
import cn.nexon.zerovector.core.model.SourceType;
import cn.nexon.zerovector.core.navigator.HybridNavigator;
import cn.nexon.zerovector.core.storage.ShardedTreeStorage;
import cn.nexon.zerovector.core.storage.config.ChunkStorageConfig;
import cn.nexon.zerovector.core.storage.config.DictionaryStorageConfig;
import cn.nexon.zerovector.core.storage.local.DocumentSourceStore;
import cn.nexon.zerovector.core.storage.local.LocalFileChunkStorage;
import cn.nexon.zerovector.core.storage.local.LocalFileDictionaryStorage;
import cn.nexon.zerovector.core.storage.spi.ChunkStorage;
import cn.nexon.zerovector.core.storage.spi.DictionaryStorage;
import cn.nexon.zerovector.core.tree.TreeBuilder;
import cn.nexon.zerovector.core.util.FileUtils;
import cn.nexon.zerovector.core.util.LLMExecutors;
import cn.nexon.zerovector.core.util.MD5Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 语义树管理器：章节切片、文档入库、语义树构建、持久化与导航的统一入口。
 *
 * <p>存储职责：
 * <ul>
 *   <li>{@link DocumentSourceStore}：大文件原文（托管副本或原文件引用）；</li>
 *   <li>{@link ChunkStorage}：接口直传小文档的内联正文（mmap）；</li>
 *   <li>语义树：章节摘要、关键词、源文件区间等导航元数据。</li>
 * </ul>
 *
 * <p>导航只读取摘要；{@link #readChunkContent(DocumentChunk)} 只在回答阶段被调用。
 */
public class SemanticTreeManager implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SemanticTreeManager.class);

    private static final String TREE_FILE = "semantic-tree.json";
    private static final String TREE_SHARD_DIR = "semantic-tree.d";
    private static final String CHUNKS_BASE = "chunks";
    private static final String DICTIONARY_FILE = "dictionary.json";
    private static final String SOURCES_DIR = "sources";
    private static final long DEFAULT_CHAPTER_SPLIT_THRESHOLD_CHARS = 100_000L;

    private final LLMProvider llmProvider;
    private final DocumentComprehender documentComprehender;
    private final HookExecutor hookExecutor;
    private final ConcurrencyProperties concurrencyProperties;
    private final ExecutorService executor;
    /** 公共异步 API 专用：避免外层任务占用限流信号量后等待内层任务而死锁。 */
    private final ExecutorService asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final ChunkStorage chunkStorage;
    private final DictionaryStorage dictionaryStorage;
    private final DocumentSourceStore sourceStore;
    private volatile KeywordDictionary dictionary;

    private final Path storagePath;
    private final Path treeFile;
    private final ShardedTreeStorage shardedTreeStorage;
    private final boolean useShardedStorage;
    private final long chapterSplitThresholdChars;

    private final ReentrantLock writeLock = new ReentrantLock();
    private final Set<String> knownMd5 = new HashSet<>();

    private volatile NavigationState navigationState;
    private volatile boolean closed;

    private record NavigationState(SemanticTree tree, HybridNavigator navigator) {
    }

    /** 一篇待处理文档：标题、源文件引用和章节区间。 */
    private record PendingDocument(
            String docId,
            String title,
            String md5,
            Charset charset,
            SourceMode mode,
            SourceRef sourceRef,
            List<SectionRef> sections,
            Path originalPath
    ) {
    }

    /** 章节元数据；text 非空表示内容型小文档，否则按源文件区间读取。 */
    private record SectionRef(String title, String text, long byteOffset, int byteLength,
                              int headingLevel) {
    }

    /** 并行理解结果；正文只在当前批次内保留，处理后即丢弃。 */
    private record SectionOutcome(int index, SectionRef section, String text,
                                  DocumentComprehendResult result) {
    }

    public SemanticTreeManager(LLMProvider llmProvider, DocumentComprehender documentComprehender,
                               Path storagePath, boolean useShardedStorage, int shardSize,
                               ConcurrencyProperties concurrencyProperties, HookExecutor hookExecutor) {
        this(llmProvider, documentComprehender, new LocalFileChunkStorage(), new LocalFileDictionaryStorage(),
                storagePath, useShardedStorage, shardSize, concurrencyProperties, hookExecutor);
    }

    public SemanticTreeManager(LLMProvider llmProvider, DocumentComprehender documentComprehender,
                               Path storagePath, boolean useShardedStorage,
                               ConcurrencyProperties concurrencyProperties) {
        this(llmProvider, documentComprehender, storagePath, useShardedStorage,
                ConcurrencyProperties.DEFAULT_SHARD_SIZE, concurrencyProperties, new DefaultHookExecutor());
    }

    public SemanticTreeManager(LLMProvider llmProvider, DocumentComprehender documentComprehender,
                               ChunkStorage chunkStorage, DictionaryStorage dictionaryStorage,
                               Path storagePath, boolean useShardedStorage, int shardSize,
                               ConcurrencyProperties concurrencyProperties, HookExecutor hookExecutor) {
        this(llmProvider, documentComprehender, chunkStorage, dictionaryStorage,
                storagePath, useShardedStorage, shardSize,
                DEFAULT_CHAPTER_SPLIT_THRESHOLD_CHARS, concurrencyProperties, hookExecutor);
    }

    public SemanticTreeManager(LLMProvider llmProvider, DocumentComprehender documentComprehender,
                               Path storagePath, boolean useShardedStorage, int shardSize,
                               long chapterSplitThresholdChars,
                               ConcurrencyProperties concurrencyProperties, HookExecutor hookExecutor) {
        this(llmProvider, documentComprehender, new LocalFileChunkStorage(), new LocalFileDictionaryStorage(),
                storagePath, useShardedStorage, shardSize, chapterSplitThresholdChars,
                concurrencyProperties, hookExecutor);
    }

    public SemanticTreeManager(LLMProvider llmProvider, DocumentComprehender documentComprehender,
                               ChunkStorage chunkStorage, DictionaryStorage dictionaryStorage,
                               Path storagePath, boolean useShardedStorage, int shardSize,
                               long chapterSplitThresholdChars,
                               ConcurrencyProperties concurrencyProperties, HookExecutor hookExecutor) {
        this.llmProvider = Objects.requireNonNull(llmProvider, "llmProvider");
        this.documentComprehender = Objects.requireNonNull(documentComprehender, "documentComprehender");
        this.concurrencyProperties = Objects.requireNonNull(concurrencyProperties, "concurrencyProperties");
        this.hookExecutor = Objects.requireNonNullElseGet(hookExecutor, DefaultHookExecutor::new);
        this.chunkStorage = Objects.requireNonNull(chunkStorage, "chunkStorage");
        this.dictionaryStorage = Objects.requireNonNull(dictionaryStorage, "dictionaryStorage");
        this.storagePath = Objects.requireNonNull(storagePath, "storagePath").toAbsolutePath();
        this.treeFile = this.storagePath.resolve(TREE_FILE);
        this.useShardedStorage = useShardedStorage;
        this.chapterSplitThresholdChars = Math.max(1_000, chapterSplitThresholdChars);
        this.executor = LLMExecutors.create(concurrencyProperties);

        try {
            Files.createDirectories(this.storagePath);
            this.sourceStore = new DocumentSourceStore(this.storagePath.resolve(SOURCES_DIR));

            this.chunkStorage.initialize(new ChunkStorageConfig(
                    this.storagePath.resolve(CHUNKS_BASE).toString()));
            this.dictionaryStorage.initialize(new DictionaryStorageConfig(
                    this.storagePath.resolve(DICTIONARY_FILE).toString()));
            this.dictionary = dictionaryStorage.load();

            if (useShardedStorage) {
                this.shardedTreeStorage = new ShardedTreeStorage(
                        this.storagePath.resolve(TREE_SHARD_DIR).toString(), shardSize);
                loadTreeState(shardedTreeStorage.loadTree());
            } else {
                this.shardedTreeStorage = null;
                loadTreeState(SemanticTree.loadFromFile(treeFile.toString()));
            }
            cleanupSourceCopies(currentTree());
        } catch (IOException e) {
            asyncExecutor.close();
            executor.close();
            closeQuietly();
            throw new StorageException(this.storagePath.toString(), "initialize", e);
        } catch (RuntimeException e) {
            asyncExecutor.close();
            executor.close();
            closeQuietly();
            throw e;
        }
    }

    private void loadTreeState(SemanticTree tree) {
        if (tree == null || tree.rootNode() == null) {
            return;
        }
        this.navigationState = new NavigationState(tree, createNavigator(tree));
        rebuildMd5Index(tree);
        logger.debug("已加载语义树: {} 个节点, {} 个章节", tree.nodes().size(), tree.chunks().size());
    }

    private HybridNavigator createNavigator(SemanticTree tree) {
        return new HybridNavigator(tree, dictionary, llmProvider,
                concurrencyProperties.getMaxNavigationSteps(), hookExecutor);
    }

    private void rebuildMd5Index(SemanticTree tree) {
        knownMd5.clear();
        for (String chunkId : tree.chunks().keySet()) {
            DocumentChunk chunk = tree.getChunk(chunkId);
            if (chunk != null && chunk.md5() != null) {
                knownMd5.add(chunk.md5());
            }
        }
    }

    /** 清理上次进程崩溃遗留、未被当前树引用的托管副本。 */
    private void cleanupSourceCopies(SemanticTree tree) {
        Set<String> keepIds = new HashSet<>();
        if (tree != null) {
            for (String chunkId : tree.chunks().keySet()) {
                DocumentChunk chunk = tree.getChunk(chunkId);
                if (chunk != null && chunk.source() != null
                        && chunk.source().type() == SourceType.MANAGED_COPY) {
                    keepIds.add(chunk.source().id());
                }
            }
        }
        sourceStore.cleanupManagedCopies(keepIds);
    }

    // ==================== 文档入库 ====================

    public DocumentUploadResult addDocument(Path filePath) {
        return addDocument(filePath, SourceMode.COPY, ChunkingPolicy.AUTO);
    }

    public DocumentUploadResult addDocument(Path filePath, SourceMode mode) {
        return addDocument(filePath, mode, ChunkingPolicy.AUTO);
    }

    public DocumentUploadResult addDocument(Path filePath, SourceMode mode, ChunkingPolicy policy) {
        return addDocuments(List.of(filePath), mode, policy).get(0);
    }

    public List<DocumentUploadResult> addDocuments(List<Path> filePaths) {
        return addDocuments(filePaths, SourceMode.COPY, ChunkingPolicy.AUTO);
    }

    public List<DocumentUploadResult> addDocuments(List<Path> filePaths, SourceMode mode) {
        return addDocuments(filePaths, mode, ChunkingPolicy.AUTO);
    }

    /**
     * 批量添加文档。
     *
     * @param filePaths 文件列表
     * @param mode      COPY（默认，托管副本）/ REFERENCE（引用原文件）/ INLINE（正文写 mmap）
     * @param policy    AUTO（默认，小文档整篇、大文档章节）/ WHOLE / CHAPTER
     */
    public List<DocumentUploadResult> addDocuments(List<Path> filePaths, SourceMode mode, ChunkingPolicy policy) {
        if (filePaths == null || filePaths.isEmpty()) {
            return List.of();
        }
        writeLock.lock();
        try {
            ensureOpen();
            return addDocumentsLocked(filePaths, mode == null ? SourceMode.COPY : mode,
                    policy == null ? ChunkingPolicy.AUTO : policy);
        } finally {
            writeLock.unlock();
        }
    }

    private List<DocumentUploadResult> addDocumentsLocked(List<Path> filePaths, SourceMode mode,
                                                          ChunkingPolicy policy) {
        List<DocumentUploadResult> results = new ArrayList<>(filePaths.size());
        List<PendingDocument> pending = new ArrayList<>();
        List<Integer> pendingIndices = new ArrayList<>();
        Map<Integer, DocumentUploadResult> failures = new HashMap<>();
        Set<String> batchMd5 = new HashSet<>();

        for (int i = 0; i < filePaths.size(); i++) {
            Path filePath = filePaths.get(i);
            long startTime = System.currentTimeMillis();
            trigger(HookType.DOCUMENT_UPLOAD_START,
                    HookContext.builder(HookType.DOCUMENT_UPLOAD_START)
                            .data("filePath", filePath.toString()));

            try {
                String md5 = MD5Util.calculateMD5(filePath);
                if (knownMd5.contains(md5) || !batchMd5.add(md5)) {
                    results.add(null);
                    failures.put(i, DocumentUploadResult.skipped(null, "文档已存在，跳过处理"));
                    trigger(HookType.DOCUMENT_UPLOAD_END,
                            HookContext.builder(HookType.DOCUMENT_UPLOAD_END)
                                    .data("filePath", filePath.toString())
                                    .data("skipped", true)
                                    .data("reason", "Document already exists")
                                    .durationMillis(System.currentTimeMillis() - startTime));
                    continue;
                }

                PendingDocument document = prepareFileDocument(
                        "doc_" + UUID.randomUUID(), filePath, md5, mode, policy);
                results.add(null);
                pending.add(document);
                pendingIndices.add(i);
            } catch (Exception e) {
                logger.error("读取文件失败: {}", filePath, e);
                results.add(null);
                failures.put(i, DocumentUploadResult.failure(null, "读取失败: " + e.getMessage()));
                trigger(HookType.DOCUMENT_UPLOAD_ERROR,
                        HookContext.builder(HookType.DOCUMENT_UPLOAD_ERROR)
                                .data("filePath", filePath.toString())
                                .durationMillis(System.currentTimeMillis() - startTime)
                                .error(e));
            }
        }

        if (pending.isEmpty()) {
            for (Map.Entry<Integer, DocumentUploadResult> entry : failures.entrySet()) {
                results.set(entry.getKey(), entry.getValue());
            }
            return results;
        }

        try {
            Map<String, LLMUsageStats> docStats = processPending(pending, false);
            for (int j = 0; j < pending.size(); j++) {
                PendingDocument document = pending.get(j);
                knownMd5.add(document.md5());
                results.set(pendingIndices.get(j), DocumentUploadResult.success(
                        document.docId(), document.title(),
                        docStats.getOrDefault(document.docId(), new LLMUsageStats())));
                trigger(HookType.DOCUMENT_UPLOAD_END,
                        HookContext.builder(HookType.DOCUMENT_UPLOAD_END)
                                .data("filePath", document.originalPath() == null
                                        ? null : document.originalPath().toString())
                                .data("documentTitle", document.title())
                                .data("sectionCount", document.sections().size())
                                .data("llmUsageStats", docStats.get(document.docId())));
            }
        } catch (RuntimeException e) {
            logger.error("批量入库失败", e);
            cleanupPending(pending);
            for (int i = 0; i < results.size(); i++) {
                if (results.get(i) == null) {
                    results.set(i, DocumentUploadResult.failure(null, "处理失败: " + e.getMessage()));
                }
            }
            return results;
        }

        for (Map.Entry<Integer, DocumentUploadResult> entry : failures.entrySet()) {
            results.set(entry.getKey(), entry.getValue());
        }
        return results;
    }

    private PendingDocument prepareFileDocument(String docId, Path filePath, String md5,
                                                SourceMode mode, ChunkingPolicy policy) throws IOException {
        String title = FileUtils.getFileName(filePath);
        Charset detected = FileUtils.detectCharset(filePath);
        DocumentSourceStore.CopySession copy = null;
        StreamingDocumentChunker.Result parsed = null;

        try {
            List<Charset> candidates = candidateCharsets(detected);
            for (int attempt = 0; attempt < candidates.size(); attempt++) {
                Charset candidate = candidates.get(attempt);
                copy = mode == SourceMode.COPY ? sourceStore.beginCopy(title) : null;
                try {
                    parsed = StreamingDocumentChunker.parse(
                            filePath, candidate, sectionChars(),
                            copy != null ? copy.writer()::write : null);
                    detected = candidate;
                    break;
                } catch (CharacterCodingException e) {
                    if (copy != null) {
                        copy.abort();
                        copy = null;
                    }
                    if (attempt == candidates.size() - 1) {
                        throw e;
                    }
                } catch (IOException | RuntimeException e) {
                    if (copy != null) {
                        copy.abort();
                        copy = null;
                    }
                    throw e;
                }
            }
            if (parsed == null) {
                throw new IOException("无法解码文件: " + filePath);
            }

            if (parsed.sections().isEmpty()) {
                throw new DocumentProcessingException(docId, "chunk", "文档没有可用章节");
            }

            boolean chaptered = policy == ChunkingPolicy.CHAPTER
                    || (policy == ChunkingPolicy.AUTO && parsed.totalChars() > chapterSplitThresholdChars);
            List<SectionRef> sections;
            if (chaptered) {
                sections = parsed.sections().stream()
                        .map(section -> new SectionRef(section.title(), null,
                                section.byteOffset(), section.byteLength(), section.headingLevel()))
                        .toList();
            } else {
                sections = List.of(new SectionRef(title, null, 0,
                        (int) Math.min(Integer.MAX_VALUE, parsed.totalBytes()), 0));
            }

            SourceRef sourceRef;
            Charset sourceCharset;
            if (copy != null) {
                sourceRef = copy.finish();
                sourceCharset = StandardCharsets.UTF_8;
            } else if (mode == SourceMode.REFERENCE) {
                sourceRef = sourceStore.reference(filePath);
                sourceCharset = detected;
            } else {
                sourceRef = null;
                sourceCharset = detected;
            }

            return new PendingDocument(docId, title, md5, sourceCharset, mode, sourceRef,
                    sections, filePath.toAbsolutePath());
        } catch (IOException | RuntimeException e) {
            if (copy != null) {
                copy.abort();
            }
            throw e;
        } finally {
            if (copy != null) {
                copy.close();
            }
        }
    }

    private PendingDocument prepareContentDocument(Document document, ChunkingPolicy policy) {
        if (document.content() == null || document.content().isBlank()) {
            throw new DocumentProcessingException(document.id(), "readFile", "文档内容为空");
        }
        List<SectionRef> sections = splitSections(document.content(), document.title(), policy).stream()
                .map(section -> new SectionRef(section.title(), section.text(), 0, 0, section.headingLevel()))
                .toList();
        if (sections.isEmpty()) {
            throw new DocumentProcessingException(document.id(), "chunk", "文档没有可用章节");
        }
        String md5 = document.md5() != null
                ? document.md5() : MD5Util.calculateMD5(document.content());
        // 内容型文档没有源文件，正文只能内联写入 ChunkStorage
        return new PendingDocument(document.id(), document.title(), md5,
                StandardCharsets.UTF_8, SourceMode.INLINE,
                null, sections, document.filePath() == null ? null : Paths.get(document.filePath()));
    }

    private int sectionChars() {
        return (int) Math.max(200,
                Math.min(documentComprehender.getMaxChunkChars(), chapterSplitThresholdChars));
    }

    /** 采样编码可能误判，严格解码失败时按常见编码回退重试。 */
    private List<Charset> candidateCharsets(Charset detected) {
        List<Charset> candidates = new ArrayList<>();
        candidates.add(detected);
        if (!StandardCharsets.UTF_8.equals(detected)) {
            candidates.add(StandardCharsets.UTF_8);
        }
        if (Charset.isSupported("GBK") && !Charset.forName("GBK").equals(detected)) {
            candidates.add(Charset.forName("GBK"));
        }
        if (!StandardCharsets.ISO_8859_1.equals(detected)) {
            candidates.add(StandardCharsets.ISO_8859_1);
        }
        return candidates;
    }

    /**
     * 默认小文档整篇、大文档章节；CHAPTER/WHOLE 可显式覆盖。
     */
    private List<DocumentChunker.Section> splitSections(String content, String title, ChunkingPolicy policy) {
        ChunkingPolicy effective = policy == null ? ChunkingPolicy.AUTO : policy;
        boolean chaptered = effective == ChunkingPolicy.CHAPTER
                || (effective == ChunkingPolicy.AUTO && content.length() > chapterSplitThresholdChars);
        if (!chaptered) {
            return List.of(DocumentChunker.whole(title, content));
        }
        return DocumentChunker.split(content, sectionChars());
    }

    /**
     * 章节理解 → 分片写入 → 建树/更新 → 提交。
     *
     * @return 每个文档（按 docId）的 LLM 统计
     */
    private Map<String, LLMUsageStats> processPending(List<PendingDocument> pending, boolean replaceTree) {
        Map<String, LLMUsageStats> docStats = new LinkedHashMap<>();
        Map<String, DocumentChunk> metadataChunks = new LinkedHashMap<>();
        List<TreeBuilder.DocumentInput> documentInputs = new ArrayList<>();
        int batchSize = Math.max(1, concurrencyProperties.getMaxConcurrentRequests());

        for (PendingDocument document : pending) {
            LLMUsageStats stats = new LLMUsageStats();
            docStats.put(document.docId(), stats);
            List<TreeBuilder.ChapterInput> chapters = new ArrayList<>();
            List<SectionRef> sections = document.sections();

            // 以有界批次并行理解：批次内正文总量有上限，不会因大文件而无限驻留内存。
            for (int start = 0; start < sections.size(); start += batchSize) {
                int end = Math.min(start + batchSize, sections.size());
                List<CompletableFuture<SectionOutcome>> futures = new ArrayList<>(end - start);
                for (int i = start; i < end; i++) {
                    int index = i;
                    SectionRef section = sections.get(i);
                    futures.add(CompletableFuture.supplyAsync(() -> {
                        String text = readSectionText(document, section);
                        Document sectionDoc = new Document(sectionId(document, index),
                                section.title(), text, null, document.md5(), Instant.now(),
                                sectionMetadata(document, section, index));
                        DocumentComprehendResult result = documentComprehender.comprehend(sectionDoc);
                        return new SectionOutcome(index, section, text, result);
                    }, executor));
                }

                for (CompletableFuture<SectionOutcome> future : futures) {
                    SectionOutcome outcome;
                    try {
                        outcome = future.join();
                    } catch (CompletionException e) {
                        Throwable cause = e.getCause() != null ? e.getCause() : e;
                        if (cause instanceof RuntimeException runtime) {
                            throw runtime;
                        }
                        throw new DocumentProcessingException(document.docId(), "comprehend", cause);
                    }

                    int index = outcome.index();
                    SectionRef section = outcome.section();
                    String sectionId = sectionId(document, index);
                    DocumentComprehendResult result = outcome.result();
                    stats.merge(result.llmUsageStats());

                    DocumentChunk chunk = createChunk(document, section, result,
                            outcome.text(), sectionId, index);
                    if (chunk.hasContent()) {
                        // 流式/内联章节：立即写 mmap，随后丢弃正文，避免整篇驻留内存
                        chunkStorage.saveChunk(chunk);
                    }
                    metadataChunks.put(sectionId, chunk.metadataOnly());
                    chapters.add(new TreeBuilder.ChapterInput(sectionId, section.title(), result));
                }
            }

            DocumentComprehendResult documentResult = aggregateDocumentResult(document, chapters);
            documentInputs.add(new TreeBuilder.DocumentInput(
                    document.docId(), document.title(), documentResult, chapters));
        }

        KeywordDictionary workingDictionary = replaceTree ? new KeywordDictionary() : dictionary;
        TreeBuilder builder = new TreeBuilder(llmProvider, workingDictionary, executor);
        SemanticTree existing = currentTree();
        boolean freshTree = replaceTree || existing == null;
        if (freshTree && !replaceTree) {
            // 没有现有树时字典可能是旧文件残留，重建字典避免无效映射
            workingDictionary = new KeywordDictionary();
            builder = new TreeBuilder(llmProvider, workingDictionary, executor);
        }
        TreeBuilder.TreeBuildResult buildResult = freshTree
                ? builder.build(documentInputs, metadataChunks)
                : builder.updateTree(existing, documentInputs, metadataChunks);
        if (!pending.isEmpty() && buildResult.llmUsageStats() != null) {
            // 聚类/节点元数据是整批共享成本，归到首个文档，避免统计丢失
            docStats.get(pending.get(0).docId()).merge(buildResult.llmUsageStats());
        }

        if (freshTree) {
            Map<String, DocumentChunk> previousChunks = existing == null
                    ? Map.of() : new HashMap<>(existing.chunks());
            commit(buildResult.tree(), workingDictionary);
            deleteOrphans(previousChunks, buildResult.tree());
        } else {
            commit(buildResult.tree());
        }
        return docStats;
    }

    private DocumentComprehendResult aggregateDocumentResult(PendingDocument document,
                                                              List<TreeBuilder.ChapterInput> chapters) {
        if (chapters.size() == 1) {
            DocumentComprehendResult single = chapters.get(0).result();
            if (single != null) {
                return single;
            }
        }

        StringBuilder summary = new StringBuilder(document.title()).append("：");
        Map<String, KeywordDefinition> keywords = new LinkedHashMap<>();
        Set<String> entities = new LinkedHashSet<>();
        Set<String> examples = new LinkedHashSet<>();
        for (TreeBuilder.ChapterInput chapter : chapters) {
            DocumentComprehendResult result = chapter.result();
            if (result == null) {
                continue;
            }
            if (result.summary() != null && !result.summary().isBlank()) {
                summary.append(result.summary()).append("；");
            }
            for (KeywordDefinition definition : result.keywordDefinitions()) {
                keywords.putIfAbsent(definition.normalizedKeyword(), definition);
            }
            entities.addAll(result.entities());
            examples.addAll(result.exampleQuestions());
        }
        String documentSummary = summary.length() > 1000
                ? summary.substring(0, 1000)
                : summary.toString();
        return new DocumentComprehendResult(documentSummary, new ArrayList<>(keywords.values()),
                new ArrayList<>(entities), new ArrayList<>(examples), new LLMUsageStats());
    }

    private String sectionId(PendingDocument document, int index) {
        return document.docId() + "#" + index;
    }

    private Map<String, Object> sectionMetadata(PendingDocument document, SectionRef section,
                                                int index) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("document_id", document.docId());
        metadata.put("document_title", document.title());
        metadata.put("section_title", section.title());
        metadata.put("section_index", index);
        metadata.put("heading_level", section.headingLevel());
        metadata.put("source_mode", document.mode().name());
        if (document.originalPath() != null) {
            metadata.put("original_file", document.originalPath().toString());
        }
        return metadata;
    }

    private String readSectionText(PendingDocument document, SectionRef section) {
        if (section.text() != null) {
            return section.text();
        }
        SourceRef ref = document.sourceRef();
        if (ref == null) {
            ref = new SourceRef(SourceType.ORIGINAL, document.originalPath().toString());
        }
        try {
            return sourceStore.read(ref, section.byteOffset(), section.byteLength(),
                    document.charset().name());
        } catch (IOException e) {
            throw new DocumentProcessingException(document.docId(), "readSection", e);
        }
    }

    private DocumentChunk createChunk(PendingDocument document, SectionRef section,
                                      DocumentComprehendResult result, String text,
                                      String sectionId, int index) {
        Map<String, Object> metadata = sectionMetadata(document, section, index);
        String summary = result.summary() == null ? "" : result.summary();
        if (document.mode() == SourceMode.INLINE) {
            return DocumentChunk.withContent(sectionId, text, summary, document.md5(), metadata);
        }
        return DocumentChunk.withSource(sectionId, summary, document.md5(), metadata,
                document.sourceRef(), section.byteOffset(), section.byteLength(),
                document.charset().name());
    }

    // ==================== 批量重建 ====================

    /**
     * 用给定文档重建整棵语义树（替换现有树）。
     */
    public void buildTree(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        writeLock.lock();
        try {
            ensureOpen();
            List<PendingDocument> pending = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            try {
                for (Document document : documents) {
                    if (!ids.add(document.id())) {
                        throw new IllegalArgumentException("Duplicate document id: " + document.id());
                    }
                    if (document.content() != null) {
                        pending.add(prepareContentDocument(document, ChunkingPolicy.AUTO));
                    } else if (document.filePath() != null) {
                        try {
                            String md5 = document.md5() != null
                                    ? document.md5() : MD5Util.calculateMD5(Paths.get(document.filePath()));
                            pending.add(prepareFileDocument(document.id(), Paths.get(document.filePath()),
                                    md5, SourceMode.COPY, ChunkingPolicy.AUTO));
                        } catch (IOException e) {
                            throw new DocumentProcessingException(document.id(), "readFile", e);
                        }
                    } else {
                        throw new DocumentProcessingException(document.id(), "readFile", "缺少正文和文件路径");
                    }
                }
            } catch (RuntimeException e) {
                cleanupPending(pending);
                throw e;
            }

            long startTime = System.currentTimeMillis();
            trigger(HookType.TREE_BUILD_START,
                    HookContext.builder(HookType.TREE_BUILD_START)
                            .data("documentCount", pending.size()));
            Map<String, LLMUsageStats> stats;
            try {
                stats = processPending(pending, true);
            } catch (RuntimeException e) {
                cleanupPending(pending);
                trigger(HookType.TREE_BUILD_ERROR,
                        HookContext.builder(HookType.TREE_BUILD_ERROR)
                                .data("documentCount", pending.size())
                                .durationMillis(System.currentTimeMillis() - startTime)
                                .error(e));
                throw e;
            }

            // 提交成功后只做观测，任何异常都不能回滚已提交的树
            rebuildMd5Index(currentTree());
            LLMUsageStats total = new LLMUsageStats();
            stats.values().forEach(total::merge);
            trigger(HookType.TREE_BUILD_END,
                    HookContext.builder(HookType.TREE_BUILD_END)
                            .data("documentCount", pending.size())
                            .data("nodeCount", currentTree().nodes().size())
                            .data("llmUsageStats", total)
                            .durationMillis(System.currentTimeMillis() - startTime));
        } finally {
            writeLock.unlock();
        }
    }

    private void cleanupPending(List<PendingDocument> pending) {
        for (PendingDocument document : pending) {
            for (int i = 0; i < document.sections().size(); i++) {
                try {
                    chunkStorage.deleteChunk(sectionId(document, i));
                } catch (RuntimeException ignored) {
                    // best effort
                }
            }
            if (document.sourceRef() != null && document.sourceRef().type() == SourceType.MANAGED_COPY) {
                sourceStore.delete(document.sourceRef());
            }
        }
    }

    /** 删除旧树中不再被新树引用的章节正文和托管源文件。 */
    private void deleteOrphans(Map<String, DocumentChunk> previousChunks, SemanticTree current) {
        if (previousChunks.isEmpty() || current == null) {
            return;
        }
        Set<String> currentIds = current.chunks().keySet();
        Set<SourceRef> currentSources = new HashSet<>();
        current.chunks().values().forEach(chunk -> {
            if (chunk.source() != null) {
                currentSources.add(chunk.source());
            }
        });

        for (Map.Entry<String, DocumentChunk> entry : previousChunks.entrySet()) {
            DocumentChunk chunk = entry.getValue();
            if (chunk == null) {
                continue;
            }
            if (currentIds.contains(entry.getKey())) {
                // 同一章节可能从文件源切换为内联，旧托管副本不再被引用
                if (chunk.source() != null && !currentSources.contains(chunk.source())) {
                    sourceStore.delete(chunk.source());
                }
                continue;
            }
            try {
                chunkStorage.deleteChunk(chunk.id());
            } catch (RuntimeException e) {
                logger.warn("清理旧分片失败: {}", chunk.id(), e);
            }
            if (chunk.source() != null && !currentSources.contains(chunk.source())) {
                sourceStore.delete(chunk.source());
            }
        }
    }

    private void commit(SemanticTree tree) {
        commit(tree, dictionary);
    }

    private void commit(SemanticTree tree, KeywordDictionary newDictionary) {
        saveTree(tree, newDictionary);
        this.dictionary = newDictionary;
        this.navigationState = new NavigationState(tree, createNavigator(tree));
    }

    private SemanticTree currentTree() {
        NavigationState state = navigationState;
        return state == null ? null : state.tree();
    }

    // ==================== 查询 ====================

    /**
     * 按需读取章节正文：这是唯一会触发原文 IO 的入口。
     */
    public String readChunkContent(DocumentChunk chunk) {
        if (chunk == null) {
            return null;
        }
        if (chunk.hasContent()) {
            return chunk.content();
        }
        if (chunk.hasSource()) {
            try {
                return sourceStore.read(chunk.source(), chunk.sourceOffset(), chunk.sourceLength(),
                        chunk.sourceCharset() == null ? StandardCharsets.UTF_8.name() : chunk.sourceCharset());
            } catch (IOException e) {
                throw new StorageException(chunk.source().id(), "readChunkContent", e);
            }
        }
        return chunkStorage.getChunkContent(chunk.id());
    }

    public NavigationResult navigate(String query) {
        NavigationState state = navigationState;
        if (state == null || state.tree() == null) {
            throw new NavigationException(query, null, NavigationException.ERROR_CODE_TREE_NOT_INITIALIZED,
                    "Semantic tree not built yet");
        }

        long startTime = System.currentTimeMillis();
        trigger(HookType.NAVIGATION_START,
                HookContext.builder(HookType.NAVIGATION_START).data("query", query));
        try {
            NavigationResult result = state.navigator().navigate(query);
            trigger(HookType.NAVIGATION_END,
                    HookContext.builder(HookType.NAVIGATION_END)
                            .data("query", query)
                            .data("resultCount", result.documents().size())
                            .data("llmUsageStats", result.llmUsageStats())
                            .durationMillis(System.currentTimeMillis() - startTime));
            return result;
        } catch (NavigationException e) {
            trigger(HookType.NAVIGATION_ERROR,
                    HookContext.builder(HookType.NAVIGATION_ERROR)
                            .data("query", query)
                            .durationMillis(System.currentTimeMillis() - startTime)
                            .error(e));
            throw e;
        } catch (RuntimeException e) {
            NavigationException wrapped = new NavigationException(query,
                    state.tree().rootNode() != null ? state.tree().rootNode().id() : null,
                    NavigationException.ERROR_CODE_LLM_DECISION_FAILED, "Navigation failed", e);
            trigger(HookType.NAVIGATION_ERROR,
                    HookContext.builder(HookType.NAVIGATION_ERROR)
                            .data("query", query)
                            .durationMillis(System.currentTimeMillis() - startTime)
                            .error(wrapped));
            throw wrapped;
        }
    }

    // ==================== 持久化与生命周期 ====================

    public void saveTree() {
        writeLock.lock();
        try {
            ensureOpen();
            SemanticTree tree = currentTree();
            if (tree != null) {
                saveTree(tree);
            }
        } finally {
            writeLock.unlock();
        }
    }

    private void saveTree(SemanticTree tree) {
        saveTree(tree, dictionary);
    }

    /** 保存顺序：正文 → 字典 → 树。树文件作为提交点最后写入。 */
    private void saveTree(SemanticTree tree, KeywordDictionary dictionaryToSave) {
        try {
            chunkStorage.flush();
            dictionaryStorage.save(dictionaryToSave);
            if (useShardedStorage) {
                shardedTreeStorage.saveTree(tree);
            } else {
                tree.saveToFile(treeFile.toString());
            }
            logger.debug("语义树已保存: {}", storagePath);
        } catch (IOException e) {
            throw new StorageException(storagePath.toString(), "saveTree", e);
        }
    }

    public SemanticTree getSemanticTree() {
        return currentTree();
    }

    public boolean isTreeLoaded() {
        return currentTree() != null;
    }

    public boolean isUsingShardedStorage() {
        return useShardedStorage;
    }

    public ChunkStorage getChunkStorage() {
        return chunkStorage;
    }

    public DictionaryStorage getDictionaryStorage() {
        return dictionaryStorage;
    }

    public KeywordDictionary getKeywordDictionary() {
        return dictionary;
    }

    public CompletableFuture<DocumentUploadResult> addDocumentAsync(Path filePath) {
        return addDocumentAsync(filePath, SourceMode.COPY);
    }

    public CompletableFuture<DocumentUploadResult> addDocumentAsync(Path filePath, SourceMode mode) {
        return addDocumentAsync(filePath, mode, ChunkingPolicy.AUTO);
    }

    public CompletableFuture<DocumentUploadResult> addDocumentAsync(Path filePath, SourceMode mode,
                                                                    ChunkingPolicy policy) {
        return CompletableFuture.supplyAsync(() -> addDocument(filePath, mode, policy), asyncExecutor);
    }

    public CompletableFuture<List<DocumentUploadResult>> addDocumentsAsync(List<Path> filePaths) {
        return addDocumentsAsync(filePaths, SourceMode.COPY, ChunkingPolicy.AUTO);
    }

    public CompletableFuture<List<DocumentUploadResult>> addDocumentsAsync(List<Path> filePaths, SourceMode mode) {
        return addDocumentsAsync(filePaths, mode, ChunkingPolicy.AUTO);
    }

    public CompletableFuture<List<DocumentUploadResult>> addDocumentsAsync(List<Path> filePaths, SourceMode mode,
                                                                           ChunkingPolicy policy) {
        return CompletableFuture.supplyAsync(() -> addDocuments(filePaths, mode, policy), asyncExecutor);
    }

    @Override
    public void close() {
        writeLock.lock();
        try {
            if (closed) {
                return;
            }
            SemanticTree tree = currentTree();
            if (tree != null) {
                try {
                    saveTree(tree);
                } catch (RuntimeException e) {
                    logger.error("关闭前保存语义树失败", e);
                }
            }
            closed = true;
        } finally {
            writeLock.unlock();
        }

        // 先等待异步任务结束，再关闭存储
        asyncExecutor.close();
        executor.close();
        closeQuietly();
        logger.debug("SemanticTreeManager 已关闭: {}", storagePath);
    }

    private void closeQuietly() {
        try {
            chunkStorage.close();
        } catch (Exception e) {
            logger.warn("关闭 ChunkStorage 失败", e);
        }
        try {
            dictionaryStorage.close();
        } catch (Exception e) {
            logger.warn("关闭 DictionaryStorage 失败", e);
        }
        if (shardedTreeStorage != null) {
            shardedTreeStorage.close();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("SemanticTreeManager is closed");
        }
    }

    /** Hook 失败不得影响主流程。 */
    private void trigger(HookType type, HookContext.Builder builder) {
        try {
            hookExecutor.executeHooks(type, builder);
        } catch (RuntimeException e) {
            logger.warn("Hook 执行失败: {}", type, e);
        }
    }
}
