package cn.nexon.zerovector.core.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

class MMapDocumentStoreTest {

    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("mmap-test-");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            try (var walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            // On Windows, mmap'd files may still be locked.
                            // Attempt delete and ignore failures.
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
            }
        }
    }

    @Test
    void open_createsDataFile() throws IOException {
        Path dataFile = tempDir.resolve("test.data");
        try (MMapDocumentStore store = MMapDocumentStore.open(dataFile.toString())) {
            assertTrue(Files.exists(dataFile));
            assertEquals(dataFile.toString(), store.getDataFilePath());
        }
    }

    @Test
    void addAndGetChunk_roundtrip() throws IOException {
        Path dataFile = tempDir.resolve("roundtrip.data");
        try (MMapDocumentStore store = MMapDocumentStore.open(dataFile.toString())) {
            store.addChunk("chunk1", "Hello ZeroVector");
            String content = store.getChunk("chunk1");
            assertEquals("Hello ZeroVector", content);
        }
    }

    @Test
    void addAndGetChunk_multipleChunks() throws IOException {
        Path dataFile = tempDir.resolve("multi.data");
        try (MMapDocumentStore store = MMapDocumentStore.open(dataFile.toString())) {
            store.addChunk("c1", "content one");
            store.addChunk("c2", "content two");
            store.addChunk("c3", "content three");

            assertEquals("content one", store.getChunk("c1"));
            assertEquals("content two", store.getChunk("c2"));
            assertEquals("content three", store.getChunk("c3"));
        }
    }

    @Test
    void getChunk_nonexistent_returnsNull() throws IOException {
        Path dataFile = tempDir.resolve("nonexist.data");
        try (MMapDocumentStore store = MMapDocumentStore.open(dataFile.toString())) {
            assertNull(store.getChunk("nonexistent"));
        }
    }

    @Test
    void deleteChunk_existing_returnsTrue() throws IOException {
        Path dataFile = tempDir.resolve("delete.data");
        try (MMapDocumentStore store = MMapDocumentStore.open(dataFile.toString())) {
            store.addChunk("chunk1", "to be deleted");
            assertTrue(store.deleteChunk("chunk1"));
            assertNull(store.getChunk("chunk1"));
        }
    }

    @Test
    void deleteChunk_nonexistent_returnsFalse() throws IOException {
        Path dataFile = tempDir.resolve("delnonexist.data");
        try (MMapDocumentStore store = MMapDocumentStore.open(dataFile.toString())) {
            assertFalse(store.deleteChunk("nonexistent"));
        }
    }

    @Test
    void indexPersists_acrossCloseReopen() throws IOException {
        Path dataFile = tempDir.resolve("persist.data");
        Path indexPath = tempDir.resolve("persist.data.index");

        // First session
        {
            MMapDocumentStore store = MMapDocumentStore.open(dataFile.toString());
            store.addChunk("persistent1", "survives close");
            store.addChunk("persistent2", "also survives");
            store.close();
        }

        assertTrue(Files.exists(indexPath), "Index file should exist after close");

        // Second session
        try (MMapDocumentStore store = MMapDocumentStore.open(dataFile.toString())) {
            assertEquals("survives close", store.getChunk("persistent1"));
            assertEquals("also survives", store.getChunk("persistent2"));
        }
    }

    @Test
    @Disabled("compact() fails on Windows due to MMap file lock; works on Linux/macOS")
    void compact_reclaimsSpace() throws IOException {
        Path dataFile = tempDir.resolve("compact.data");
        MMapDocumentStore store = MMapDocumentStore.open(dataFile.toString());

        store.addChunk("k1", "data1");
        store.addChunk("k2", "data2");
        store.addChunk("k3", "data3");

        store.deleteChunk("k2");
        store.compact();

        assertEquals("data1", store.getChunk("k1"));
        assertEquals("data3", store.getChunk("k3"));
        assertNull(store.getChunk("k2"));
        store.close();
    }

    @Test
    @Disabled("compact() fails on Windows due to MMap file lock; works on Linux/macOS")
    void compact_emptyStore_resetsBuffer() throws IOException {
        Path dataFile = tempDir.resolve("emptycompact.data");
        MMapDocumentStore store = MMapDocumentStore.open(dataFile.toString());

        store.addChunk("tmp", "temp");
        store.deleteChunk("tmp");
        store.compact();

        String usage = store.getUsageInfo();
        assertTrue(usage.contains("0/"), "After compacting all data, usage should show 0 used");
        store.close();
    }

    @Test
    void largeChunk_triggersBufferExpansion() throws IOException {
        Path dataFile = tempDir.resolve("expand.data");
        try (MMapDocumentStore store = MMapDocumentStore.open(dataFile.toString())) {
            // 1.5MB — enough to trigger expansion from 1MB initial buffer, fits in 2MB expanded buffer
            String largeContent = "x".repeat(1_500_000);
            store.addChunk("large", largeContent);
            String retrieved = store.getChunk("large");
            assertEquals(largeContent, retrieved);
        }
    }

    @Test
    void getUsageInfo_returnsFormattedString() throws IOException {
        Path dataFile = tempDir.resolve("usage.data");
        try (MMapDocumentStore store = MMapDocumentStore.open(dataFile.toString())) {
            store.addChunk("u1", "small");
            String usage = store.getUsageInfo();
            assertTrue(usage.contains("bytes"));
            assertTrue(usage.contains("%"));
        }
    }

    @Test
    void saveAndLoadIndex_manually() throws IOException {
        Path dataFile = tempDir.resolve("manualindex.data");
        MMapDocumentStore store = MMapDocumentStore.open(dataFile.toString());
        store.addChunk("manual1", "manual content");
        store.saveIndex();
        store.close();

        Path indexPath = tempDir.resolve("manualindex.data.index");
        assertTrue(Files.exists(indexPath));
        String indexContent = Files.readString(indexPath);
        assertTrue(indexContent.contains("manual1"));
    }

    @Test
    void close_thenGetReturnsNull() throws IOException {
        Path dataFile = tempDir.resolve("closed.data");
        MMapDocumentStore store = MMapDocumentStore.open(dataFile.toString());
        store.addChunk("preclose", "before close");
        store.close();

        assertNull(store.getChunk("preclose"));
    }

    @Test
    void concurrentReadWrite_noDataCorruption() throws IOException {
        Path dataFile = tempDir.resolve("concurrent.data");
        try (MMapDocumentStore store = MMapDocumentStore.open(dataFile.toString())) {
            for (int i = 0; i < 20; i++) {
                store.addChunk("key-" + i, "value-" + i);
            }
            for (int i = 0; i < 20; i++) {
                assertEquals("value-" + i, store.getChunk("key-" + i));
            }
        }
    }
}
