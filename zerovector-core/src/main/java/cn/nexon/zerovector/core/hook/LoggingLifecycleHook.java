package cn.nexon.zerovector.core.hook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoggingLifecycleHook implements LifecycleHook {
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
        
        String documentTitle = context.getData("documentTitle", String.class);
        if (documentTitle != null) {
            sb.append("文档: ").append(documentTitle);
        }
        
        String query = context.getData("query", String.class);
        if (query != null) {
            sb.append("查询: ").append(query);
        }
        
        String nodeName = context.getData("nodeName", String.class);
        if (nodeName != null) {
            sb.append("节点: ").append(nodeName);
        }
        
        Integer documentCount = context.getData("documentCount", Integer.class);
        if (documentCount != null) {
            sb.append("文档数: ").append(documentCount);
        }
        
        Integer nodeCount = context.getData("nodeCount", Integer.class);
        if (nodeCount != null) {
            sb.append("节点数: ").append(nodeCount);
        }
        
        Integer stepCount = context.getData("stepCount", Integer.class);
        if (stepCount != null) {
            sb.append("步骤数: ").append(stepCount);
        }
        
        String filePath = context.getData("filePath", String.class);
        if (filePath != null) {
            sb.append("文件: ").append(filePath);
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
