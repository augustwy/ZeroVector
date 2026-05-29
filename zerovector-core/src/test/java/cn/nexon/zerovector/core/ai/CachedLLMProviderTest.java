package cn.nexon.zerovector.core.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CachedLLMProviderTest {

    @Mock
    LLMProvider delegate;

    @Test
    void chat_firstCall_callsDelegate() {
        when(delegate.chat(anyString(), any())).thenReturn(LLMResponse.success("result", 10, 20, 5L));

        CachedLLMProvider cached = new CachedLLMProvider(delegate);
        LLMResponse response = cached.chat("hello", SmartCacheStrategy.RequestType.GENERATE_SUMMARY);

        assertEquals("result", response.content());
        verify(delegate, times(1)).chat("hello", SmartCacheStrategy.RequestType.GENERATE_SUMMARY);
    }

    @Test
    void chat_secondCall_returnsCached() {
        when(delegate.chat(anyString(), any())).thenReturn(LLMResponse.success("cached", 10, 20, 5L));

        CachedLLMProvider cached = new CachedLLMProvider(delegate);
        cached.chat("hello", SmartCacheStrategy.RequestType.GENERATE_SUMMARY);
        LLMResponse response = cached.chat("hello", SmartCacheStrategy.RequestType.GENERATE_SUMMARY);

        assertEquals("cached", response.content());
        verify(delegate, times(1)).chat(anyString(), any());
    }

    @Test
    void chat_differentTypes_isolatedCaches() {
        when(delegate.chat(anyString(), any())).thenReturn(LLMResponse.success("result", 5L));

        CachedLLMProvider cached = new CachedLLMProvider(delegate);
        cached.chat("hello", SmartCacheStrategy.RequestType.COMPREHEND_CHUNK);
        cached.chat("hello", SmartCacheStrategy.RequestType.GENERATE_SUMMARY);

        verify(delegate, times(2)).chat(anyString(), any());
    }

    @Test
    void chat_differentInputs_notCached() {
        when(delegate.chat(anyString(), any())).thenReturn(LLMResponse.success("r", 5L));

        CachedLLMProvider cached = new CachedLLMProvider(delegate);
        cached.chat("query one", SmartCacheStrategy.RequestType.EXTRACT_KEYWORDS);
        cached.chat("query two", SmartCacheStrategy.RequestType.EXTRACT_KEYWORDS);

        verify(delegate, times(2)).chat(anyString(), any());
    }

    @Test
    void clearCache_invalidatesAll() {
        when(delegate.chat(anyString(), any())).thenReturn(LLMResponse.success("r", 5L));

        CachedLLMProvider cached = new CachedLLMProvider(delegate);
        cached.chat("hello", SmartCacheStrategy.RequestType.EXTRACT_KEYWORDS);
        cached.clearCache();
        cached.chat("hello", SmartCacheStrategy.RequestType.EXTRACT_KEYWORDS);

        verify(delegate, times(2)).chat(anyString(), any());
    }

    @Test
    void clearCache_byType() {
        when(delegate.chat(anyString(), any())).thenReturn(LLMResponse.success("r", 5L));

        CachedLLMProvider cached = new CachedLLMProvider(delegate);
        cached.chat("hello", SmartCacheStrategy.RequestType.COMPREHEND_CHUNK);
        cached.chat("hello", SmartCacheStrategy.RequestType.GENERATE_SUMMARY);

        cached.clearCache(SmartCacheStrategy.RequestType.COMPREHEND_CHUNK);

        cached.chat("hello", SmartCacheStrategy.RequestType.COMPREHEND_CHUNK);
        cached.chat("hello", SmartCacheStrategy.RequestType.GENERATE_SUMMARY);

        verify(delegate, times(2)).chat("hello", SmartCacheStrategy.RequestType.COMPREHEND_CHUNK);
        verify(delegate, times(1)).chat("hello", SmartCacheStrategy.RequestType.GENERATE_SUMMARY);
    }

    @Test
    void getStatistics_returnsStats() {
        when(delegate.chat(anyString(), any())).thenReturn(LLMResponse.success("r", 5L));

        CachedLLMProvider cached = new CachedLLMProvider(delegate);
        cached.chat("hello", SmartCacheStrategy.RequestType.COMPREHEND_CHUNK);

        Map<SmartCacheStrategy.RequestType, CacheStatistics> stats = cached.getStatistics();
        assertNotNull(stats);
        assertTrue(stats.containsKey(SmartCacheStrategy.RequestType.COMPREHEND_CHUNK));
    }

    @Test
    void getCacheSize_returnsEstimatedSize() {
        when(delegate.chat(anyString(), any())).thenReturn(LLMResponse.success("r", 5L));

        CachedLLMProvider cached = new CachedLLMProvider(delegate);
        cached.chat("a", SmartCacheStrategy.RequestType.COMPREHEND_CHUNK);
        cached.chat("b", SmartCacheStrategy.RequestType.COMPREHEND_CHUNK);

        assertTrue(cached.getCacheSize(SmartCacheStrategy.RequestType.COMPREHEND_CHUNK) >= 2);
    }

    @Test
    void warmupCache_populatesCache() {
        when(delegate.chat(anyString(), any())).thenReturn(LLMResponse.success("warmed", 5L));

        CachedLLMProvider cached = new CachedLLMProvider(delegate);
        Map<SmartCacheStrategy.RequestType, List<String>> warmupPrompts = Map.of(
            SmartCacheStrategy.RequestType.EXTRACT_KEYWORDS, List.of("prompt1", "prompt2")
        );
        cached.warmupCache(warmupPrompts);

        // 3rd call should be cached (2 from warmup)
        cached.chat("prompt1", SmartCacheStrategy.RequestType.EXTRACT_KEYWORDS);
        verify(delegate, times(2)).chat(anyString(), any());
    }

    @Test
    void updateCacheConfig_rebuildsCache() {
        when(delegate.chat(anyString(), any())).thenReturn(LLMResponse.success("r", 5L));

        CachedLLMProvider cached = new CachedLLMProvider(delegate);
        cached.chat("hello", SmartCacheStrategy.RequestType.COMPREHEND_CHUNK);

        cached.updateCacheConfig(SmartCacheStrategy.RequestType.COMPREHEND_CHUNK,
            CacheConfig.DEFAULT_COMPREHEND);

        cached.chat("hello", SmartCacheStrategy.RequestType.COMPREHEND_CHUNK);
        verify(delegate, times(2)).chat("hello", SmartCacheStrategy.RequestType.COMPREHEND_CHUNK);
    }

    @Test
    void updateCacheConfig_disabled_disablesCache() {
        when(delegate.chat(anyString(), any())).thenReturn(LLMResponse.success("r", 5L));

        CachedLLMProvider cached = new CachedLLMProvider(delegate);
        cached.updateCacheConfig(SmartCacheStrategy.RequestType.COMPREHEND_CHUNK,
            CacheConfig.disabled());

        cached.chat("hello", SmartCacheStrategy.RequestType.COMPREHEND_CHUNK);
        cached.chat("hello", SmartCacheStrategy.RequestType.COMPREHEND_CHUNK);

        verify(delegate, times(2)).chat(anyString(), any());
    }

    @Test
    void totalCacheSize_aggregatesAllTypes() {
        when(delegate.chat(anyString(), any())).thenReturn(LLMResponse.success("r", 5L));

        CachedLLMProvider cached = new CachedLLMProvider(delegate);
        cached.chat("a", SmartCacheStrategy.RequestType.COMPREHEND_CHUNK);
        cached.chat("b", SmartCacheStrategy.RequestType.GENERATE_SUMMARY);

        assertTrue(cached.getTotalCacheSize() >= 2);
    }

    @Test
    void similarPrompt_oneCharDiff_usesCachedResult() {
        // "hello world" vs "hello xorld": 1 char diff, sim=0.909 > 0.85 threshold
        when(delegate.chat(anyString(), any())).thenReturn(LLMResponse.success("cached result", 5L));

        CachedLLMProvider cached = new CachedLLMProvider(delegate);
        cached.chat("hello world", SmartCacheStrategy.RequestType.EXTRACT_QUERY_KEYWORDS);

        LLMResponse response = cached.chat("hello xorld",
            SmartCacheStrategy.RequestType.EXTRACT_QUERY_KEYWORDS);

        assertEquals("cached result", response.content());
        verify(delegate, times(1)).chat(anyString(), any());
    }

    @Test
    void chat_delegateFailure_propagatesException() {
        when(delegate.chat(anyString(), any())).thenThrow(new RuntimeException("API error"));

        CachedLLMProvider cached = new CachedLLMProvider(delegate);
        assertThrows(RuntimeException.class,
            () -> cached.chat("fail", SmartCacheStrategy.RequestType.GENERATE_SUMMARY));
    }

    @Test
    void setMaxSearchItems_positiveValue_accepted() {
        CachedLLMProvider cached = new CachedLLMProvider(delegate);
        cached.setMaxSearchItems(50);
        assertEquals(50, cached.getMaxSearchItems());

        // Does not throw
        assertDoesNotThrow(() -> cached.setMaxSearchItems(50));
    }
}
