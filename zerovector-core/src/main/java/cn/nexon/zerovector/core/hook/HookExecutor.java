package cn.nexon.zerovector.core.hook;

import java.util.List;

public interface HookExecutor {
    void executeHooks(HookType type, HookContext context);
    
    void executeHooks(HookType type, HookContext.Builder builder);
    
    List<LifecycleHook> getHooks(HookType type);
    
    boolean hasHooks(HookType type);
}
