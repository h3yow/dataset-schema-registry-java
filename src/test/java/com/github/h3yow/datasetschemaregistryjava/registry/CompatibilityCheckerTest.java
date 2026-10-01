package com.github.h3yow.datasetschemaregistryjava.registry;

import com.github.h3yow.datasetschemaregistryjava.compatibility.CompatibilityChecker;
import com.github.h3yow.datasetschemaregistryjava.model.CompatibilityResult;
import com.github.h3yow.datasetschemaregistryjava.model.FieldType;
import com.github.h3yow.datasetschemaregistryjava.model.SchemaField;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 兼容性判定单测：覆盖删字段、改类型、可空性收窄、新增必填字段与兼容演进。
 */
class CompatibilityCheckerTest {

    private static final Logger log = LoggerFactory.getLogger(CompatibilityCheckerTest.class);

    private final CompatibilityChecker checker = new CompatibilityChecker();

    private static SchemaField field(String name, FieldType type, boolean nullable, String defaultValue) {
        return new SchemaField(name, type, nullable, defaultValue);
    }

    private void logConclusion(String basis, CompatibilityResult result) {
        log.info("判定依据: {}", basis);
        log.info("最终结论: compatible={}, conflicts={}", result.compatible(), result.conflicts());
    }

    @Test
    void removingFieldIsRejected() {
        List<SchemaField> parent = List.of(
                field("id", FieldType.LONG, false, null),
                field("name", FieldType.STRING, true, null));
        List<SchemaField> next = List.of(
                field("id", FieldType.LONG, false, null));
        log.info("输入: parent={}, next={}", parent, next);

        CompatibilityResult result = checker.check(parent, next);

        logConclusion("删除字段属于破坏性变更，必须报 FIELD_REMOVED", result);
        assertFalse(result.compatible());
        assertEquals(1, result.conflicts().size());
        assertEquals("FIELD_REMOVED", result.conflicts().get(0).kind());
        assertEquals("name", result.conflicts().get(0).path());
    }

    @Test
    void incompatibleTypeChangeIsRejected() {
        List<SchemaField> parent = List.of(field("score", FieldType.STRING, true, null));
        List<SchemaField> next = List.of(field("score", FieldType.INT, true, null));
        log.info("输入: parent={}, next={}", parent, next);

        CompatibilityResult result = checker.check(parent, next);

        logConclusion("STRING→INT 不在加宽提升白名单内，必须报 TYPE_CHANGED", result);
        assertFalse(result.compatible());
        assertEquals("TYPE_CHANGED", result.conflicts().get(0).kind());
        assertEquals("score", result.conflicts().get(0).path());
    }

    @Test
    void wideningTypeChangeIsAccepted() {
        List<SchemaField> parent = List.of(field("count", FieldType.INT, false, null));
        List<SchemaField> next = List.of(field("count", FieldType.LONG, false, null));
        log.info("输入: parent={}, next={}", parent, next);

        CompatibilityResult result = checker.check(parent, next);

        logConclusion("INT→LONG 是允许的加宽提升，应判兼容", result);
        assertTrue(result.compatible());
        assertTrue(result.conflicts().isEmpty());
    }

    @Test
    void nullabilityNarrowingIsRejected() {
        List<SchemaField> parent = List.of(field("email", FieldType.STRING, true, null));
        List<SchemaField> next = List.of(field("email", FieldType.STRING, false, null));
        log.info("输入: parent={}, next={}", parent, next);

        CompatibilityResult result = checker.check(parent, next);

        logConclusion("可空→非空是收窄，必须报 NULLABILITY_NARROWED", result);
        assertFalse(result.compatible());
        assertEquals("NULLABILITY_NARROWED", result.conflicts().get(0).kind());
    }

    @Test
    void addingRequiredFieldWithoutDefaultIsRejected() {
        List<SchemaField> parent = List.of(field("id", FieldType.LONG, false, null));
        List<SchemaField> next = List.of(
                field("id", FieldType.LONG, false, null),
                field("age", FieldType.INT, false, null));
        log.info("输入: parent={}, next={}", parent, next);

        CompatibilityResult result = checker.check(parent, next);

        logConclusion("新增非空且无默认值字段，必须报 REQUIRED_FIELD_ADDED", result);
        assertFalse(result.compatible());
        assertEquals("REQUIRED_FIELD_ADDED", result.conflicts().get(0).kind());
        assertEquals("age", result.conflicts().get(0).path());
    }

    @Test
    void addingNullableOrDefaultedFieldIsAccepted() {
        List<SchemaField> parent = List.of(field("id", FieldType.LONG, false, null));
        List<SchemaField> next = List.of(
                field("id", FieldType.LONG, false, null),
                field("nickname", FieldType.STRING, true, null),
                field("status", FieldType.STRING, false, "ACTIVE"));
        log.info("输入: parent={}, next={}", parent, next);

        CompatibilityResult result = checker.check(parent, next);

        logConclusion("新增可空字段或带默认值的必填字段是兼容演进，应通过", result);
        assertTrue(result.compatible());
    }

    @Test
    void verdictIsDeterministicAndReproducible() {
        List<SchemaField> parent = List.of(
                field("id", FieldType.LONG, false, null),
                field("name", FieldType.STRING, true, null));
        List<SchemaField> next = List.of(field("id", FieldType.STRING, false, null));
        log.info("输入: parent={}, next={}", parent, next);

        CompatibilityResult first = checker.check(parent, next);
        CompatibilityResult second = checker.check(parent, next);

        logConclusion("同样输入重复判定，结论与冲突列表必须完全一致", first);
        assertEquals(first, second);
    }
}
