package com.github.h3yow.datasetschemaregistryjava.tests;

import com.github.h3yow.datasetschemaregistryjava.compat.CompatibilityChecker;
import com.github.h3yow.datasetschemaregistryjava.exception.DuplicateSchemaException;
import com.github.h3yow.datasetschemaregistryjava.exception.DuplicateVersionException;
import com.github.h3yow.datasetschemaregistryjava.exception.IncompatibleSchemaException;
import com.github.h3yow.datasetschemaregistryjava.exception.InvalidEvolutionChainException;
import com.github.h3yow.datasetschemaregistryjava.exception.InvalidRollbackException;
import com.github.h3yow.datasetschemaregistryjava.exception.ParentVersionNotFoundException;
import com.github.h3yow.datasetschemaregistryjava.exception.RegistryException;
import com.github.h3yow.datasetschemaregistryjava.exception.VersionNotFoundException;
import com.github.h3yow.datasetschemaregistryjava.model.FieldType;
import com.github.h3yow.datasetschemaregistryjava.model.SchemaDefinition;
import com.github.h3yow.datasetschemaregistryjava.model.SchemaField;
import com.github.h3yow.datasetschemaregistryjava.model.SchemaVersion;
import com.github.h3yow.datasetschemaregistryjava.service.SchemaRegistryService;
import com.github.h3yow.datasetschemaregistryjava.store.LocalFileSchemaStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 注册服务单测：重复注册 / 不兼容变更 / 演进链 / 查询 / 回滚。
 * 每个用例打印输入、判定依据与最终结论。
 */
class SchemaRegistryServiceTest {

    private static final Logger log = LoggerFactory.getLogger(SchemaRegistryServiceTest.class);
    private static final String DATASET = "orders";

    @TempDir
    Path tempDir;

    private SchemaRegistryService service;

    private SchemaField f(String name, FieldType type, boolean nullable) {
        return new SchemaField(name, type, nullable, null);
    }

    private SchemaDefinition def(SchemaField... fields) {
        return new SchemaDefinition(List.of(fields));
    }

    @BeforeEach
    void setUp() {
        LocalFileSchemaStore store = new LocalFileSchemaStore(tempDir.toString());
        store.init();
        service = new SchemaRegistryService(store, new CompatibilityChecker());
    }

    private void logInput(String action, SchemaDefinition schema) {
        log.info("【输入】操作={}, dataset={}, schema={}", action, DATASET, schema);
    }

    private void expectFailure(String action, SchemaDefinition input,
                               RegistryException ex) {
        log.info("【输入】操作={}, dataset={}", action, DATASET);
        log.info("【判定依据】错误码={}, 原因={}", ex.getErrorCode(), ex.getMessage());
        if (ex instanceof IncompatibleSchemaException ie) {
            ie.getConflicts().forEach(c -> log.info("【判定依据】冲突位置={}, 类型={}, 说明={}",
                    c.path(), c.reason(), c.detail()));
        }
        log.info("【最终结论】REJECTED ({})", ex.getErrorCode());
    }

    @Test
    void firstVersionStartsAtOne() {
        SchemaDefinition v1 = def(f("id", FieldType.LONG, false));
        logInput("注册首个版本", v1);

        SchemaVersion saved = service.register(DATASET, null, null, v1);

        assertEquals(1, saved.version());
        assertEquals(null, saved.parentVersion());
        assertEquals(service.getLatest(DATASET).version(), saved.version());
        log.info("【最终结论】ACCEPTED version=v1, head=v1");
    }

    @Test
    void duplicateVersionNumberIsRejectedDistinctFromDuplicateContent() {
        service.register(DATASET, null, null, def(f("id", FieldType.LONG, false)));

        // 再次提交 version=1（内容不同）：必须是"版本号重复"，而不是内容重复
        SchemaDefinition other = def(
                f("id", FieldType.LONG, false), f("name", FieldType.STRING, true));
        DuplicateVersionException ex = assertThrows(DuplicateVersionException.class,
                () -> service.register(DATASET, 1, 1, other));
        expectFailure("重复版本号注册(v1)", other, ex);
        assertEquals("DUPLICATE_VERSION", ex.getErrorCode());

        // 原版本未被覆盖
        assertEquals(1, service.listVersions(DATASET).size());
    }

    @Test
    void identicalContentIsRejectedWithDistinctErrorCode() {
        SchemaDefinition v1 = def(f("id", FieldType.LONG, false));
        service.register(DATASET, null, null, v1);

        // 自动分配 v2，但内容与 v1 完全相同
        DuplicateSchemaException ex = assertThrows(DuplicateSchemaException.class,
                () -> service.register(DATASET, null, null,
                        def(f("id", FieldType.LONG, false))));
        expectFailure("相同内容注册(自动v2)", v1, ex);
        assertEquals("DUPLICATE_SCHEMA", ex.getErrorCode());
        assertEquals(1, service.listVersions(DATASET).size(), "不得产生新版本");
    }

