package com.github.h3yow.datasetschemaregistryjava.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.h3yow.datasetschemaregistryjava.model.SchemaDefinition;
import com.github.h3yow.datasetschemaregistryjava.model.SchemaField;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 计算 schema 内容的稳定指纹。
 *
 * <p>先把 {@link SchemaDefinition} 规范化成确定性 JSON（字段保持声明顺序，
 * 不依赖 JVM 反射顺序），再做 SHA-256。这样：
 * <ul>
 *   <li>字段声明顺序不同 / 类型不同 / 可空性不同 / 备注不同，哈希都不同；</li>
 *   <li>同样的定义在任何机器、任何时间得到的哈希都一致，结论可复现。</li>
 * </ul>
 */
public final class ContentHasher {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ContentHasher() {
    }

    /** 计算 schema 定义的 SHA-256 十六进制指纹。 */
    public static String sha256(SchemaDefinition schema) {
        return sha256(canonicalJson(schema));
    }

    /** 计算任意字符串的 SHA-256 十六进制指纹。 */
    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }

    /** 生成规范化 JSON 字符串，供日志与哈希共同使用，保证可解释。 */
    public static String canonicalJson(SchemaDefinition schema) {
        List<Object> fields = new ArrayList<>();
        for (SchemaField field : schema.fields()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", field.name());
            entry.put("type", field.type().name());
            entry.put("nullable", field.nullable());
            entry.put("comment", field.comment());
            fields.add(entry);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("fields", fields);
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("schema 规范化失败", e);
        }
    }
}
