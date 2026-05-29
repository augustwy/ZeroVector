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

package cn.nexon.zerovector.core.storage.model;

import java.time.Instant;

/**
 * 文件副本元信息
 * 
 * <p>记录文件副本的基本信息，包括文件标识、名称、大小、类型、创建时间和校验和
 */
public record DocumentCopyInfo(
    /**
     * 文件标识
     * 
     * <p>用于唯一标识文件副本，在存储系统中唯一
     */
    String fileId,

    /**
     * 原始文件名
     * 
     * <p>上传时的原始文件名
     */
    String fileName,

    /**
     * 文件大小（字节）
     */
    long size,

    /**
     * 内容类型（MIME）
     * 
     * <p>如 "text/plain", "application/pdf" 等
     */
    String contentType,

    /**
     * 创建时间
     */
    Instant createdAt,

    /**
     * 文件校验和（MD5）
     * 
     * <p>用于文件完整性校验
     */
    String checksum
) {

    /**
     * 创建一个简化的文件副本信息
     * 
     * @param fileId 文件标识
     * @param fileName 文件名
     * @param size 文件大小
     * @return 文件副本信息
     */
    public static DocumentCopyInfo simple(String fileId, String fileName, long size) {
        return new DocumentCopyInfo(fileId, fileName, size, null, Instant.now(), null);
    }

    /**
     * 创建一个包含校验和的文件副本信息
     * 
     * @param fileId 文件标识
     * @param fileName 文件名
     * @param size 文件大小
     * @param checksum 校验和
     * @return 文件副本信息
     */
    public static DocumentCopyInfo withChecksum(String fileId, String fileName, long size, String checksum) {
        return new DocumentCopyInfo(fileId, fileName, size, null, Instant.now(), checksum);
    }

    /**
     * 格式化文件大小
     * 
     * @return 人类可读的文件大小字符串
     */
    public String getFormattedSize() {
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format("%.2f KB", size / 1024.0);
        } else if (size < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", size / (1024.0 * 1024));
        } else {
            return String.format("%.2f GB", size / (1024.0 * 1024 * 1024));
        }
    }
}
