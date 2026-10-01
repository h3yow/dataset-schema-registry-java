package com.github.h3yow.datasetschemaregistryjava.tests;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端集成测试：HTTP 状态码 + 错误码必须能互相区分三类失败。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SchemaRegistryWebIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SchemaRegistryWebIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;

    private final String base = "/api/datasets/web_ds/versions";

    @Test
    @Order(1)
    void fullLifecycleOverHttp() throws Exception {
        // v1 首个版本
        String v1 = "{\"fields\":[{\"name\":\"id\",\"type\":\"LONG\",\"nullable\":false}]}";
        log.info("【输入】POST {} body={}", base, v1);
        mockMvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(v1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.head").value(true));
        log.info("【最终结论】v1 注册成功 201");

        // 兼容演进 v2：新增可空字段
        String v2 = "{\"fields\":["
                + "{\"name\":\"id\",\"type\":\"LONG\",\"nullable\":false},"
                + "{\"name\":\"name\",\"type\":\"STRING\",\"nullable\":true}]}";
        mockMvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(v2))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.parentVersion").value(1));
        log.info("【最终结论】v2 兼容演进注册成功 201");

        // 重复版本号 -> 409 DUPLICATE_VERSION
        String dupVersion = "{\"version\":1,\"parentVersion\":1,\"fields\":["
                + "{\"name\":\"id\",\"type\":\"LONG\",\"nullable\":false},"
                + "{\"name\":\"x\",\"type\":\"STRING\",\"nullable\":true}]}";
        mockMvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(dupVersion))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE_VERSION"));
        log.info("【最终结论】重复版本号 -> 409 DUPLICATE_VERSION");

        // 内容完全相同 -> 409 DUPLICATE_SCHEMA（自动分配 v3）
        mockMvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(v2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE_SCHEMA"));
        log.info("【最终结论】相同内容 -> 409 DUPLICATE_SCHEMA");

        // 父版本不存在 -> 404 PARENT_VERSION_NOT_FOUND
        String badParent = "{\"version\":3,\"parentVersion\":99,\"fields\":["
                + "{\"name\":\"id\",\"type\":\"LONG\",\"nullable\":false},"
                + "{\"name\":\"y\",\"type\":\"STRING\",\"nullable\":true}]}";
        mockMvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(badParent))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PARENT_VERSION_NOT_FOUND"));
        log.info("【最终结论】父版本不存在 -> 404 PARENT_VERSION_NOT_FOUND");

        // 破坏性变更 -> 422 INCOMPATIBLE_SCHEMA + 冲突位置
        String incompatible = "{\"fields\":[{\"name\":\"id\",\"type\":\"STRING\",\"nullable\":false}]}";
        mockMvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(incompatible))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("INCOMPATIBLE_SCHEMA"))
                .andExpect(jsonPath("$.conflicts[0]").exists());
        log.info("【最终结论】类型变更 -> 422 INCOMPATIBLE_SCHEMA（含冲突定位）");

        // 按版本查询 / latest / 列表
        mockMvc.perform(get(base + "/1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        mockMvc.perform(get(base + "/latest")).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(get(base)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // 回滚到 v1
        mockMvc.perform(post(base + "/rollback")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        mockMvc.perform(get(base + "/latest")).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        log.info("【最终结论】回滚 head -> v1，历史 v2 仍可按版本查询");

        // 无效回滚：回滚到当前 head
        mockMvc.perform(post(base + "/rollback")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_ROLLBACK"));
        log.info("【最终结论】回滚到当前 head -> 400 INVALID_ROLLBACK");
    }

    @Test
    @Order(2)
    void unknownDatasetReturns404() throws Exception {
        mockMvc.perform(get("/api/datasets/no_such_dataset/versions/latest"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("DATASET_NOT_FOUND"));
        log.info("【最终结论】未知数据集 -> 404 DATASET_NOT_FOUND");
    }
}
