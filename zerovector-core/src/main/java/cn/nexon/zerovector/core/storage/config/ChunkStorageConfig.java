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
 * 文档正文存储配置。
 */
public class ChunkStorageConfig extends StorageConfig {

    /** 本地存储基础路径（不含后缀）。 */
    private String basePath;

    /** 是否使用 mmap 存储正文。 */
    private boolean useMmap = true;

    /** mmap 分片数，用于降低单文件锁争用与 2GB 上限风险。 */
    private int mmapShardCount = 4;

    public ChunkStorageConfig() {
        super("local-mmap");
    }

    public ChunkStorageConfig(String basePath) {
        this();
        this.basePath = basePath;
    }

    public String getBasePath() {
        return basePath;
    }

    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }

    public boolean isUseMmap() {
        return useMmap;
    }

    public void setUseMmap(boolean useMmap) {
        this.useMmap = useMmap;
    }

    public int getMmapShardCount() {
        return mmapShardCount;
    }

    public void setMmapShardCount(int mmapShardCount) {
        this.mmapShardCount = mmapShardCount;
    }
}
