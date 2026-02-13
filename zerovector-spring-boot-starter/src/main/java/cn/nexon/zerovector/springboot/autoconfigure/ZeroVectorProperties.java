package cn.nexon.zerovector.springboot.autoconfigure;

import cn.nexon.zerovector.core.config.ConcurrencyProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Max;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;

@ConfigurationProperties(prefix = "zerovector")
@Validated
public class ZeroVectorProperties {
    
    private static final int MIN_SHARD_SIZE = 10;
    private static final int MAX_SHARD_SIZE = 10000;
    
    private boolean enabled = true;
    
    @NotBlank
    private String storagePath = "./data/zerovector_storage";
    
    private boolean useShardedStorage = true;
    
    private int shardSize = 100;
    
    private SpringAi springAi = new SpringAi();
    
    private LangChain4j langChain4j = new LangChain4j();

    private Model model = new Model("gpt-4o-mini", "gpt-4o", 0.7, -1);

    @Valid
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
        if (shardSize < MIN_SHARD_SIZE || shardSize > MAX_SHARD_SIZE) {
            throw new IllegalArgumentException(
                String.format("shardSize must be between %d and %d, got: %d", 
                    MIN_SHARD_SIZE, MAX_SHARD_SIZE, shardSize));
        }
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