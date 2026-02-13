package cn.nexon.zerovector.core.storage;

import cn.nexon.zerovector.core.model.DocumentChunk;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class LazyChunkMap implements Map<String, DocumentChunk> {
    private final ShardedTreeStorage storage;
    
    public LazyChunkMap(ShardedTreeStorage storage) {
        this.storage = storage;
    }
    
    @Override
    public int size() {
        return storage.getChunkShardIndex().size();
    }
    
    @Override
    public boolean isEmpty() {
        return storage.getChunkShardIndex().isEmpty();
    }
    
    @Override
    public boolean containsKey(Object key) {
        return storage.getChunkShardIndex().containsKey(key);
    }
    
    @Override
    public boolean containsValue(Object value) {
        return values().contains(value);
    }
    
    @Override
    public DocumentChunk get(Object key) {
        if (key == null) {
            return null;
        }
        try {
            return storage.getChunk((String) key);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load chunk: " + key, e);
        }
    }
    
    @Override
    public DocumentChunk put(String key, DocumentChunk value) {
        throw new UnsupportedOperationException("LazyChunkMap is read-only");
    }
    
    @Override
    public DocumentChunk remove(Object key) {
        throw new UnsupportedOperationException("LazyChunkMap is read-only");
    }
    
    @Override
    public void putAll(Map<? extends String, ? extends DocumentChunk> m) {
        throw new UnsupportedOperationException("LazyChunkMap is read-only");
    }
    
    @Override
    public void clear() {
        throw new UnsupportedOperationException("LazyChunkMap is read-only");
    }
    
    @Override
    public Set<String> keySet() {
        return Collections.unmodifiableSet(storage.getChunkShardIndex().keySet());
    }
    
    @Override
    public Collection<DocumentChunk> values() {
        Set<String> keys = keySet();
        List<DocumentChunk> values = new ArrayList<>(keys.size());
        for (String key : keys) {
            values.add(get(key));
        }
        return values;
    }
    
    @Override
    public Set<Entry<String, DocumentChunk>> entrySet() {
        Set<String> keys = keySet();
        Set<Entry<String, DocumentChunk>> entries = new HashSet<>(keys.size());
        for (String key : keys) {
            entries.add(new AbstractMap.SimpleEntry<>(key, get(key)));
        }
        return entries;
    }
}
