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

package cn.nexon.zerovector.springboot.example;

import cn.nexon.zerovector.springboot.SemanticHub;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证自动配置、LLM 适配器与默认知识库能一起启动。
 */
@SpringBootTest(properties = {
        "zerovector.storage-base-path=target/test-knowledge-bases",
        "spring.ai.openai.api-key=test-key"
})
class ZeroVectorContextTest {

    @Autowired
    private SemanticHub semanticHub;

    @Test
    void contextLoadsWithDefaultKnowledgeBase() {
        assertNotNull(semanticHub);
        assertTrue(semanticHub.knowledgeBaseExists("default"));
    }
}
