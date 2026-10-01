package com.github.h3yow.datasetschemaregistryjava.tests;

import com.github.h3yow.datasetschemaregistryjava.compat.CompatibilityChecker;
import com.github.h3yow.datasetschemaregistryjava.model.CompatibilityConflict;
import com.github.h3yow.datasetschemaregistryjava.model.CompatibilityResult;
import com.github.h3yow.datasetschemaregistryjava.model.FieldType;
import com.github.h3yow.datasetschemaregistryjava.model.SchemaDefinition;
import com.github.h3yow.datasetschemaregistryjava.model.SchemaField;
import com.github.h3yow.datasetschemaregistryjava.util.ContentHasher;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 兼容性判定单测：每个用例都打印输入、判定依据与最终结论。
 */
class CompatibilityCheckerTest {

    private static final Logger log = LoggerFactory.getLogger(CompatibilityCheckerTest.class);

    private final CompatibilityChecker checker = new CompatibilityChecker();

    private SchemaField field(String name, FieldType type, boolean nullable) {
        return new SchemaField(name, type, nullable, null);
    }

    private void logCase(String title, SchemaDefinition oldSchema,
                         SchemaDefinition newSchema, CompatibilityResult result) {
        log.info("==== 用例: {} ====", title);
        log.info("输入-旧版本: {}", ContentHasher.canonicalJson(oldSchema));
        log.info("输入-新版本: {}", ContentHasher.canonicalJson(newSchema));
        for (CompatibilityConflict c : result.conflicts()) {
            log.info("判定依据-冲突: 位置={}, 类型={}, 说明={}", c.path(), c.reason(), c.detail());
        }
        if (result.compatible()) {
            log.info("判定依据: 无破坏性变更（仅新增可空字段/放宽可空/备注变化等兼容变更）");
        }
        log.info("最终结论: {}", result.compatible() ? "COMPATIBLE" : "INCOMPATIBLE");
    }

    @Test
    void addingNullableFieldIsCompatible() {
        SchemaDefinition oldSchema = new SchemaDefinition(List.of(
                field("id", FieldType.LONG, false)));
        SchemaDefinition newSchema = new SchemaDefinition(List.of(
                field("id", FieldType.LONG, false),
                field("nickname", FieldType.STRING, true)));

        CompatibilityResult result = checker.checkBackwardCompatible(oldSchema, newSchema);
        logCase("新增可空字段-应通过", oldSchema, newSchema, result);

        assertTrue(result.compatible(), "新增 nullable 字段必须兼容，不能被误判");
    }

    @Test
    void addingRequiredFieldIsIncompatible() {
        SchemaDefinition oldSchema = new SchemaDefinition(List.of(
                field("id", FieldType.LONG, false)));
        SchemaDefinition newSchema = new SchemaDefinition(List.of(
                field("id", FieldType.LONG, false),
                field("email", FieldType.STRING, false)));

        CompatibilityResult result = checker.checkBackwardCompatible(oldSchema, newSchema);
        logCase("新增必填字段-应拒绝", oldSchema, newSchema, result);

        assertFalse(result.compatible());
        assertEquals("NEW_FIELD_REQUIRED", result.conflicts().get(0).reason());
    }

    @Test
    void removingFieldIsIncompatible() {
        SchemaDefinition oldSchema = new SchemaDefinition(List.of(
                field("id", FieldType.LONG, false),
                field("name", FieldType.STRING, true)));
        SchemaDefinition newSchema = new SchemaDefinition(List.of(
                field("id", FieldType.LONG, false)));

        CompatibilityResult result = checker.checkBackwardCompatible(oldSchema, newSchema);
        logCase("删除字段-应拒绝", oldSchema, newSchema, result);

        assertFalse(result.compatible());
        assertEquals("FIELD_REMOVED", result.conflicts().get(0).reason());
        assertEquals("fields.name", result.conflicts().get(0).path());
    }

    @Test
    void changingFieldTypeIsIncompatible() {
        SchemaDefinition oldSchema = new SchemaDefinition(List.of(
                field("age", FieldType.INTEGER, true)));
        SchemaDefinition newSchema = new SchemaDefinition(List.of(
                field("age", FieldType.STRING, true)));

        CompatibilityResult result = checker.checkBackwardCompatible(oldSchema, newSchema);
        logCase("字段类型变更-应拒绝", oldSchema, newSchema, result);

        assertFalse(result.compatible());
        assertEquals("TYPE_CHANGED", result.conflicts().get(0).reason());
        assertEquals("fields.age.type", result.conflicts().get(0).path());
    }

    @Test
    void narrowingNullabilityIsIncompatible() {
        SchemaDefinition oldSchema = new SchemaDefinition(List.of(
                field("bio", FieldType.STRING, true)));
        SchemaDefinition newSchema = new SchemaDefinition(List.of(
                field("bio", FieldType.STRING, false)));

        CompatibilityResult result = checker.checkBackwardCompatible(oldSchema, newSchema);
        logCase("可空性收紧-应拒绝", oldSchema, newSchema, result);

        assertFalse(result.compatible());
        assertEquals("NULLABILITY_TIGHTENED", result.conflicts().get(0).reason());
    }

    @Test
    void relaxingNullabilityAndReorderingIsCompatible() {
        SchemaDefinition oldSchema = new SchemaDefinition(List.of(
                field("id", FieldType.LONG, false),
                field("name", FieldType.STRING, false)));
        // 顺序调换 + name 放宽为可空，均属兼容变更
        SchemaDefinition newSchema = new SchemaDefinition(List.of(
                field("name", FieldType.STRING, true),
                field("id", FieldType.LONG, false)));

        CompatibilityResult result = checker.checkBackwardCompatible(oldSchema, newSchema);
        logCase("放宽可空+调整字段顺序-应通过", oldSchema, newSchema, result);

        assertTrue(result.compatible());
    }

    @Test
    void multipleConflictsAreAllReported() {
        SchemaDefinition oldSchema = new SchemaDefinition(List.of(
                field("id", FieldType.LONG, false),
                field("age", FieldType.INTEGER, true)));
        SchemaDefinition newSchema = new SchemaDefinition(List.of(
                field("id", FieldType.STRING, false)));

        CompatibilityResult result = checker.checkBackwardCompatible(oldSchema, newSchema);
        logCase("同时删字段+改类型-冲突应全部报出", oldSchema, newSchema, result);

        assertFalse(result.compatible());
        assertEquals(2, result.conflicts().size(), "两个冲突位置都必须被指出");
    }
}
