package cn.nexon.zerovector.springboot.autoconfigure;

import cn.nexon.zerovector.core.config.ConcurrencyProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ZeroVector Spring Boot 自动配置属性
 */
@ConfigurationProperties(prefix = "zerovector")
public class ZeroVectorProperties {
    
    /**
     * 是否启用ZeroVector
     */
    private boolean enabled = true;
    
    /**
     * 存储路径
     */
    private String storagePath = "./zerovector_storage";
    
    /**
     * 是否使用分片存储
     */
    private boolean useShardedStorage = true;
    
    /**
     * 分片大小（每个分片包含的节点/块数量）
     */
    private int shardSize = 100;
    
    /**
     * Spring AI配置
     */
    private SpringAi springAi = new SpringAi();
    
    /**
     * LangChain4j配置
     */
    private LangChain4j langChain4j = new LangChain4j();

    /**
     * 模型配置
     */
    private Model model = new Model("gpt-4o-mini", "gpt-4o", 0.7, -1);

    /**
     * 模型并发配置
     */
    private ConcurrencyProperties concurrency = new ConcurrencyProperties();

    public boolean isEnabled() {
        return enabled;
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    public String getStoragePath() {
        return storagePath;
    }
    
    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }
    
    public boolean isUseShardedStorage() {
        return useShardedStorage;
    }
    
    public void setUseShardedStorage(boolean useShardedStorage) {
        this.useShardedStorage = useShardedStorage;
    }
    
    public int getShardSize() {
        return shardSize;
    }
    
    public void setShardSize(int shardSize) {
        this.shardSize = shardSize;
    }
    
    public SpringAi getSpringAi() {
        return springAi;
    }
    
    public void setSpringAi(SpringAi springAi) {
        this.springAi = springAi;
    }
    
    public LangChain4j getLangChain4j() {
        return langChain4j;
    }
    
    public void setLangChain4j(LangChain4j langChain4j) {
        this.langChain4j = langChain4j;
    }

    public Model getModel() {
        return model;
    }

    public void setModel(Model model) {
        this.model = model;
    }

    public ConcurrencyProperties getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(ConcurrencyProperties concurrency) {
        this.concurrency = concurrency;
    }

    /**
     * 模型配置
     */
    public record Model(String clustering, String navigation, double temperature, int maxTokens) {

    }

    /**
     * Spring AI配置
     */
    public static class SpringAi {

    }
    
    /**
     * LangChain4j配置
     */
    public static class LangChain4j {

    }
}