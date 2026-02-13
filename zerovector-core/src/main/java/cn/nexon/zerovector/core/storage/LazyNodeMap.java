package cn.nexon.zerovector.core.storage;

import cn.nexon.zerovector.core.model.DocumentChunk;
import cn.nexon.zerovector.core.model.TreeNode;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class LazyNodeMap implements Map<String, TreeNode> {
    private final ShardedTreeStorage storage;
    
    public LazyNodeMap(ShardedTreeStorage storage) {
        this.storage = storage;
    }
    
    @Override
    public int size() {
        return storage.getNodeShardIndex().size();
    }
    
    @Override
    public boolean isEmpty() {
        return storage.getNodeShardIndex().isEmpty();
    }
    
    @Override
    public boolean containsKey(Object key) {
        return storage.getNodeShardIndex().containsKey(key);
    }
    
    @Override
    public boolean containsValue(Object value) {
        return values().contains(value);
    }
    
    @Override
    public TreeNode get(Object key) {
        if (key == null) {
            return null;
        }
        try {
            return storage.getNode((String) key);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load node: " + key, e);
        }
    }
    
    @Override
    public TreeNode put(String key, TreeNode value) {
        throw new UnsupportedOperationException("LazyNodeMap is read-only");
    }
    
    @Override
    public TreeNode remove(Object key) {
        throw new UnsupportedOperationException("LazyNodeMap is read-only");
    }
    
    @Override
    public void putAll(Map<? extends String, ? extends TreeNode> m) {
        throw new UnsupportedOperationException("LazyNodeMap is read-only");
    }
    
    @Override
    public void clear() {
        throw new UnsupportedOperationException("LazyNodeMap is read-only");
    }
    
    @Override
    public Set<String> keySet() {
        return Collections.unmodifiableSet(storage.getNodeShardIndex().keySet());
    }
    
    @Override
    public Collection<TreeNode> values() {
        Set<String> keys = keySet();
        List<TreeNode> values = new ArrayList<>(keys.size());
        for (String key : keys) {
            values.add(get(key));
        }
        return values;
    }
    
    @Override
    public Set<Entry<String, TreeNode>> entrySet() {
        Set<String> keys = keySet();
        Set<Entry<String, TreeNode>> entries = new HashSet<>(keys.size());
        for (String key : keys) {
            entries.add(new AbstractMap.SimpleEntry<>(key, get(key)));
        }
        return entries;
    }
}
