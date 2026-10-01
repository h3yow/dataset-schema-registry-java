package com.github.h3yow.datasetschemaregistryjava.compat;

import com.github.h3yow.datasetschemaregistryjava.model.CompatibilityConflict;
import com.github.h3yow.datasetschemaregistryjava.model.CompatibilityResult;
import com.github.h3yow.datasetschemaregistryjava.model.SchemaDefinition;
import com.github.h3yow.datasetschemaregistryjava.model.SchemaField;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 向后兼容性检查（BACKWARD）：新版本必须能安全读取按旧版本写入的数据。
 *
 * <p>判定规则（全部基于字段名匹配，与字段声明顺序无关）：
 * <ol>
 *   <li>删除字段 —— 不兼容，旧数据仍携带该字段且下游可能依赖；</li>
 *   <li>字段类型变更 —— 不兼容，任何类型改动都视为破坏性（含 INTEGER→LONG 等），
 *       避免静默的精度/语义变化；</li>
 *   <li>可空性收紧（旧 nullable=true，新 nullable=false）—— 不兼容，旧数据可能为 null；</li>
 *   <li>可空性放宽（false→true）—— 兼容；</li>
 *   <li>新增字段 —— 仅当声明为 nullable 时兼容，否则旧数据缺少该字段，读取必失败；</li>
 *   <li>字段备注变化 —— 兼容，不影响数据。</li>
 * </ol>
 * 规则无随机性、无环境依赖，相同输入永远得到相同结论。
 */
@Component
public class CompatibilityChecker {

    public CompatibilityResult checkBackwardCompatible(SchemaDefinition oldSchema,
                                                       SchemaDefinition newSchema) {
        Map<String, SchemaField> oldFields = indexByName(oldSchema);
        Map<String, SchemaField> newFields = indexByName(newSchema);
        List<CompatibilityConflict> conflicts = new ArrayList<>();

        // 旧字段在新版本中的命运：删除 / 类型变化 / 可空性收紧
        for (SchemaField oldField : oldSchema.fields()) {
            String path = "fields." + oldField.name();
            SchemaField newField = newFields.get(oldField.name());
            if (newField == null) {
                conflicts.add(new CompatibilityConflict(path, "FIELD_REMOVED",
                        "字段被删除，旧数据仍包含该字段，破坏性变更"));
                continue;
            }
            if (newField.type() != oldField.type()) {
                conflicts.add(new CompatibilityConflict(path + ".type", "TYPE_CHANGED",
                        "字段类型由 " + oldField.type() + " 变更为 " + newField.type()
                                + "，任何类型变更都不兼容"));
            }
            if (oldField.nullable() && !newField.nullable()) {
                conflicts.add(new CompatibilityConflict(path + ".nullable",
                        "NULLABILITY_TIGHTENED",
                        "可空性由 nullable=true 收紧为 false，旧数据可能包含 null"));
            }
        }

        // 新增字段：必须 nullable，否则旧数据缺字段
        for (SchemaField newField : newSchema.fields()) {
            if (!oldFields.containsKey(newField.name()) && !newField.nullable()) {
                conflicts.add(new CompatibilityConflict("fields." + newField.name(),
                        "NEW_FIELD_REQUIRED",
                        "新增字段必须声明为 nullable=true，否则按旧版本写入的数据缺少该字段"));
            }
        }

        return new CompatibilityResult(conflicts.isEmpty(), List.copyOf(conflicts));
    }

    private Map<String, SchemaField> indexByName(SchemaDefinition schema) {
        Map<String, SchemaField> map = new LinkedHashMap<>();
        for (SchemaField field : schema.fields()) {
            map.put(field.name(), field);
        }
        return map;
    }
}
