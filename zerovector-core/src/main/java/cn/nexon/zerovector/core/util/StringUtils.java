package cn.nexon.zerovector.core.util;

import java.util.List;

public class StringUtils {
    
    private StringUtils() {}
    
    public static boolean isEmpty(String str) {
        return str == null || str.isEmpty();
    }
    
    public static boolean isNotEmpty(String str) {
        return !isEmpty(str);
    }
    
    public static String truncate(String str, int maxLength) {
        if (str == null) {
            return null;
        }
        if (maxLength <= 0) {
            return "";
        }
        return str.substring(0, Math.min(maxLength, str.length()));
    }
    
    public static String joinWithSpace(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        return String.join(" ", items);
    }
}
