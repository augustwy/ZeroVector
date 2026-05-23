package cn.nexon.zerovector.core.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

class MMapDocumentStoreTest {
    private static final Logger logger = LoggerFactory.getLogger(MMapDocumentStoreTest.class);
    
    @TempDir
    Path tempDir;
    
    private MMapDocumentStore store;
    
    @BeforeEach
    void setUp() throws IOException {
        Path storePath = tempDir.resolve("test.store");
        store = MMapDocumentStore.open(storePath.toString());
    }
    
    @AfterEach
    void tearDown() throws IOException {
        if (store != null) {
            store.close();
        }
    }
    
    @Test
    void testAddAndGetChunk() throws IOException {
        String chunkId = "chunk1";
        String content = "This is a test chunk content";
        
        MMapDocumentStore.FileLocation location = store.addChunk(chunkId, content);
        assertNotNull(location);
        assertEquals(0, location.offset());
        assertEquals(content.getBytes().length, location.length());
        
        String retrieved = store.getChunk(chunkId);
        assertEquals(content, retrieved);
    }
    
    @Test
    void testAddMultipleChunks() throws IOException {
        String chunk1 = "First chunk";
        String chunk2 = "Second chunk";
        String chunk3 = "Third chunk";
        
        store.addChunk("chunk1", chunk1);
        store.addChunk("chunk2", chunk2);
        store.addChunk("chunk3", chunk3);
        
        assertEquals(chunk1, store.getChunk("chunk1"));
        assertEquals(chunk2, store.getChunk("chunk2"));
        assertEquals(chunk3, store.getChunk("chunk3"));
    }
    
    @Test
    void testGetNonExistentChunk() {
        String result = store.getChunk("nonexistent");
        assertNull(result);
    }
    
    @Test
    void testBufferExpansion() throws IOException {
        String largeContent = "A".repeat(2000000);
        String chunkId = "large_chunk";
        
        MMapDocumentStore.FileLocation location = store.addChunk(chunkId, largeContent);
        assertNotNull(location);
        
        String retrieved = store.getChunk(chunkId);
        assertEquals(largeContent, retrieved);
    }
    
    @Test
    void testSaveAndLoadIndex() throws IOException {
        store.addChunk("chunk1", "Content 1");
        store.addChunk("chunk2", "Content 2");
        
        store.saveIndex();
        
        MMapDocumentStore newStore = MMapDocumentStore.open(store.getDataFilePath());
        assertEquals("Content 1", newStore.getChunk("chunk1"));
        assertEquals("Content 2", newStore.getChunk("chunk2"));
        newStore.close();
    }
}
