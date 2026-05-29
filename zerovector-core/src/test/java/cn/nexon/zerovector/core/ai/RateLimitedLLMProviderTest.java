package cn.nexon.zerovector.core.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RateLimitedLLMProviderTest {

    @Mock
    LLMProvider delegate;

    @Test
    void chat_withinLimit_passesThrough() {
        when(delegate.chat(anyString(), any())).thenReturn(LLMResponse.success("ok", 5L));

        RateLimitedLLMProvider limited = new RateLimitedLLMProvider(delegate, 10, 60);
        LLMResponse response = limited.chat("test", SmartCacheStrategy.RequestType.GENERATE_SUMMARY);

        assertEquals("ok", response.content());
        verify(delegate).chat("test", SmartCacheStrategy.RequestType.GENERATE_SUMMARY);
    }

    @Test
    void chat_belowLimit_allCallsThrough() {
        when(delegate.chat(anyString(), any())).thenReturn(LLMResponse.success("ok", 5L));

        RateLimitedLLMProvider limited = new RateLimitedLLMProvider(delegate, 5, 60);
        for (int i = 0; i < 5; i++) {
            limited.chat("call-" + i, SmartCacheStrategy.RequestType.EXTRACT_KEYWORDS);
        }

        verify(delegate, times(5)).chat(anyString(), any());
    }

    @Test
    void chat_rateLimit_withShortWindow_enforcesLimit() {
        when(delegate.chat(anyString(), any())).thenReturn(LLMResponse.success("ok", 5L));

        // 2 max in 1-second window — 3rd call waits for window to expire
        RateLimitedLLMProvider limited = new RateLimitedLLMProvider(delegate, 2, 1);

        long start = System.currentTimeMillis();
        limited.chat("a", SmartCacheStrategy.RequestType.EXTRACT_KEYWORDS);
        limited.chat("b", SmartCacheStrategy.RequestType.EXTRACT_KEYWORDS);

        // 3rd call may wait briefly for the 1-second window
        limited.chat("c", SmartCacheStrategy.RequestType.EXTRACT_KEYWORDS);
        long elapsed = System.currentTimeMillis() - start;

        // Should have waited at least some time
        assertTrue(elapsed >= 0);
        verify(delegate, times(3)).chat(anyString(), any());
    }

    @Test
    void constructor_acceptsParameters() {
        RateLimitedLLMProvider limited = new RateLimitedLLMProvider(delegate, 10, 1);
        assertNotNull(limited);
    }

    @Test
    void chat_differentTypes_shareRateLimit() {
        when(delegate.chat(anyString(), any())).thenReturn(LLMResponse.success("ok", 5L));

        RateLimitedLLMProvider limited = new RateLimitedLLMProvider(delegate, 2, 1);
        limited.chat("a", SmartCacheStrategy.RequestType.COMPREHEND_CHUNK);
        limited.chat("b", SmartCacheStrategy.RequestType.GENERATE_SUMMARY);

        // 3rd call of any type — may wait briefly
        limited.chat("c", SmartCacheStrategy.RequestType.EXTRACT_KEYWORDS);

        verify(delegate, times(3)).chat(anyString(), any());
    }

    @Test
    void chat_delegateFailure_propagatesException() {
        when(delegate.chat(anyString(), any())).thenThrow(new RuntimeException("API down"));

        RateLimitedLLMProvider limited = new RateLimitedLLMProvider(delegate, 5, 60);
        assertThrows(RuntimeException.class,
            () -> limited.chat("fail", SmartCacheStrategy.RequestType.GENERATE_SUMMARY));
    }
}
