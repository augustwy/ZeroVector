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
        return new AbstractCollection<TreeNode>() {
            @Override
            public Iterator<TreeNode> iterator() {
                return new Iterator<TreeNode>() {
                    private final Iterator<String> keyIterator = keys.iterator();
                    
                    @Override
                    public boolean hasNext() {
                        return keyIterator.hasNext();
                    }
                    
                    @Override
                    public TreeNode next() {
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
    public Set<Entry<String, TreeNode>> entrySet() {
        Set<String> keys = keySet();
        return new AbstractSet<Entry<String, TreeNode>>() {
            @Override
            public Iterator<Entry<String, TreeNode>> iterator() {
                return new Iterator<Entry<String, TreeNode>>() {
                    private final Iterator<String> keyIterator = keys.iterator();
                    
                    @Override
                    public boolean hasNext() {
                        return keyIterator.hasNext();
                    }
                    
                    @Override
                    public Entry<String, TreeNode> next() {
                        String key = keyIterator.next();
                        return new AbstractMap.SimpleEntry<>(key, get(key));
                    }
                };
            }
            
            @Override
            public int size() {
                return keys.size();
            }
        };
    }
}
