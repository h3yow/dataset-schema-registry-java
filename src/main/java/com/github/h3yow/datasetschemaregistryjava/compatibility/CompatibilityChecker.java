package com.github.h3yow.datasetschemaregistryjava.compatibility;

import com.github.h3yow.datasetschemaregistryjava.model.CompatibilityConflict;
import com.github.h3yow.datasetschemaregistryjava.model.CompatibilityResult;
import com.github.h3yow.datasetschemaregistryjava.model.FieldType;
import com.github.h3yow.datasetschemaregistryjava.model.SchemaField;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 兼容性判定器：比较新版本字段与父版本字段，给出确定、可复现的结论。
 *
 * 规则（相对父版本）：
 * 1. 删除字段：不兼容（FIELD_REMOVED）。
 * 2. 字段类型变化：仅允许固定的加宽提升（INT→LONG→DOUBLE、INT→DOUBLE、FLOAT→DOUBLE），
 *    其余一律不兼容（TYPE_CHANGED）。
 * 3. 可空性收窄（nullable → 非空）：不兼容（NULLABILITY_NARROWED）；放宽则兼容。
 * 4. 新增字段：可空或带默认值则兼容；非空且无默认值则不兼容（REQUIRED_FIELD_ADDED）。
 *
 * 判定只依赖输入的两个字段列表，不依赖任何外部状态，因此结论确定且可复现。
 */
@Component
public class CompatibilityChecker {

    /** 允许的类型加宽提升：key 可提升为 value 集合中的任一类型。 */
    private static final Map<FieldType, Set<FieldType>> WIDENING = Map.of(
            FieldType.INT, Set.of(FieldType.LONG, FieldType.DOUBLE),
            FieldType.LONG, Set.of(FieldType.DOUBLE),
            FieldType.FLOAT, Set.of(FieldType.DOUBLE));

    /**
     * 判定 newFields 相对 parentFields 是否兼容。
     */
    public CompatibilityResult check(List<SchemaField> parentFields, List<SchemaField> newFields) {
        Map<String, SchemaField> parentByName = new LinkedHashMap<>();
        for (SchemaField f : parentFields) {
            parentByName.put(f.name(), f);
        }
        Map<String, SchemaField> newByName = new LinkedHashMap<>();
        for (SchemaField f : newFields) {
            newByName.put(f.name(), f);
        }

        List<CompatibilityConflict> conflicts = new ArrayList<>();

        // 规则 1：删除字段
        for (String name : parentByName.keySet()) {
            if (!newByName.containsKey(name)) {
                conflicts.add(new CompatibilityConflict(name, "FIELD_REMOVED",
                        "字段 '" + name + "' 在新版本中被删除"));
            }
        }

        // 规则 2、3：共有字段的类型与可空性
        for (Map.Entry<String, SchemaField> e : newByName.entrySet()) {
            SchemaField parent = parentByName.get(e.getKey());
            if (parent == null) {
                continue;
            }
            SchemaField current = e.getValue();
            if (parent.type() != current.type() && !isWidening(parent.type(), current.type())) {
                conflicts.add(new CompatibilityConflict(current.name(), "TYPE_CHANGED",
                        "字段 '" + current.name() + "' 类型由 " + parent.type()
                                + " 变为 " + current.type() + "，不属于允许的加宽提升"));
            }
            if (parent.nullable() && !current.nullable()) {
                conflicts.add(new CompatibilityConflict(current.name(), "NULLABILITY_NARROWED",
                        "字段 '" + current.name() + "' 由可空变为非空"));
            }
        }

        // 规则 4：新增字段
        for (Map.Entry<String, SchemaField> e : newByName.entrySet()) {
            if (parentByName.containsKey(e.getKey())) {
                continue;
            }
            SchemaField added = e.getValue();
            if (!added.nullable() && added.defaultValue() == null) {
                conflicts.add(new CompatibilityConflict(added.name(), "REQUIRED_FIELD_ADDED",
                        "新增字段 '" + added.name() + "' 为非空且无默认值"));
            }
        }

        return conflicts.isEmpty()
                ? CompatibilityResult.ok()
                : CompatibilityResult.incompatible(conflicts);
    }

    private boolean isWidening(FieldType from, FieldType to) {
        return WIDENING.getOrDefault(from, Set.of()).contains(to);
    }
}
