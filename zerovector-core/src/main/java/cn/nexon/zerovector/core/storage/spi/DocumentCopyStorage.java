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
import cn.nexon.zerovector.core.storage.config.DocumentCopyStorageConfig;
import cn.nexon.zerovector.core.storage.model.DocumentCopyInfo;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 文件副本存储接口
 * 
 * <p>用于存储原始文档的副本，支持扩展到：
 * <ul>
 *   <li>MinIO - 开源对象存储</li>
 *   <li>阿里云 OSS - 云对象存储</li>
 *   <li>AWS S3 - 云对象存储</li>
 *   <li>本地文件系统 - 默认实现</li>
 * </ul>
 */
public interface DocumentCopyStorage extends AutoCloseable {

    /**
     * 初始化存储
     * 
     * @param config 存储配置
     * @throws StorageException 初始化失败
     */
    void initialize(DocumentCopyStorageConfig config) throws StorageException;

    /**
     * 保存文件副本
     * 
     * @param originalPath 原始文件路径
     * @return 保存后的文件标识
     * @throws StorageException 存储异常
     */
    String saveCopy(Path originalPath) throws StorageException;

    /**
     * 保存文件副本（带自定义标识）
     * 
     * @param originalPath 原始文件路径
     * @param customId 自定义标识
     * @return 保存后的文件标识
     * @throws StorageException 存储异常
     */
    String saveCopy(Path originalPath, String customId) throws StorageException;

    /**
     * 保存文件副本（从输入流）
     * 
     * @param inputStream 输入流
     * @param fileName 文件名
     * @return 保存后的文件标识
     * @throws StorageException 存储异常
     */
    String saveCopy(InputStream inputStream, String fileName) throws StorageException;

    /**
     * 获取文件副本内容
     * 
     * @param fileId 文件标识
     * @return 文件内容字节数组
     * @throws StorageException 存储异常
     */
    byte[] getContent(String fileId) throws StorageException;

    /**
     * 获取文件副本输入流
     * 
     * @param fileId 文件标识
     * @return 输入流
     * @throws StorageException 存储异常
     */
    InputStream getInputStream(String fileId) throws StorageException;

    /**
     * 获取文件副本路径（仅本地文件系统实现支持）
     * 
     * @param fileId 文件标识
     * @return 文件路径，不支持则返回 empty
     */
    Optional<Path> getFilePath(String fileId);

    /**
     * 删除文件副本
     * 
     * @param fileId 文件标识
     * @throws StorageException 存储异常
     */
    void deleteCopy(String fileId) throws StorageException;

    /**
     * 检查文件是否存在
     * 
     * @param fileId 文件标识
     * @return 是否存在
     */
    boolean exists(String fileId);

    /**
     * 获取所有文件标识
     * 
     * @return 文件标识列表
     */
    List<String> getAllFileIds();

    /**
     * 获取文件元信息
     * 
     * @param fileId 文件标识
     * @return 文件元信息
     */
    Optional<DocumentCopyInfo> getFileInfo(String fileId);

    /**
     * 清空所有文件副本
     * 
     * @throws StorageException 存储异常
     */
    void clear() throws StorageException;

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
}
