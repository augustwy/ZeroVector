package cn.nexon.zerovector.core.util;

import cn.nexon.zerovector.core.exception.PromptLoadException;
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
            StringListResponse result = OBJECT_MAPPER.readValue(json, StringListResponse.class);
            return result.items();
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
    
    private record StringListResponse(List<String> items) {}
}
