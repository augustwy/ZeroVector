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

package cn.nexon.zerovector.core.hook;

import java.time.Instant;
import java.util.Map;
import java.util.HashMap;

public class HookContext {
    private final HookType type;
    private final Instant timestamp;
    private final Map<String, Object> data;
    private Throwable error;
    private long durationMillis;

    private HookContext(HookType type) {
        this.type = type;
        this.timestamp = Instant.now();
        this.data = new HashMap<>();
    }

    public static HookContext of(HookType type) {
        return new HookContext(type);
    }

    public static Builder builder(HookType type) {
        return new Builder(type);
    }

    public HookType getType() {
        return type;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public HookContext withData(String key, Object value) {
        this.data.put(key, value);
        return this;
    }

    public Object getData(String key) {
        return data.get(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T getData(String key, Class<T> type) {
        Object value = data.get(key);
        if (value != null && type.isInstance(value)) {
            return (T) value;
        }
        return null;
    }

    public Throwable getError() {
        return error;
    }

    public HookContext withError(Throwable error) {
        this.error = error;
        return this;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public HookContext withDurationMillis(long durationMillis) {
        this.durationMillis = durationMillis;
        return this;
    }

    public boolean hasError() {
        return error != null;
    }

    public static class Builder {
        private final HookContext context;

        public Builder(HookType type) {
            this.context = new HookContext(type);
        }

        public Builder data(String key, Object value) {
            context.data.put(key, value);
            return this;
        }

        public Builder error(Throwable error) {
            context.error = error;
            return this;
        }

        public Builder durationMillis(long durationMillis) {
            context.durationMillis = durationMillis;
            return this;
        }

        public HookContext build() {
            return context;
        }
    }
}
