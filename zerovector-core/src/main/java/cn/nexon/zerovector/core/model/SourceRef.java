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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * 指向文档原文的引用。
 *
 * @param type 来源类型
 * @param id   托管副本的文件名，或原文件的绝对路径
 */
public record SourceRef(SourceType type, String id) {
    @JsonCreator
    public SourceRef(@JsonProperty("type") SourceType type, @JsonProperty("id") String id) {
        this.type = Objects.requireNonNull(type, "type");
        this.id = Objects.requireNonNull(id, "id");
    }
}
