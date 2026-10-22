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

package cn.nexon.zerovector.core.util;

import cn.nexon.zerovector.core.exception.PromptLoadException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * JSON 工具类。
 *
 * <p>LLM 经常在 JSON 外包一层 markdown 代码块，{@link #parseJson} 会先剥离围栏；
 * 同时关闭“未知字段报错”，避免模型/版本新增字段导致旧数据无法加载。
 */
public final class JsonUtils {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JsonUtils() {
    }

    public static ObjectMapper getObjectMapper() {
        return OBJECT_MAPPER;
    }

    public static <T> T parseJson(String json, Class<T> clazz) {
        try {
            return OBJECT_MAPPER.readValue(stripCodeFence(json), clazz);
        } catch (Exception e) {
            throw new PromptLoadException("parseJson", PromptLoadException.ERROR_CODE_PARSE_FAILED,
                    "Failed to parse JSON: " + abbreviate(json), e);
        }
    }

    public static <T> T parseJson(String json, TypeReference<T> typeReference) {
        try {
            return OBJECT_MAPPER.readValue(stripCodeFence(json), typeReference);
        } catch (Exception e) {
            throw new PromptLoadException("parseJson", PromptLoadException.ERROR_CODE_PARSE_FAILED,
                    "Failed to parse JSON: " + abbreviate(json), e);
        }
    }

    public static <T> T parseJsonQuietly(String json, Class<T> clazz, T defaultValue) {
        try {
            return OBJECT_MAPPER.readValue(stripCodeFence(json), clazz);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public static List<String> parseStringList(String json) {
        try {
            return OBJECT_MAPPER.readValue(stripCodeFence(json), new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            throw new PromptLoadException("parseStringList", PromptLoadException.ERROR_CODE_PARSE_FAILED,
                    "Failed to parse string list response: " + abbreviate(json), e);
        }
    }

    public static Map<String, Object> parseToMap(String json) {
        try {
            return OBJECT_MAPPER.readValue(stripCodeFence(json), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new PromptLoadException("parseToMap", PromptLoadException.ERROR_CODE_PARSE_FAILED,
                    "Failed to parse JSON to map: " + abbreviate(json), e);
        }
    }

    public static String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize object to JSON", e);
        }
    }

    /** 去掉 LLM 输出常见的 ```json ... ``` 围栏；必要时截取第一个 JSON 对象/数组。 */
    public static String stripCodeFence(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstLineEnd = trimmed.indexOf('\n');
            if (firstLineEnd >= 0) {
                String body = trimmed.substring(firstLineEnd + 1);
                int closing = body.lastIndexOf("```");
                if (closing >= 0) {
                    body = body.substring(0, closing);
                }
                trimmed = body.trim();
            }
        }
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed;
        }
        int objectStart = trimmed.indexOf('{');
        int arrayStart = trimmed.indexOf('[');
        int start = objectStart < 0 ? arrayStart
                : arrayStart < 0 ? objectStart : Math.min(objectStart, arrayStart);
        if (start < 0) {
            return trimmed;
        }
        char open = trimmed.charAt(start);
        char close = open == '{' ? '}' : ']';
        int end = trimmed.lastIndexOf(close);
        return end > start ? trimmed.substring(start, end + 1) : trimmed;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "null";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
