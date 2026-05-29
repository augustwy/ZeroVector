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
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

public class JsonUtils {
    
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    
    private JsonUtils() {}
    
    public static ObjectMapper getObjectMapper() {
        return OBJECT_MAPPER;
    }
    
    public static <T> T parseJson(String json, Class<T> clazz) {
        try {
            return OBJECT_MAPPER.readValue(json, clazz);
        } catch (Exception e) {
            throw new PromptLoadException("parseJson", PromptLoadException.ERROR_CODE_PARSE_FAILED, 
                "Failed to parse JSON", e);
        }
    }
    
    public static <T> T parseJsonQuietly(String json, Class<T> clazz, T defaultValue) {
        try {
            return OBJECT_MAPPER.readValue(json, clazz);
        } catch (Exception e) {
            return defaultValue;
        }
    }
    
    public static List<String> parseStringList(String json) {
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw new PromptLoadException("parseStringList", PromptLoadException.ERROR_CODE_PARSE_FAILED, 
                "Failed to parse string list response", e);
        }
    }
    
    public static Map<String, Object> parseToMap(String json) {
        try {
            return OBJECT_MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            throw new PromptLoadException("parseToMap", PromptLoadException.ERROR_CODE_PARSE_FAILED, 
                "Failed to parse JSON to map", e);
        }
    }
    
    public static String toJson(Object obj) {
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            throw new PromptLoadException("toJson", PromptLoadException.ERROR_CODE_PARSE_FAILED, 
                "Failed to serialize object to JSON", e);
        }
    }
}
