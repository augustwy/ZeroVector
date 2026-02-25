package cn.nexon.zerovector.springboot.example.controller;

import cn.nexon.zerovector.core.ai.CacheConfig;
import cn.nexon.zerovector.core.ai.CacheStatistics;
import cn.nexon.zerovector.core.ai.SmartCacheStrategy;
import cn.nexon.zerovector.springboot.SemanticHub;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/cache")
public class CacheController {

    private final SemanticHub semanticHub;

    @Autowired
    public CacheController(SemanticHub semanticHub) {
        this.semanticHub = semanticHub;
    }

    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getStatistics() {
        Map<String, Object> response = new HashMap<>();

        try {
            Map<SmartCacheStrategy.RequestType, CacheStatistics> stats = semanticHub.getCacheStatistics();

            Map<String, Object> statisticsMap = new HashMap<>();
            stats.forEach((type, stat) -> {
                Map<String, Object> statData = new HashMap<>();
                statData.put("cacheName", stat.cacheName());
                statData.put("hitCount", stat.hitCount());
                statData.put("missCount", stat.missCount());
                statData.put("requestCount", stat.requestCount());
                statData.put("hitRate", stat.hitRate());
                statData.put("loadSuccessCount", stat.loadSuccessCount());
                statData.put("loadFailureCount", stat.loadFailureCount());
                statData.put("evictionCount", stat.evictionCount());
                statData.put("averageLoadPenalty", stat.averageLoadPenalty());
                statisticsMap.put(type.name().toLowerCase(), statData);
            });

            response.put("success", true);
            response.put("statistics", statisticsMap);
            response.put("totalCacheSize", semanticHub.getTotalCacheSize());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "获取缓存统计失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @GetMapping("/statistics/{type}")
    public ResponseEntity<Map<String, Object>> getStatisticsByType(@PathVariable("type") String type) {
        Map<String, Object> response = new HashMap<>();

        try {
            SmartCacheStrategy.RequestType requestType = SmartCacheStrategy.RequestType.valueOf(type.toUpperCase());
            CacheStatistics stats = semanticHub.getCacheStatistics(requestType);

            if (stats == null) {
                response.put("success", false);
                response.put("message", "未找到该类型的缓存统计");
                return ResponseEntity.notFound().build();
            }

            Map<String, Object> statData = new HashMap<>();
            statData.put("cacheName", stats.cacheName());
            statData.put("hitCount", stats.hitCount());
            statData.put("missCount", stats.missCount());
            statData.put("requestCount", stats.requestCount());
            statData.put("hitRate", stats.hitRate());
            statData.put("loadSuccessCount", stats.loadSuccessCount());
            statData.put("loadFailureCount", stats.loadFailureCount());
            statData.put("evictionCount", stats.evictionCount());
            statData.put("averageLoadPenalty", stats.averageLoadPenalty());

            response.put("success", true);
            response.put("type", type);
            response.put("statistics", statData);
            response.put("cacheSize", semanticHub.getCacheSize(requestType));

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", "无效的缓存类型: " + type);
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "获取缓存统计失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @DeleteMapping("/clear")
    public ResponseEntity<Map<String, Object>> clearAllCache() {
        Map<String, Object> response = new HashMap<>();

        try {
            semanticHub.clearCache();

            response.put("success", true);
            response.put("message", "所有缓存已清除");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "清除缓存失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @DeleteMapping("/clear/{type}")
    public ResponseEntity<Map<String, Object>> clearCacheByType(@PathVariable("type") String type) {
        Map<String, Object> response = new HashMap<>();

        try {
            SmartCacheStrategy.RequestType requestType = SmartCacheStrategy.RequestType.valueOf(type.toUpperCase());
            semanticHub.clearCache(requestType);

            response.put("success", true);
            response.put("message", "缓存类型 " + type + " 已清除");

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", "无效的缓存类型: " + type);
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "清除缓存失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PostMapping("/warmup")
    public ResponseEntity<Map<String, Object>> warmupCache(@RequestBody Map<String, List<String>> warmupData) {
        Map<String, Object> response = new HashMap<>();

        try {
            Map<SmartCacheStrategy.RequestType, List<String>> warmupPrompts = new HashMap<>();

            for (Map.Entry<String, List<String>> entry : warmupData.entrySet()) {
                SmartCacheStrategy.RequestType type = SmartCacheStrategy.RequestType.valueOf(entry.getKey().toUpperCase());
                warmupPrompts.put(type, entry.getValue());
            }

            semanticHub.warmupCache(warmupPrompts);

            response.put("success", true);
            response.put("message", "缓存预热完成");

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", "无效的缓存类型: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "缓存预热失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PutMapping("/config/{type}")
    public ResponseEntity<Map<String, Object>> updateCacheConfig(
            @PathVariable("type") String type,
            @RequestBody Map<String, Object> configData) {
        Map<String, Object> response = new HashMap<>();

        try {
            SmartCacheStrategy.RequestType requestType = SmartCacheStrategy.RequestType.valueOf(type.toUpperCase());

            long maxSize = ((Number) configData.getOrDefault("maxSize", 1000)).longValue();
            long expireAfterAccess = ((Number) configData.getOrDefault("expireAfterAccess", 1)).longValue();
            String timeUnitStr = (String) configData.getOrDefault("timeUnit", "HOURS");

            TimeUnit timeUnit = TimeUnit.valueOf(timeUnitStr.toUpperCase());

            CacheConfig config = new CacheConfig(maxSize, expireAfterAccess, timeUnit, true, "");
            semanticHub.updateCacheConfig(requestType, config);

            response.put("success", true);
            response.put("message", "缓存配置已更新");
            response.put("type", type);
            response.put("config", Map.of(
                "maxSize", maxSize,
                "expireAfterAccess", expireAfterAccess,
                "timeUnit", timeUnitStr
            ));

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", "无效的参数: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "更新缓存配置失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @GetMapping("/size")
    public ResponseEntity<Map<String, Object>> getCacheSize() {
        Map<String, Object> response = new HashMap<>();

        try {
            Map<String, Long> sizeMap = new HashMap<>();

            for (SmartCacheStrategy.RequestType type : SmartCacheStrategy.RequestType.values()) {
                sizeMap.put(type.name().toLowerCase(), semanticHub.getCacheSize(type));
            }

            response.put("success", true);
            response.put("sizes", sizeMap);
            response.put("totalSize", semanticHub.getTotalCacheSize());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "获取缓存大小失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PostMapping("/log")
    public ResponseEntity<Map<String, Object>> logStatistics() {
        Map<String, Object> response = new HashMap<>();

        try {
            semanticHub.logCacheStatistics();

            response.put("success", true);
            response.put("message", "缓存统计已记录到日志");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "记录日志失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
