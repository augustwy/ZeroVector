package cn.nexon.zerovector.core.hook;

public interface LifecycleHook {
    String getName();
    
    HookType[] getSupportedTypes();
    
    void execute(HookContext context);
    
    default int getOrder() {
        return 0;
    }
}
