package cn.nexon.zerovector.core.storage.local;

import cn.nexon.zerovector.core.exception.StorageException;
import cn.nexon.zerovector.core.model.DocumentChunk;
import cn.nexon.zerovector.core.storage.config.ChunkStorageConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LocalFileChunkStorageTest {

    @TempDir
    Path tempDir;

    private ChunkStorageConfig createConfig() {
        ChunkStorageConfig config = new ChunkStorageConfig();
        config.setBasePath(tempDir.resolve("chunks.data").toString());
        config.setUseMmap(false);
        config.setSharded(false);
        return config;
    }

    @Test
    void initialize_createsStorage() throws IOException {
        try (LocalFileChunkStorage storage = new LocalFileChunkStorage()) {
            storage.initialize(createConfig());
            assertTrue(storage.isHealthy());
        }
    }

    @Test
    void saveAndGetChunk_roundtrip() throws IOException {
        try (LocalFileChunkStorage storage = new LocalFileChunkStorage()) {
            storage.initialize(createConfig());
            DocumentChunk chunk = DocumentChunk.withContent("c1", "hello world", "summary", Map.of());
            storage.saveChunk(chunk);

            DocumentChunk retrieved = storage.getChunk("c1");
            assertNotNull(retrieved);
            assertEquals("c1", retrieved.id());
            assertEquals("hello world", retrieved.content());
        }
    }

    @Test
    void saveAndGetChunk_filePathBased() throws IOException {
        try (LocalFileChunkStorage storage = new LocalFileChunkStorage()) {
            storage.initialize(createConfig());
            DocumentChunk chunk = DocumentChunk.withFilePath("c1", "summary", "/path/to/file", Map.of());
            storage.saveChunk(chunk);

            DocumentChunk retrieved = storage.getChunk("c1");
            assertNotNull(retrieved);
            assertTrue(retrieved.isFilePathBased());
        }
    }

    @Test
    void getChunk_nonexistent_returnsNull() throws IOException {
        try (LocalFileChunkStorage storage = new LocalFileChunkStorage()) {
            storage.initialize(createConfig());
            assertNull(storage.getChunk("nonexistent"));
        }
    }

    @Test
    void saveChunks_batch() throws IOException {
        try (LocalFileChunkStorage storage = new LocalFileChunkStorage()) {
            storage.initialize(createConfig());
            Map<String, DocumentChunk> chunks = Map.of(
                "k1", DocumentChunk.withContent("k1", "val1", "sum1", Map.of()),
                "k2", DocumentChunk.withContent("k2", "val2", "sum2", Map.of())
            );
            storage.saveChunks(chunks);

            assertEquals(2, storage.getAllChunkIds().size());
            assertNotNull(storage.getChunk("k1"));
            assertNotNull(storage.getChunk("k2"));
        }
    }

    @Test
    void deleteChunk_removesFromCache() throws IOException {
        try (LocalFileChunkStorage storage = new LocalFileChunkStorage()) {
            storage.initialize(createConfig());
            storage.saveChunk(DocumentChunk.withContent("c1", "content", "summary", Map.of()));
            assertTrue(storage.exists("c1"));

            storage.deleteChunk("c1");
            assertFalse(storage.exists("c1"));
        }
    }

    @Test
    void clear_removesAll() throws IOException {
        try (LocalFileChunkStorage storage = new LocalFileChunkStorage()) {
            storage.initialize(createConfig());
            storage.saveChunk(DocumentChunk.withContent("c1", "a", "s", Map.of()));
            storage.saveChunk(DocumentChunk.withContent("c2", "b", "s", Map.of()));
            storage.clear();

            assertTrue(storage.getAllChunkIds().isEmpty());
        }
    }

    @Test
    void exists_checksCache() throws IOException {
        try (LocalFileChunkStorage storage = new LocalFileChunkStorage()) {
            storage.initialize(createConfig());
            assertFalse(storage.exists("nonexistent"));

            storage.saveChunk(DocumentChunk.withContent("c1", "c", "s", Map.of()));
            assertTrue(storage.exists("c1"));
        }
    }

    @Test
    void getStorageType_returnsLocalMmap() {
        LocalFileChunkStorage storage = new LocalFileChunkStorage();
        assertEquals("local-mmap", storage.getStorageType());
    }

    @Test
    void getChunks_batch() throws IOException {
        try (LocalFileChunkStorage storage = new LocalFileChunkStorage()) {
            storage.initialize(createConfig());
            storage.saveChunk(DocumentChunk.withContent("a", "val_a", "s", Map.of()));
            storage.saveChunk(DocumentChunk.withContent("b", "val_b", "s", Map.of()));
            storage.saveChunk(DocumentChunk.withContent("c", "val_c", "s", Map.of()));

            Map<String, DocumentChunk> result = storage.getChunks(List.of("a", "c"));
            assertEquals(2, result.size());
            assertTrue(result.containsKey("a"));
            assertTrue(result.containsKey("c"));
            assertFalse(result.containsKey("b"));
        }
    }

    @Test
    void notInitialized_throwsException() {
        LocalFileChunkStorage storage = new LocalFileChunkStorage();
        assertThrows(StorageException.class, () -> storage.saveChunk(
            DocumentChunk.withContent("c1", "x", "s", Map.of())));
        assertThrows(StorageException.class, () -> storage.clear());
    }

    @Test
    void doubleInitialize_doesNotThrow() throws IOException {
        try (LocalFileChunkStorage storage = new LocalFileChunkStorage()) {
            storage.initialize(createConfig());
            storage.initialize(createConfig());
            assertTrue(storage.isHealthy());
        }
    }

    @Test
    void getChunkContent_contentBased_returnsContent() throws IOException {
        try (LocalFileChunkStorage storage = new LocalFileChunkStorage()) {
            storage.initialize(createConfig());
            storage.saveChunk(DocumentChunk.withContent("c1", "my content", "sum", Map.of()));
            assertEquals("my content", storage.getChunkContent("c1"));
        }
    }

    @Test
    void getAllChunkIds_returnsUnmodifiable() throws IOException {
        try (LocalFileChunkStorage storage = new LocalFileChunkStorage()) {
            storage.initialize(createConfig());
            storage.saveChunk(DocumentChunk.withContent("c1", "x", "s", Map.of()));
            Set<String> ids = storage.getAllChunkIds();
            assertThrows(UnsupportedOperationException.class, () -> ids.add("extra"));
        }
    }
}
