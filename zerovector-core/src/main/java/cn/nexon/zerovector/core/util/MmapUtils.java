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
