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
 * 分片正文来源类型。
 */
public enum SourceType {
    /** 系统托管的 UTF-8 副本，与用户原文件的后续修改隔离。 */
    MANAGED_COPY,
    /** 直接引用用户原文件；用户修改/移动文件会影响读取。 */
    ORIGINAL
}
