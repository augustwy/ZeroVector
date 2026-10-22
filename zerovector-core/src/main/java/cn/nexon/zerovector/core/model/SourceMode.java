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

package cn.nexon.zerovector.core.model;

/**
 * 文档正文的保存模式。
 */
public enum SourceMode {
    /** 复制一份 UTF-8 副本，查询/回答与用户原文件隔离（文件上传默认）。 */
    COPY,
    /** 只保存原文件路径，不复制；用户修改原文件会影响后续读取。 */
    REFERENCE,
    /** 正文直接写入 ChunkStorage（mmap），适用于接口传入的小文档。 */
    INLINE
}
