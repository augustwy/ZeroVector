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
import cn.nexon.zerovector.core.hook.HookExecutor;
import cn.nexon.zerovector.core.hook.DefaultHookExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class KnowledgeBaseManager {
    private static final Logger logger = LoggerFactory.getLogger(KnowledgeBaseManager.class);
    private static final String DEFAULT_KNOWLEDGE_BASE = "default";
    private static final String NAME_PATTERN = "^[a-zA-Z0-9_-]+$";

    private final Map<String, SemanticTreeManager> managers = new ConcurrentHashMap<>();
    private final LLMProvider llmProvider;
    private final DocumentComprehender documentComprehender;
    private final ConcurrencyProperties concurrencyProperties;
    private final HookExecutor hookExecutor;
    private final Path baseStoragePath;
    private String currentKnowledgeBase;
    private boolean initialized = false;

    public KnowledgeBaseManager(LLMProvider llmProvider, DocumentComprehender documentComprehender,
                                   ConcurrencyProperties concurrencyProperties, String baseStoragePath) {
        this(llmProvider, documentComprehender, concurrencyProperties, baseStoragePath, new DefaultHookExecutor());
    }

    public KnowledgeBaseManager(LLMProvider llmProvider, DocumentComprehender documentComprehender,
                                   ConcurrencyProperties concurrencyProperties, String baseStoragePath, 
                                   HookExecutor hookExecutor) {
        this.llmProvider = llmProvider;
        this.documentComprehender = documentComprehender;
        this.concurrencyProperties = concurrencyProperties;
        this.baseStoragePath = Paths.get(baseStoragePath);
        this.hookExecutor = Objects.requireNonNullElse(hookExecutor, new DefaultHookExecutor());
        this.currentKnowledgeBase = DEFAULT_KNOWLEDGE_BASE;
    }

    public void initialize() throws IOException {
        if (initialized) {
            logger.warn("知识库管理器已经初始化，跳过重复初始化");
            return;
        }

        // 打印logo日志
        System.out.println("");
        System.out.println(" ________   _______ .______      ______      ____    ____  _______   ______ .___________.  ______   .______   ");
        System.out.println("|       /  |   ____||   _  \\    /  __  \\     \\   \\  /   / |   ____| /      ||           | /  __  \\  |   _  \\ ");
        System.out.println("`---/  /   |  |__   |  |_)  |  |  |  |  |     \\   \\/   /  |  |__   |  ,----'`---|  |----`|  |  |  | |  |_)  |");
        System.out.println("   /  /    |   __|  |      /   |  |  |  |      \\      /   |   __|  |  |         |  |     |  |  |  | |      /");
        System.out.println("  /  /----.|  |____ |  |\\  \\--.|  `--'  |       \\    /    |  |____ |  `----.    |  |     |  `--'  | |  |\\  \\--.");
        System.out.println(" /________||_______|| _| `.___| \\______/         \\__/     |_______| \\______|    |__|      \\______/  | _| `.___|");
        System.out.println("");
        System.out.println("     Zero Vector  v1.0.0");
        System.out.println("");

        logger.debug("开始初始化知识库管理器，基础存储路径: {}", baseStoragePath.toAbsolutePath());

        try {
            Files.createDirectories(baseStoragePath);
        } catch (IOException e) {
            throw new StorageException(baseStoragePath.toString(), "initialize", e);
        }

        try {
            loadExistingKnowledgeBases();
        } catch (IOException e) {
            logger.warn("加载已存在的知识库时遇到问题，将创建新的默认知识库: {}", e.getMessage());
        }

        if (!managers.containsKey(DEFAULT_KNOWLEDGE_BASE)) {
            logger.debug("默认知识库不存在，正在创建: {}", DEFAULT_KNOWLEDGE_BASE);
            try {
                createKnowledgeBase(DEFAULT_KNOWLEDGE_BASE);
            } catch (IOException e) {
                logger.error("创建默认知识库失败: {}", e.getMessage());
                throw e;
            }
        } else {
            logger.debug("默认知识库已存在: {}", DEFAULT_KNOWLEDGE_BASE);
        }

        currentKnowledgeBase = DEFAULT_KNOWLEDGE_BASE;
        initialized = true;

        logger.info("知识库管理器初始化完成，已加载 {} 个知识库", managers.size());
    }

    private void loadExistingKnowledgeBases() throws IOException {
        if (!Files.exists(baseStoragePath)) {
            logger.debug("基础存储路径不存在: {}", baseStoragePath);
            return;
        }

        if (!Files.isDirectory(baseStoragePath)) {
            logger.warn("基础存储路径不是目录: {}", baseStoragePath);
            return;
        }

        try {
            List<Path> kbDirectories = Files.list(baseStoragePath)
                .filter(Files::isDirectory)
                .toList();

            logger.debug("发现 {} 个潜在的知识库目录", kbDirectories.size());

            int loadedCount = 0;
            for (Path kbDir : kbDirectories) {
                String kbName = kbDir.getFileName().toString();
                if (isValidKnowledgeBaseName(kbName) && !managers.containsKey(kbName)) {
                    try {
                        loadKnowledgeBase(kbName, kbDir);
                        loadedCount++;
                    } catch (IOException e) {
                        logger.error("加载知识库 {} 失败，跳过: {}", kbName, e.getMessage());
                    }
                }
            }

            logger.debug("成功加载 {} 个已存在的知识库", loadedCount);
        } catch (IOException e) {
            throw new StorageException(baseStoragePath.toString(), "loadExistingKnowledgeBases", e);
        }
    }

    private void loadKnowledgeBase(String name, Path storagePath) throws IOException {
        logger.debug("加载知识库: {}, 存储路径: {}", name, storagePath);

        if (!isValidKnowledgeBaseDirectory(storagePath)) {
            logger.warn("知识库目录 {} 不是有效的知识库目录，跳过加载", storagePath);
            return;
        }

        try {
            SemanticTreeManager manager = new SemanticTreeManager(
                llmProvider,
                documentComprehender,
                storagePath,
                true,
                concurrencyProperties,
                hookExecutor
            );

            manager.initialize();
            managers.put(name, manager);

            logger.debug("知识库 {} 加载成功", name);
        } catch (IOException e) {
            throw new StorageException(storagePath.toString(), "loadKnowledgeBase", e);
        }
    }

    private boolean isValidKnowledgeBaseDirectory(Path path) {
        if (!Files.exists(path)) {
            return false;
        }

        if (!Files.isDirectory(path)) {
            return false;
        }

        try {
            return Files.list(path).findAny().isPresent();
        } catch (IOException e) {
            logger.warn("无法读取知识库目录 {}: {}", path, e.getMessage());
            return false;
        }
    }

    private boolean isValidKnowledgeBaseName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }

        if (name.length() > 50) {
            return false;
        }

        return name.matches(NAME_PATTERN);
    }

    private void validateKnowledgeBaseName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("知识库名称不能为空");
        }

        if (!isValidKnowledgeBaseName(name)) {
            throw new IllegalArgumentException(
                "知识库名称格式无效，只能包含字母、数字、下划线和连字符，长度不超过50个字符"
            );
        }
    }

    public SemanticTreeManager createKnowledgeBase(String name) throws IOException {
        validateKnowledgeBaseName(name);

        if (managers.containsKey(name)) {
            throw new IllegalArgumentException("知识库 '" + name + "' 已存在");
        }

        Path kbStoragePath = getKnowledgeBaseStoragePath(name);
        
        try {
            Files.createDirectories(kbStoragePath);
        } catch (IOException e) {
            throw new StorageException(kbStoragePath.toString(), "createKnowledgeBase", e);
        }

        logger.debug("创建知识库: {}, 存储路径: {}", name, kbStoragePath);

        try {
            SemanticTreeManager manager = new SemanticTreeManager(
                llmProvider,
                documentComprehender,
                kbStoragePath,
                true,
                concurrencyProperties,
                hookExecutor
            );

            manager.initialize();
            managers.put(name, manager);

            logger.info("知识库 {} 创建成功", name);

            return manager;
        } catch (IOException e) {
            throw new StorageException(kbStoragePath.toString(), "createKnowledgeBase", e);
        }
    }

    public void deleteKnowledgeBase(String name) throws IOException {
        validateKnowledgeBaseName(name);

        if (DEFAULT_KNOWLEDGE_BASE.equals(name)) {
            throw new IllegalArgumentException("不能删除默认知识库 '" + DEFAULT_KNOWLEDGE_BASE + "'");
        }

        if (!managers.containsKey(name)) {
            throw new IllegalArgumentException("知识库 '" + name + "' 不存在");
        }

        SemanticTreeManager manager = managers.remove(name);
        
        try {
            manager.close();
        } catch (Exception e) {
            throw new StorageException(getKnowledgeBaseStoragePath(name).toString(), "deleteKnowledgeBase", e);
        }

        Path kbStoragePath = getKnowledgeBaseStoragePath(name);
        deleteDirectory(kbStoragePath);

        logger.debug("删除知识库: {}, 存储路径: {}", name, kbStoragePath);

        if (currentKnowledgeBase.equals(name)) {
            switchKnowledgeBase(DEFAULT_KNOWLEDGE_BASE);
        }
    }

    public SemanticTreeManager getKnowledgeBase(String name) {
        if (name == null || name.trim().isEmpty()) {
            return getCurrentKnowledgeBase();
        }
        return managers.get(name);
    }

    public SemanticTreeManager getCurrentKnowledgeBase() {
        if (!managers.containsKey(currentKnowledgeBase)) {
            if (managers.containsKey(DEFAULT_KNOWLEDGE_BASE)) {
                currentKnowledgeBase = DEFAULT_KNOWLEDGE_BASE;
            } else {
                logger.warn("当前知识库 {} 不存在，且默认知识库也不存在", currentKnowledgeBase);
                return null;
            }
        }
        return managers.get(currentKnowledgeBase);
    }

    public void switchKnowledgeBase(String name) throws IOException {
        validateKnowledgeBaseName(name);

        if (!managers.containsKey(name)) {
            throw new IllegalArgumentException("知识库 '" + name + "' 不存在");
        }

        String oldName = currentKnowledgeBase;
        currentKnowledgeBase = name;

        logger.debug("切换知识库: {} -> {}", oldName, name);
    }

    public List<String> listKnowledgeBases() {
        return new ArrayList<>(managers.keySet());
    }

    public boolean exists(String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        return managers.containsKey(name);
    }

    public LLMProvider getLlmProvider() {
        return llmProvider;
    }

    private Path getKnowledgeBaseStoragePath(String name) {
        return baseStoragePath.resolve(name);
    }

    private void deleteDirectory(Path path) throws IOException {
        if (Files.exists(path)) {
            try (java.util.stream.Stream<Path> stream = Files.walk(path)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                      .forEach(p -> {
                          try {
                              Files.delete(p);
                          } catch (IOException e) {
                              logger.warn("删除文件失败: {}", p, e);
                          }
                      });
            }
        }
    }

    public void close() throws IOException {
        for (Map.Entry<String, SemanticTreeManager> entry : managers.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                logger.error("关闭知识库 {} 失败", entry.getKey(), e);
            }
        }
        managers.clear();
        logger.debug("知识库管理器已关闭");
    }
}
