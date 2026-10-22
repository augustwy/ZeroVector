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
 * 语义树节点类型。
 *
 * <p>{@link #DOCUMENT} 用于大文档的文档节点：其子节点是章节叶子；
 * 小文档仍然直接是 {@link #LEAF}。
 */
public enum NodeType {
    ROOT,
    CATEGORY,
    DOCUMENT,
    LEAF
}
