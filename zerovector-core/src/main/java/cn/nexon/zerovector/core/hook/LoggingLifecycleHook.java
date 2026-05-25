package cn.nexon.zerovector.core.hook;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoggingLifecycleHook implements LifecycleHook {

    private static final Map<String, String> DATA_LABELS = Map.of(
        "documentTitle", "文档",
        "query", "查询",
        "nodeName", "节点",
        "documentCount", "文档数",
        "nodeCount", "节点数",
        "stepCount", "步骤数",
        "filePath", "文件"
    );
    private static final Logger logger = LoggerFactory.getLogger(LoggingLifecycleHook.class);
    
    private final String name;
    private boolean enabled;

    public LoggingLifecycleHook() {
        this("LoggingLifecycleHook", true);
    }

    public LoggingLifecycleHook(String name, boolean enabled) {
        this.name = name;
        this.enabled = enabled;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public HookType[] getSupportedTypes() {
        return HookType.values();
    }

    @Override
    public void execute(HookContext context) {
        if (!enabled) {
            return;
        }
        
        String message = buildLogMessage(context);
        
        if (context.hasError() || context.getType().name().endsWith("_ERROR")) {
            logger.error(message);
        } else if (context.getType().name().endsWith("_START")) {
            logger.debug(message);
        } else {
            logger.info(message);
        }
    }
    
    private String buildLogMessage(HookContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(context.getType()).append("] ");

        if (context.getDurationMillis() > 0) {
            sb.append("耗时: ").append(context.getDurationMillis()).append("ms - ");
        }

        for (var entry : DATA_LABELS.entrySet()) {
            Object value = context.getData(entry.getKey(), Object.class);
            if (value != null) {
                sb.append(entry.getValue()).append(": ").append(value);
            }
        }

        if (context.hasError()) {
            sb.append(" - 错误: ").append(context.getError().getMessage());
        }

        return sb.toString();
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    public boolean isEnabled() {
        return enabled;
    }
    
    @Override
    public int getOrder() {
        return Integer.MAX_VALUE;
    }
}
