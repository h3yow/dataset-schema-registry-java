package com.github.h3yow.datasetschemaregistryjava.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.h3yow.datasetschemaregistryjava.compatibility.CompatibilityChecker;
import com.github.h3yow.datasetschemaregistryjava.exception.RegistryException;
import com.github.h3yow.datasetschemaregistryjava.model.FieldType;
import com.github.h3yow.datasetschemaregistryjava.model.SchemaField;
import com.github.h3yow.datasetschemaregistryjava.model.SchemaVersion;
import com.github.h3yow.datasetschemaregistryjava.service.SchemaRegistryService;
import com.github.h3yow.datasetschemaregistryjava.store.SchemaStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 注册拒绝场景：版本号重复、内容完全相同、父版本不存在、链路断裂，
 * 各类失败原因必须可互相区分，且已有版本不被静默覆盖。
 */
class RegistrationRejectionTest {

    private static final Logger log = LoggerFactory.getLogger(RegistrationRejectionTest.class);

    private static final String DATASET = "orders";

    @TempDir
    Path tempDir;

    private SchemaRegistryService service;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        SchemaStore store = new SchemaStore(tempDir.toString(), mapper);
        service = new SchemaRegistryService(store, new CompatibilityChecker());
    }

    private static List<SchemaField> v1Fields() {
        return List.of(
                new SchemaField("id", FieldType.LONG, false, null),
                new SchemaField("amount", FieldType.DOUBLE, true, null));
    }

    private static List<SchemaField> v2Fields() {
        return List.of(
                new SchemaField("id", FieldType.LONG, false, null),
                new SchemaField("amount", FieldType.DOUBLE, true, null),
                new SchemaField("currency", FieldType.STRING, true, null));
    }

    @Test
    void duplicateVersionNumberIsRejectedAndNotOverwritten() {
        SchemaVersion v1 = service.register(DATASET, 1, null, v1Fields());
        log.info("输入: 已注册 version=1, fields={}", v1.fields());

        RegistryException ex = assertThrows(RegistryException.class,
                () -> service.register(DATASET, 1, null, v2Fields()));

        log.info("判定依据: 版本号 1 已存在，禁止覆盖; 最终结论: code={}, message={}", ex.getCode(), ex.getMessage());
        assertEquals(RegistryException.Code.DUPLICATE_VERSION, ex.getCode());
        // 已有版本未被静默覆盖
        assertEquals(v1Fields(), service.get(DATASET, 1).fields());
        assertEquals(1, service.list(DATASET).size());
    }

    @Test
    void identicalContentIsRejectedWithDistinctReason() {
        service.register(DATASET, 1, null, v1Fields());
        log.info("输入: 已注册 version=1, 再次以 version=2 提交完全相同的内容");

        RegistryException ex = assertThrows(RegistryException.class,
                () -> service.register(DATASET, 2, null, v1Fields()));

        log.info("判定依据: 内容指纹与版本 1 相同; 最终结论: code={}, message={}", ex.getCode(), ex.getMessage());
        assertEquals(RegistryException.Code.IDENTICAL_CONTENT, ex.getCode());
        assertEquals(1, service.list(DATASET).size());
    }

    @Test
    void unknownParentVersionIsRejected() {
        service.register(DATASET, 1, null, v1Fields());
        log.info("输入: 已注册 version=1, 新版本指定 parentVersion=99");

        RegistryException ex = assertThrows(RegistryException.class,
                () -> service.register(DATASET, 2, 99, v2Fields()));

        log.info("判定依据: 父版本 99 不存在; 最终结论: code={}, message={}", ex.getCode(), ex.getMessage());
        assertEquals(RegistryException.Code.UNKNOWN_PARENT_VERSION, ex.getCode());
        assertEquals(1, service.list(DATASET).size());
    }

    @Test
    void brokenChainIsRejected() {
        service.register(DATASET, 1, null, v1Fields());
        service.register(DATASET, 2, null, v2Fields());
        log.info("输入: 已注册 version=1,2, 新版本指定 parentVersion=1（非最新版）");

        RegistryException ex = assertThrows(RegistryException.class,
                () -> service.register(DATASET, 3, 1, List.of(
                        new SchemaField("id", FieldType.LONG, false, null),
                        new SchemaField("amount", FieldType.DOUBLE, true, null),
                        new SchemaField("currency", FieldType.STRING, true, null),
                        new SchemaField("tag", FieldType.STRING, true, null))));

        log.info("判定依据: 父版本 1 不是当前最新版 2，演进链断裂; 最终结论: code={}, message={}",
                ex.getCode(), ex.getMessage());
        assertEquals(RegistryException.Code.BROKEN_CHAIN, ex.getCode());
        assertEquals(2, service.list(DATASET).size());
    }

    @Test
    void incompatibleEvolutionIsRejectedWithConflictLocation() {
        service.register(DATASET, 1, null, v1Fields());
        log.info("输入: 已注册 version=1, 新版本删除 amount 字段");

        RegistryException ex = assertThrows(RegistryException.class,
                () -> service.register(DATASET, 2, null,
                        List.of(new SchemaField("id", FieldType.LONG, false, null))));

        log.info("判定依据: 删除字段属破坏性变更; 最终结论: code={}, message={}", ex.getCode(), ex.getMessage());
        assertEquals(RegistryException.Code.INCOMPATIBLE_SCHEMA, ex.getCode());
        assertTrue(ex.getMessage().contains("amount"), "错误信息应指出冲突位置 amount");
        assertEquals(1, service.list(DATASET).size());
    }

    @Test
    void compatibleEvolutionPassesAndLatestIsUpdated() {
        service.register(DATASET, 1, null, v1Fields());
        log.info("输入: 在 version=1 基础上新增可空字段 currency 作为 version=2");

        SchemaVersion v2 = service.register(DATASET, 2, null, v2Fields());

        log.info("判定依据: 新增可空字段是兼容演进; 最终结论: 注册成功, parent={}, latest={}",
                v2.parentVersion(), service.getLatest(DATASET).version());
        assertEquals(1, v2.parentVersion());
        assertEquals(2, service.getLatest(DATASET).version());
        assertEquals(v1Fields(), service.get(DATASET, 1).fields());
    }

    @Test
    void queryByVersionAndLatest() {
        service.register(DATASET, 1, null, v1Fields());
        service.register(DATASET, 2, null, v2Fields());
        log.info("输入: 已注册 version=1,2，分别按指定版本与最新版查询");

        SchemaVersion byVersion = service.get(DATASET, 1);
        SchemaVersion latest = service.getLatest(DATASET);

        log.info("最终结论: get(1).version={}, latest.version={}", byVersion.version(), latest.version());
        assertEquals(1, byVersion.version());
        assertEquals(2, latest.version());

        RegistryException ex = assertThrows(RegistryException.class, () -> service.get(DATASET, 42));
        log.info("查询不存在版本 42 -> code={}", ex.getCode());
        assertEquals(RegistryException.Code.VERSION_NOT_FOUND, ex.getCode());
    }
}
