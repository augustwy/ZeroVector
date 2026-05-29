/*
 * Copyright 2025 nexonlab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
        return new AbstractCollection<DocumentChunk>() {
            @Override
            public Iterator<DocumentChunk> iterator() {
                return new Iterator<DocumentChunk>() {
                    private final Iterator<String> keyIterator = keys.iterator();
                    
                    @Override
                    public boolean hasNext() {
                        return keyIterator.hasNext();
                    }
                    
                    @Override
                    public DocumentChunk next() {
                        String key = keyIterator.next();
                        return get(key);
                    }
                };
            }
            
            @Override
            public int size() {
                return keys.size();
            }
        };
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
