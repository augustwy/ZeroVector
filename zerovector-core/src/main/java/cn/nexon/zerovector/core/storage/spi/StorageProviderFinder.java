package cn.nexon.zerovector.core.storage.spi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 存储提供者发现器
 * 
 * <p>通过 SPI 机制发现和加载存储实现
 * 
 * <p>使用示例：
 * <pre>
 * // 发现指定类型的存储实现
 * Optional&lt;ChunkStorage&gt; storage = StorageProviderFinder.findProvider(
 *     ChunkStorage.class, "local-mmap");
 * 
 * // 获取所有可用的存储类型
 * Set&lt;String&gt; types = StorageProviderFinder.getAvailableTypes(ChunkStorage.class);
 * </pre>
 */
public final class StorageProviderFinder {

    private static final Logger logger = LoggerFactory.getLogger(StorageProviderFinder.class);

    private static final Map<Class<?>, Map<String, ProviderInfo<?>>> PROVIDERS = new ConcurrentHashMap<>();

    private StorageProviderFinder() {
    }

    /**
     * 发现指定类型的存储实现
     * 
     * @param providerType 存储接口类型
     * @param type 存储类型标识
     * @param <T> 存储接口类型
     * @return 存储实现实例，如果未找到则返回 empty
     */
    public static <T> Optional<T> findProvider(Class<T> providerType, String type) {
        if (providerType == null || type == null || type.isEmpty()) {
            return Optional.empty();
        }

        Map<String, ProviderInfo<?>> providers = PROVIDERS.computeIfAbsent(providerType,
            StorageProviderFinder::loadProviders);

        ProviderInfo<?> info = providers.get(type);
        if (info != null) {
            try {
                @SuppressWarnings("unchecked")
                T provider = (T) info.provider;
                return Optional.of(provider);
            } catch (ClassCastException e) {
                logger.warn("无法转换存储提供者类型: {}", type, e);
            }
        }

        return Optional.empty();
    }

    /**
     * 获取所有可用的存储类型
     * 
     * @param providerType 存储接口类型
     * @param <T> 存储接口类型
     * @return 可用的存储类型集合
     */
    public static <T> Set<String> getAvailableTypes(Class<T> providerType) {
        if (providerType == null) {
            return Collections.emptySet();
        }

        Map<String, ProviderInfo<?>> providers = PROVIDERS.computeIfAbsent(providerType,
            StorageProviderFinder::loadProviders);

        return Collections.unmodifiableSet(providers.keySet());
    }

    /**
     * 获取存储提供者的描述信息
     * 
     * @param providerType 存储接口类型
     * @param type 存储类型标识
     * @param <T> 存储接口类型
     * @return 描述信息，如果未找到则返回 empty
     */
    public static <T> Optional<String> getDescription(Class<T> providerType, String type) {
        if (providerType == null || type == null || type.isEmpty()) {
            return Optional.empty();
        }

        Map<String, ProviderInfo<?>> providers = PROVIDERS.computeIfAbsent(providerType,
            StorageProviderFinder::loadProviders);

        ProviderInfo<?> info = providers.get(type);
        return info != null ? Optional.of(info.description) : Optional.empty();
    }

    /**
     * 刷新提供者缓存
     * 
     * <p>在动态加载新的存储实现后调用此方法刷新缓存
     */
    public static void refresh() {
        PROVIDERS.clear();
        logger.debug("存储提供者缓存已刷新");
    }

    /**
     * 刷新指定类型的提供者缓存
     * 
     * @param providerType 存储接口类型
     * @param <T> 存储接口类型
     */
    public static <T> void refresh(Class<T> providerType) {
        if (providerType != null) {
            PROVIDERS.remove(providerType);
            logger.debug("存储提供者缓存已刷新: {}", providerType.getName());
        }
    }

    private static <T> Map<String, ProviderInfo<?>> loadProviders(Class<T> providerType) {
        Map<String, ProviderInfo<?>> result = new HashMap<>();

        try {
            ServiceLoader<T> loader = ServiceLoader.load(providerType);

            for (T provider : loader) {
                StorageProvider annotation = provider.getClass()
                    .getAnnotation(StorageProvider.class);

                if (annotation != null) {
                    String type = annotation.type();
                    ProviderInfo<T> info = new ProviderInfo<>(
                        provider,
                        annotation.priority(),
                        annotation.description()
                    );

                    ProviderInfo<?> existing = result.get(type);
                    if (existing == null || info.priority < existing.priority) {
                        result.put(type, info);
                        logger.debug("加载存储提供者: {} -> {} (优先级: {})",
                            type, provider.getClass().getName(), info.priority);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("加载存储提供者失败: {}", providerType.getName(), e);
        }

        logger.debug("已加载 {} 个 {} 存储提供者", result.size(), providerType.getSimpleName());
        return result;
    }

    private record ProviderInfo<T>(T provider, int priority, String description) {}
}
