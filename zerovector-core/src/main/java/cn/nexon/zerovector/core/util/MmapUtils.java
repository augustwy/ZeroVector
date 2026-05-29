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

package cn.nexon.zerovector.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.MappedByteBuffer;

/**
 * Memory-mapped file utility methods.
 */
public final class MmapUtils {

    private static final Logger logger = LoggerFactory.getLogger(MmapUtils.class);

    private MmapUtils() {
        // utility class
    }

    /**
     * Attempts to release the underlying native resources of a MappedByteBuffer
     * via reflection. This is a best-effort operation — failures are logged at
     * debug level and the buffer will eventually be collected by GC.
     *
     * @param buffer the buffer to release; may be null (no-op)
     */
    public static void clean(MappedByteBuffer buffer) {
        if (buffer == null) {
            return;
        }
        try {
            Field cleanerField = buffer.getClass().getDeclaredField("cleaner");
            cleanerField.setAccessible(true);
            Object cleaner = cleanerField.get(buffer);
            if (cleaner != null) {
                Method cleanMethod = cleaner.getClass().getMethod("clean");
                cleanMethod.invoke(cleaner);
            }
        } catch (Exception e) {
            logger.debug("Failed to clean MappedByteBuffer: {}", e.getMessage());
        }
    }
}
