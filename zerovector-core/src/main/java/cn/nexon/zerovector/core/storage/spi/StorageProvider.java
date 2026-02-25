package cn.nexon.zerovector.core.storage.spi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 存储提供者注解
 * 
 * <p>用于标识存储实现类，支持自动发现和注册
 * 
 * <p>使用示例：
 * <pre>
 * &#64;StorageProvider(type = "local-mmap", priority = 1, description = "本地文件存储（基于 mmap）")
 * public class LocalFileChunkStorage implements ChunkStorage {
 *     // ...
 * }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface StorageProvider {

    /**
     * 存储类型标识
     * 
     * <p>用于在配置中指定要使用的存储实现
     * 
     * @return 存储类型标识，如 "local-mmap", "elasticsearch", "minio" 等
     */
    String type();

    /**
     * 优先级
     * 
     * <p>数值越小优先级越高，当存在多个相同类型的实现时，优先级高的会被选中
     * 
     * @return 优先级值，默认为 100
     */
    int priority() default 100;

    /**
     * 描述信息
     * 
     * @return 存储实现的描述信息
     */
    String description() default "";
}