    @Test
    void nonExistentParentIsRejected() {
        service.register(DATASET, null, null, def(f("id", FieldType.LONG, false)));

        SchemaDefinition v2 = def(
                f("id", FieldType.LONG, false), f("remark", FieldType.STRING, true));
        ParentVersionNotFoundException ex = assertThrows(ParentVersionNotFoundException.class,
                () -> service.register(DATASET, 2, 99, v2));
        expectFailure("引用不存在父版本(parent=v99)", v2, ex);
        assertEquals("PARENT_VERSION_NOT_FOUND", ex.getErrorCode());
    }

    @Test
    void forkedChainIsRejected() {
        service.register(DATASET, null, null, def(f("id", FieldType.LONG, false)));
        service.register(DATASET, null, null,
                def(f("id", FieldType.LONG, false), f("a", FieldType.STRING, true)));

        // 最新是 v2，却尝试以 v1 为父注册 v3 -> 分叉/断链
        SchemaDefinition v3 = def(
                f("id", FieldType.LONG, false),
                f("b", FieldType.STRING, true));
        InvalidEvolutionChainException ex = assertThrows(InvalidEvolutionChainException.class,
                () -> service.register(DATASET, 3, 1, v3));
        expectFailure("非最新父版本导致断链(parent=v1, latest=v2)", v3, ex);
        assertEquals("INVALID_EVOLUTION_CHAIN", ex.getErrorCode());
        assertEquals(2, service.listVersions(DATASET).size(), "失败不得产生部分写入");
    }

    @Test
    void incompatibleChangeIsRejectedViaService() {
        service.register(DATASET, null, null,
                def(f("id", FieldType.LONG, false), f("age", FieldType.INTEGER, true)));

        // 删 age + 改 id 类型：注册必须被拒绝
        SchemaDefinition bad = def(f("id", FieldType.STRING, false));
        IncompatibleSchemaException ex = assertThrows(IncompatibleSchemaException.class,
                () -> service.register(DATASET, null, null, bad));
        expectFailure("破坏性变更注册(删字段+改类型)", bad, ex);
        assertEquals("INCOMPATIBLE_SCHEMA", ex.getErrorCode());
        assertTrue(ex.getConflicts().size() >= 2, "必须指出所有冲突位置");
        assertEquals(1, service.listVersions(DATASET).size(), "拒绝注册不得产生部分写入");
    }

    @Test
    void compatibleEvolutionSucceedsAndHashIsStable() {
        SchemaDefinition v1 = def(f("id", FieldType.LONG, false));
        SchemaVersion first = service.register(DATASET, null, null, v1);

        SchemaDefinition v2 = def(
                f("id", FieldType.LONG, false),
                f("email", FieldType.STRING, true));
        logInput("兼容演进注册(新增可空字段)", v2);
        SchemaVersion second = service.register(DATASET, null, null, v2);

        assertEquals(2, second.version());
        assertEquals(1, second.parentVersion());
        assertNotEquals(first.contentHash(), second.contentHash());
        assertEquals(2, service.getLatest(DATASET).version());
        assertEquals(first.contentHash(), service.getVersion(DATASET, 1).contentHash(),
                "历史版本按版本号可回查且内容不变");
        log.info("【最终结论】ACCEPTED version=v2 parent=v1, head=v2, hash={}",
                second.contentHash());
    }

    @Test
    void rollbackMovesHeadAndRejectsInvalidTargets() {
        service.register(DATASET, null, null, def(f("id", FieldType.LONG, false)));
        service.register(DATASET, null, null, def(
                f("id", FieldType.LONG, false), f("name", FieldType.STRING, true)));
        assertEquals(2, service.getLatest(DATASET).version());

        SchemaVersion rolled = service.rollback(DATASET, 1);
        assertEquals(1, service.getLatest(DATASET).version());
        log.info("【最终结论】ROLLBACK OK head=v2 -> v{}", rolled.version());

        // 历史版本仍在，新版本号继续递增
        SchemaVersion v3 = service.register(DATASET, null, null, def(
                f("id", FieldType.LONG, false),
                f("name", FieldType.STRING, true),
                f("ts", FieldType.TIMESTAMP, true)));
        assertEquals(3, v3.version());

        // 回滚到不存在版本
        VersionNotFoundException nf = assertThrows(VersionNotFoundException.class,
                () -> service.rollback(DATASET, 42));
        expectFailure("回滚到不存在版本(v42)", null, nf);

        // v3 注册后 head 已自动前推到 v3，重复回滚应拒绝
        InvalidRollbackException ir = assertThrows(InvalidRollbackException.class,
                () -> service.rollback(DATASET, 3));
        expectFailure("回滚到当前 head(v3)", null, ir);
        assertEquals("INVALID_ROLLBACK", ir.getErrorCode());
    }
}
