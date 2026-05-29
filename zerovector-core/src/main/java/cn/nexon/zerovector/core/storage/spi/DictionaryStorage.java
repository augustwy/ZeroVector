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

package cn.nexon.zerovector.core.storage.spi;

import cn.nexon.zerovector.core.exception.StorageException;
import cn.nexon.zerovector.core.model.KeywordDefinition;
import cn.nexon.zerovector.core.storage.config.DictionaryStorageConfig;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 字典存储接口
 * 
 * <p>用于存储关键词定义和倒排索引，支持扩展到：
 * <ul>
 *   <li>Elasticsearch - 利用倒排索引特性</li>
 *   <li>Redis - 高性能缓存</li>
 *   <li>数据库 - 持久化存储</li>
 * </ul>
 */
public interface DictionaryStorage extends AutoCloseable {

    /**
     * 初始化存储
     * 
     * @param config 存储配置
     * @throws StorageException 初始化失败
     */
    void initialize(DictionaryStorageConfig config) throws StorageException;

    /**
     * 保存关键词定义
     * 
     * @param definition 关键词定义
     * @throws StorageException 存储异常
     */
    void saveKeywordDefinition(KeywordDefinition definition) throws StorageException;

    /**
     * 批量保存关键词定义
     * 
     * @param definitions 关键词定义列表
     * @throws StorageException 存储异常
     */
    void saveKeywordDefinitions(List<KeywordDefinition> definitions) throws StorageException;

    /**
     * 获取关键词定义
     * 
     * @param keyword 关键词
     * @return 关键词定义，不存在返回empty
     */
    Optional<KeywordDefinition> getKeywordDefinition(String keyword);

    /**
     * 删除关键词定义
     * 
     * @param keyword 关键词
     * @throws StorageException 存储异常
     */
    void deleteKeywordDefinition(String keyword) throws StorageException;

    /**
     * 添加倒排索引条目
     * 
     * @param keyword 关键词
     * @param nodeId 节点ID
     * @param weight 权重
     * @throws StorageException 存储异常
     */
    void addInvertedIndexEntry(String keyword, String nodeId, double weight) throws StorageException;

    /**
     * 批量添加倒排索引条目
     * 
     * @param keyword 关键词
     * @param nodeIds 节点ID列表
     * @throws StorageException 存储异常
     */
    void addInvertedIndexEntries(String keyword, List<String> nodeIds) throws StorageException;

    /**
     * 获取关键词对应的节点列表
     * 
     * @param keyword 关键词
     * @return 节点ID列表
     */
    List<String> getNodesForKeyword(String keyword);

    /**
     * 获取关键词权重
     * 
     * @param keyword 关键词
     * @return 权重值，不存在返回 0.0
     */
    double getKeywordWeight(String keyword);

    /**
     * 匹配候选节点
     * 
     * @param query 查询字符串
     * @return 节点ID到得分的映射
     */
    Map<String, Double> matchCandidates(String query);

    /**
     * 从关键词列表匹配候选节点
     * 
     * @param keywords 关键词列表
     * @return 节点ID到得分的映射
     */
    Map<String, Double> matchCandidatesFromKeywords(List<String> keywords);

    /**
     * 获取所有关键词
     * 
     * @return 关键词集合
     */
    Set<String> getAllKeywords();

    /**
     * 获取所有关键词定义
     * 
     * @return 关键词定义集合
     */
    Collection<KeywordDefinition> getAllKeywordDefinitions();

    /**
     * 检查关键词是否存在
     * 
     * @param keyword 关键词
     * @return 是否存在
     */
    boolean containsKeyword(String keyword);

    /**
     * 清空所有数据
     * 
     * @throws StorageException 存储异常
     */
    void clear() throws StorageException;

    /**
     * 获取字典大小
     * 
     * @return 关键词数量
     */
    int size();

    /**
     * 获取存储类型标识
     * 
     * @return 存储类型
     */
    String getStorageType();

    /**
     * 健康检查
     * 
     * @return 是否健康
     */
    boolean isHealthy();

    /**
     * 持久化数据
     * 
     * @throws StorageException 存储异常
     */
    void persist() throws StorageException;
}
