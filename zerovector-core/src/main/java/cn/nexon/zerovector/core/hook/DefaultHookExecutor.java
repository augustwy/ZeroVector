package cn.nexon.zerovector.core.hook;

import java.util.List;

public class DefaultHookExecutor implements HookExecutor {
    private final HookRegistry registry;

    public DefaultHookExecutor(HookRegistry registry) {
        this.registry = registry;
    }

    public DefaultHookExecutor() {
        this.registry = new HookRegistry();
    }

    public HookRegistry getRegistry() {
        return registry;
    }

    @Override
    public void executeHooks(HookType type, HookContext context) {
        registry.trigger(context);
    }

    @Override
    public void executeHooks(HookType type, HookContext.Builder builder) {
        executeHooks(type, builder.build());
    }

    @Override
    public List<LifecycleHook> getHooks(HookType type) {
        return registry.getHooks(type);
    }

    @Override
    public boolean hasHooks(HookType type) {
        return registry.hasHooks(type);
    }
}