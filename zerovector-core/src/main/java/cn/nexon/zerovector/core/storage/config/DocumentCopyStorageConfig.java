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

package cn.nexon.zerovector.core.storage.config;

/**
 * 文件副本存储配置
 *
 * <p>用于配置 DocumentCopyStorage 的行为
 */
public class DocumentCopyStorageConfig extends StorageConfig {

    /**
     * 本地存储目录
     *
     * <p>用于本地文件存储实现，指定文档副本的存储目录
     */
    private String directory;

    public DocumentCopyStorageConfig() {
        super("local-file");
    }

    public DocumentCopyStorageConfig(String directory) {
        this();
        this.directory = directory;
    }

    public String getDirectory() {
        return directory;
    }

    public void setDirectory(String directory) {
        this.directory = directory;
    }
}
