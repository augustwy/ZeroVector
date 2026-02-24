package cn.nexon.zerovector.core.ai;

import java.util.concurrent.TimeUnit;

public record CacheConfig(
    long maxSize,
    long expireAfterAccess,
    TimeUnit timeUnit,
    boolean enabled,
    String prefix
) {
    public static final CacheConfig DEFAULT_SUMMARY = new CacheConfig(
        2000L, 2L, TimeUnit.HOURS, true, "summary"
    );
    
    public static final CacheConfig DEFAULT_COMPREHEND = new CacheConfig(
        5000L, 4L, TimeUnit.HOURS, true, "comprehend"
    );
    
    public static final CacheConfig DEFAULT_CLUSTER = new CacheConfig(
        1000L, 6L, TimeUnit.HOURS, true, "cluster"
    );
    
    public static final CacheConfig DEFAULT_KEYWORDS = new CacheConfig(
        3000L, 8L, TimeUnit.HOURS, true, "keywords"
    );
    
    public static final CacheConfig DEFAULT_ENTITIES = new CacheConfig(
        3000L, 8L, TimeUnit.HOURS, true, "entities"
    );
    
    public static final CacheConfig DEFAULT_QUESTIONS = new CacheConfig(
        2000L, 12L, TimeUnit.HOURS, true, "questions"
    );
    
    public static final CacheConfig DEFAULT_NAVIGATION = new CacheConfig(
        5000L, 1L, TimeUnit.HOURS, true, "navigation"
    );
    
    public static CacheConfig of(long maxSize, long expireAfterAccess, TimeUnit timeUnit) {
        return new CacheConfig(maxSize, expireAfterAccess, timeUnit, true, "");
    }
    
    public static CacheConfig disabled() {
        return new CacheConfig(0L, 0L, TimeUnit.SECONDS, false, "");
    }
}
