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
import cn.nexon.zerovector.core.config.ConcurrencyProperties;
import cn.nexon.zerovector.core.document.comprehend.DocumentComprehender;
import cn.nexon.zerovector.core.exception.StorageException;
import cn.nexon.zerovector.core.hook.DefaultHookExecutor;
import cn.nexon.zerovector.core.hook.HookExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 多知识库管理器。
 *
 * <p>每个知识库独占一个目录，懒加载；创建/删除/切换对并发调用安全。
 */
public final class KnowledgeBaseManager implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeBaseManager.class);
    private static final String DEFAULT_KNOWLEDGE_BASE = "default";
    private static final String NAME_PATTERN = "^[a-zA-Z0-9_-]+$";
    private static final String TREE_FILE = "semantic-tree.json";
    private static final String TREE_DIR = "semantic-tree.d";
    private static final String MARKER_FILE = ".zerovector-kb";

    private final Map<String, SemanticTreeManager> managers = new ConcurrentHashMap<>();
    private final LLMProvider llmProvider;
    private final DocumentComprehender documentComprehender;
    private final ConcurrencyProperties concurrencyProperties;
    private final HookExecutor hookExecutor;
    private final Path baseStoragePath;
    private final boolean useShardedStorage;
    private final int shardSize;
    private final long chapterSplitThresholdChars;

    private volatile String currentKnowledgeBase = DEFAULT_KNOWLEDGE_BASE;
    private volatile boolean initialized;
    private volatile boolean closed;

    public KnowledgeBaseManager(LLMProvider llmProvider, DocumentComprehender documentComprehender,
                                ConcurrencyProperties concurrencyProperties, String baseStoragePath,
                                boolean useShardedStorage, int shardSize, HookExecutor hookExecutor) {
        this(llmProvider, documentComprehender, concurrencyProperties, baseStoragePath,
                useShardedStorage, shardSize, 100_000L, hookExecutor);
    }

    public KnowledgeBaseManager(LLMProvider llmProvider, DocumentComprehender documentComprehender,
                                ConcurrencyProperties concurrencyProperties, String baseStoragePath,
                                boolean useShardedStorage, int shardSize,
                                long chapterSplitThresholdChars, HookExecutor hookExecutor) {
        this.llmProvider = Objects.requireNonNull(llmProvider, "llmProvider");
        this.documentComprehender = Objects.requireNonNull(documentComprehender, "documentComprehender");
        this.concurrencyProperties = Objects.requireNonNull(concurrencyProperties, "concurrencyProperties");
        this.baseStoragePath = Paths.get(Objects.requireNonNull(baseStoragePath, "baseStoragePath"))
                .toAbsolutePath();
        this.useShardedStorage = useShardedStorage;
        this.shardSize = shardSize;
        this.chapterSplitThresholdChars = Math.max(1_000L, chapterSplitThresholdChars);
        this.hookExecutor = Objects.requireNonNullElseGet(hookExecutor, DefaultHookExecutor::new);
    }

    public KnowledgeBaseManager(LLMProvider llmProvider, DocumentComprehender documentComprehender,
                                ConcurrencyProperties concurrencyProperties, String baseStoragePath,
                                HookExecutor hookExecutor) {
        this(llmProvider, documentComprehender, concurrencyProperties, baseStoragePath,
                true, ConcurrencyProperties.DEFAULT_SHARD_SIZE, hookExecutor);
    }

    /** 初始化：加载已有知识库，不存在时创建默认库。重复调用为空操作。 */
    public synchronized void initialize() {
        if (closed) {
            throw new IllegalStateException("KnowledgeBaseManager is closed");
        }
        if (initialized) {
            return;
        }

        try {
            Files.createDirectories(baseStoragePath);
        } catch (IOException e) {
            throw new StorageException(baseStoragePath.toString(), "initialize", e);
        }

        initialized = true;
        try {
            loadExistingKnowledgeBases();
            if (!managers.containsKey(DEFAULT_KNOWLEDGE_BASE)) {
                createKnowledgeBase(DEFAULT_KNOWLEDGE_BASE);
            }
            currentKnowledgeBase = DEFAULT_KNOWLEDGE_BASE;
            logger.info("知识库管理器初始化完成，已加载 {} 个知识库", managers.size());
        } catch (RuntimeException e) {
            initialized = false;
            throw e;
        }
    }

    private void loadExistingKnowledgeBases() {
        List<Path> kbDirectories;
        try (Stream<Path> stream = Files.list(baseStoragePath)) {
            kbDirectories = stream.filter(Files::isDirectory).toList();
        } catch (IOException e) {
            throw new StorageException(baseStoragePath.toString(), "loadExistingKnowledgeBases", e);
        }

        for (Path kbDir : kbDirectories) {
            String name = kbDir.getFileName().toString();
            if (!isValidKnowledgeBaseName(name) || !isKnowledgeBaseDirectory(kbDir)) {
                continue;
            }
            try {
                managers.put(name, createManager(name, kbDir));
                logger.debug("已加载知识库: {}", name);
            } catch (RuntimeException e) {
                logger.error("加载知识库 {} 失败，跳过", name, e);
            }
        }
    }

    /** 目录必须包含 marker / 树文件 / 分片元数据，避免把上传目录等误识别为知识库。 */
    private boolean isKnowledgeBaseDirectory(Path path) {
        return Files.isRegularFile(path.resolve(MARKER_FILE))
                || Files.isRegularFile(path.resolve(TREE_FILE))
                || Files.isRegularFile(path.resolve(TREE_DIR).resolve("metadata.json"));
    }

    private SemanticTreeManager createManager(String name, Path path) {
        SemanticTreeManager manager = new SemanticTreeManager(
                llmProvider, documentComprehender, path, useShardedStorage, shardSize,
                chapterSplitThresholdChars, concurrencyProperties, hookExecutor);
        try {
            Files.writeString(path.resolve(MARKER_FILE), "zerovector\n");
        } catch (IOException e) {
            manager.close();
            throw new StorageException(path.toString(), "createKnowledgeBase", e);
        }
        logger.debug("知识库 {} 已就绪: {}", name, path);
        return manager;
    }

    public synchronized SemanticTreeManager createKnowledgeBase(String name) {
        ensureOpen();
        validateKnowledgeBaseName(name);
        if (managers.containsKey(name)) {
            throw new IllegalArgumentException("知识库 '" + name + "' 已存在");
        }

        Path path = getKnowledgeBaseStoragePath(name);
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new StorageException(path.toString(), "createKnowledgeBase", e);
        }

        SemanticTreeManager manager = null;
        try {
            manager = createManager(name, path);
            managers.put(name, manager);
            logger.info("知识库 {} 创建成功", name);
            return manager;
        } catch (RuntimeException e) {
            if (manager != null) {
                manager.close();
            }
            throw e;
        }
    }

    public synchronized void deleteKnowledgeBase(String name) {
        ensureOpen();
        validateKnowledgeBaseName(name);
        if (DEFAULT_KNOWLEDGE_BASE.equals(name)) {
            throw new IllegalArgumentException("不能删除默认知识库 '" + DEFAULT_KNOWLEDGE_BASE + "'");
        }

        SemanticTreeManager manager = managers.remove(name);
        if (manager == null) {
            throw new IllegalArgumentException("知识库 '" + name + "' 不存在");
        }
        manager.close();

        Path path = getKnowledgeBaseStoragePath(name);
        deleteDirectory(path);

        if (currentKnowledgeBase.equals(name)) {
            currentKnowledgeBase = DEFAULT_KNOWLEDGE_BASE;
        }
        logger.info("知识库 {} 已删除", name);
    }

    public SemanticTreeManager getKnowledgeBase(String name) {
        ensureOpen();
        if (name == null || name.isBlank()) {
            return getCurrentKnowledgeBase();
        }
        SemanticTreeManager manager = managers.get(name);
        if (manager == null) {
            throw new IllegalArgumentException("知识库 '" + name + "' 不存在");
        }
        return manager;
    }

    public SemanticTreeManager getCurrentKnowledgeBase() {
        ensureOpen();
        SemanticTreeManager manager = managers.get(currentKnowledgeBase);
        if (manager == null) {
            throw new IllegalStateException("当前知识库不存在: " + currentKnowledgeBase);
        }
        return manager;
    }

    public synchronized void switchKnowledgeBase(String name) {
        ensureOpen();
        validateKnowledgeBaseName(name);
        if (!managers.containsKey(name)) {
            throw new IllegalArgumentException("知识库 '" + name + "' 不存在");
        }
        String previous = currentKnowledgeBase;
        currentKnowledgeBase = name;
        logger.debug("切换知识库: {} -> {}", previous, name);
    }

    public List<String> listKnowledgeBases() {
        List<String> names = new ArrayList<>(managers.keySet());
        names.sort(Comparator.naturalOrder());
        return List.copyOf(names);
    }

    public boolean exists(String name) {
        return name != null && managers.containsKey(name);
    }

    public LLMProvider getLlmProvider() {
        return llmProvider;
    }

    private void validateKnowledgeBaseName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("知识库名称不能为空");
        }
        if (!isValidKnowledgeBaseName(name)) {
            throw new IllegalArgumentException(
                    "知识库名称格式无效，只能包含字母、数字、下划线和连字符，长度不超过50个字符");
        }
    }

    private boolean isValidKnowledgeBaseName(String name) {
        return name != null && !name.isBlank() && name.length() <= 50 && name.matches(NAME_PATTERN);
    }

    private Path getKnowledgeBaseStoragePath(String name) {
        return baseStoragePath.resolve(name);
    }

    private void deleteDirectory(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(file -> {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    logger.warn("删除文件失败: {}", file, e);
                }
            });
        } catch (IOException e) {
            throw new StorageException(path.toString(), "deleteDirectory", e);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (Map.Entry<String, SemanticTreeManager> entry : managers.entrySet()) {
            try {
                entry.getValue().close();
            } catch (RuntimeException e) {
                logger.error("关闭知识库 {} 失败", entry.getKey(), e);
            }
        }
        managers.clear();
        logger.debug("知识库管理器已关闭");
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("KnowledgeBaseManager is closed");
        }
        if (!initialized) {
            throw new IllegalStateException("KnowledgeBaseManager is not initialized");
        }
    }
}
