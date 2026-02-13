package cn.nexon.zerovector.springboot.example;

import cn.nexon.zerovector.core.SemanticTreeService;
import cn.nexon.zerovector.core.ai.LLMService;
import cn.nexon.zerovector.core.model.DocumentChunk;
import cn.nexon.zerovector.core.model.NavigationPath;
import cn.nexon.zerovector.springboot.service.ZeroVectorService;
import cn.nexon.zerovector.springboot.autoconfigure.ZeroVectorAutoConfiguration;
import org.junit.Before;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.FileCopyUtils;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ZeroVector设计模式文档导入和查询测试
 */
@SpringBootTest(classes = {ZeroVectorSpringBootApplication.class})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DesignPatternsDocumentTest {

    @Autowired
    private ZeroVectorService zeroVectorService;

    /**
     * 测试设计模式文档的导入功能
     */
    @Test
    @Order(1)
    public void testImportDesignPatterns() throws Exception {
        // 使用文档处理功能，直接从文件路径处理
        ClassPathResource designPatternsResource = new ClassPathResource("软件设计模式.md");
        ClassPathResource scissorsResource = new ClassPathResource("寂静的轰鸣：论一把手工剪刀的重量.md");

        // 配置文档处理参数
        zeroVectorService.setNoChunkingMode();

        if (designPatternsResource.exists()) {
            // 使用文档处理功能添加设计模式文档
            zeroVectorService.addDocument(designPatternsResource.getFile().toPath());
            System.out.println("成功导入软件设计模式.md");
        }
        
        if (scissorsResource.exists()) {
            // 使用文档处理功能添加剪刀文档
            zeroVectorService.addDocument(scissorsResource.getFile().toPath());
            System.out.println("成功导入寂静的轰鸣：论一把手工剪刀的重量.md");
        }
    }

    /**
     * 测试设计模式文档的查询功能
     */
    @Test
    @Order(2)
    public void testSearchDesignPatterns() throws Exception {

        // 2. 测试查询功能
        // 测试查询创建型模式
        ZeroVectorService.SearchResult result1 = zeroVectorService.search("一把好剪刀有什么特质");
        List<NavigationPath> path = result1.path();
        System.out.println("查询'什么是单例模式'的导航路径: " + path);
    }
}