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

package cn.nexon.zerovector.core;

import cn.nexon.zerovector.core.ai.LLMProvider;
import cn.nexon.zerovector.core.ai.LLMResponse;
import cn.nexon.zerovector.core.ai.SmartCacheStrategy;
import cn.nexon.zerovector.core.config.ConcurrencyProperties;
import cn.nexon.zerovector.core.document.comprehend.DocumentComprehender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeBaseManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void emptyKnowledgeBase_survivesRestart() {
        Path base = tempDir.resolve("kb");
        LLMProvider llm = new NoopLLM();

        try (KnowledgeBaseManager manager = new KnowledgeBaseManager(
                llm, new DocumentComprehender(llm, 1000, null),
                new ConcurrencyProperties(), base.toString(), true, 10, null)) {
            manager.initialize();
            manager.createKnowledgeBase("empty");
            assertTrue(manager.exists("empty"));
        }

        try (KnowledgeBaseManager manager = new KnowledgeBaseManager(
                new NoopLLM(), new DocumentComprehender(new NoopLLM(), 1000, null),
                new ConcurrencyProperties(), base.toString(), true, 10, null)) {
            manager.initialize();
            assertTrue(manager.exists("default"));
            assertTrue(manager.exists("empty"));
            assertTrue(Files.exists(base.resolve("empty").resolve(".zerovector-kb")));
        }
    }

    @Test
    void cannotDeleteDefaultKnowledgeBase() {
        Path base = tempDir.resolve("kb2");
        LLMProvider llm = new NoopLLM();
        try (KnowledgeBaseManager manager = new KnowledgeBaseManager(
                llm, new DocumentComprehender(llm, 1000, null),
                new ConcurrencyProperties(), base.toString(), true, 10, null)) {
            manager.initialize();
            assertThrows(IllegalArgumentException.class, () -> manager.deleteKnowledgeBase("default"));
        }
    }

    private static final class NoopLLM implements LLMProvider {
        @Override
        public LLMResponse chat(String prompt, SmartCacheStrategy.RequestType type) {
            return LLMResponse.failure("not used");
        }
    }
}
