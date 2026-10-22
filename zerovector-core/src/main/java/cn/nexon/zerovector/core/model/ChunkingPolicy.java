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
 * 文档单位策略。
 *
 * <p>默认 {@link #AUTO}：小文档整篇作为一个检索/回答单位；超过
 * {@code chapterSplitThresholdChars} 后才按章节切分。
 */
public enum ChunkingPolicy {
    AUTO,
    WHOLE,
    CHAPTER
}
