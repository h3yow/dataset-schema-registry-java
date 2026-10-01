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
 * 回滚场景：合法回滚产生链上新版本；无效回滚（目标不存在、目标即最新、新版本号重复、
 * 空数据集）必须被明确拒绝且不产生任何部分写入。
 */
class RollbackTest {

    private static final Logger log = LoggerFactory.getLogger(RollbackTest.class);

    private static final String DATASET = "users";

    @TempDir
    Path tempDir;

    private SchemaRegistryService service;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        SchemaStore store = new SchemaStore(tempDir.toString(), mapper);
        service = new SchemaRegistryService(store, new CompatibilityChecker());
    }

    private static List<SchemaField> fieldsV1() {
        return List.of(new SchemaField("id", FieldType.LONG, false, null));
    }

    private static List<SchemaField> fieldsV2() {
        return List.of(
                new SchemaField("id", FieldType.LONG, false, null),
                new SchemaField("email", FieldType.STRING, true, null));
    }

    @Test
    void validRollbackCreatesNewVersionOnChain() {
        service.register(DATASET, 1, null, fieldsV1());
        service.register(DATASET, 2, null, fieldsV2());
        log.info("输入: 已注册 version=1,2, 回滚到 targetVersion=1, 产生 newVersion=3");

        SchemaVersion rolled = service.rollback(DATASET, 1, 3);

        log.info("判定依据: 回滚是显式操作，以目标版本内容在链尾追加新版本");
        log.info("最终结论: version={}, parent={}, rollbackOf={}, fields={}",
                rolled.version(), rolled.parentVersion(), rolled.rollbackOf(), rolled.fields());
        assertEquals(3, rolled.version());
        assertEquals(2, rolled.parentVersion());
        assertEquals(1, rolled.rollbackOf());
        assertEquals(fieldsV1(), rolled.fields());
        assertEquals(3, service.getLatest(DATASET).version());
        assertEquals(3, service.list(DATASET).size());
    }

    @Test
    void rollbackToNonExistentVersionIsRejected() {
        service.register(DATASET, 1, null, fieldsV1());
        service.register(DATASET, 2, null, fieldsV2());
        log.info("输入: 回滚到不存在的 targetVersion=99");

        RegistryException ex = assertThrows(RegistryException.class,
                () -> service.rollback(DATASET, 99, 3));

        log.info("判定依据: 目标版本不存在; 最终结论: code={}, message={}", ex.getCode(), ex.getMessage());
        assertEquals(RegistryException.Code.INVALID_ROLLBACK, ex.getCode());
        assertEquals(2, service.list(DATASET).size(), "无效回滚不得产生任何写入");
    }

    @Test
    void rollbackToLatestIsRejected() {
        service.register(DATASET, 1, null, fieldsV1());
        service.register(DATASET, 2, null, fieldsV2());
        log.info("输入: 回滚到当前最新版 targetVersion=2");

        RegistryException ex = assertThrows(RegistryException.class,
                () -> service.rollback(DATASET, 2, 3));

        log.info("判定依据: 目标已是最新版，回滚无意义; 最终结论: code={}, message={}", ex.getCode(), ex.getMessage());
        assertEquals(RegistryException.Code.INVALID_ROLLBACK, ex.getCode());
        assertEquals(2, service.list(DATASET).size());
    }

    @Test
    void rollbackWithDuplicateNewVersionIsRejected() {
        service.register(DATASET, 1, null, fieldsV1());
        service.register(DATASET, 2, null, fieldsV2());
        log.info("输入: 回滚产生的新版本号 newVersion=2 已存在");

        RegistryException ex = assertThrows(RegistryException.class,
                () -> service.rollback(DATASET, 1, 2));

        log.info("判定依据: 新版本号重复; 最终结论: code={}, message={}", ex.getCode(), ex.getMessage());
        assertEquals(RegistryException.Code.DUPLICATE_VERSION, ex.getCode());
        assertEquals(2, service.list(DATASET).size());
    }

    @Test
    void rollbackOnEmptyDatasetIsRejected() {
        log.info("输入: 空数据集上发起回滚");

        RegistryException ex = assertThrows(RegistryException.class,
                () -> service.rollback(DATASET, 1, 2));

        log.info("判定依据: 数据集无任何版本; 最终结论: code={}, message={}", ex.getCode(), ex.getMessage());
        assertEquals(RegistryException.Code.INVALID_ROLLBACK, ex.getCode());
        assertTrue(service.list(DATASET).isEmpty());
    }
}
