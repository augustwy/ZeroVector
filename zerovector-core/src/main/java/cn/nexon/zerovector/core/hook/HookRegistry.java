package cn.nexon.zerovector.core.hook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Comparator;

public class HookRegistry {
    private static final Logger logger = LoggerFactory.getLogger(HookRegistry.class);
    
    private final Map<HookType, List<LifecycleHook>> hooksByType;
    
    public HookRegistry() {
        this.hooksByType = new HashMap<>();
    }
    
    public synchronized void register(LifecycleHook hook) {
        if (hook == null) {
            logger.warn("尝试注册空钩子，已忽略");
            return;
        }
        
        HookType[] supportedTypes = hook.getSupportedTypes();
        if (supportedTypes == null || supportedTypes.length == 0) {
            logger.warn("钩子 {} 没有声明支持的类型，已忽略", hook.getName());
            return;
        }
        
        for (HookType type : supportedTypes) {
            hooksByType.computeIfAbsent(type, k -> new ArrayList<>()).add(hook);
            hooksByType.get(type).sort(Comparator.comparingInt(LifecycleHook::getOrder));
        }
        
        logger.debug("已注册钩子: {}，支持类型: {}", hook.getName(), supportedTypes);
    }
    
    public synchronized void unregister(LifecycleHook hook) {
        if (hook == null) {
            return;
        }
        
        for (List<LifecycleHook> hooks : hooksByType.values()) {
            hooks.remove(hook);
        }
        
        logger.debug("已注销钩子: {}", hook.getName());
    }
    
    public synchronized void unregisterAll() {
        hooksByType.clear();
        logger.debug("已注销所有钩子");
    }
    
    public void trigger(HookContext context) {
        if (context == null) {
            return;
        }
        
        List<LifecycleHook> hooks = hooksByType.get(context.getType());
        if (hooks == null || hooks.isEmpty()) {
            return;
        }
        
        for (LifecycleHook hook : hooks) {
            try {
                hook.execute(context);
            } catch (Exception e) {
                logger.error("钩子 {} 执行失败: {}", hook.getName(), e.getMessage(), e);
            }
        }
    }
    
    public List<LifecycleHook> getHooks(HookType type) {
        return hooksByType.getOrDefault(type, List.of());
    }
    
    public boolean hasHooks(HookType type) {
        List<LifecycleHook> hooks = hooksByType.get(type);
        return hooks != null && !hooks.isEmpty();
    }
}
