package com.github.h3yow.datasetschemaregistryjava.model;

import java.time.Instant;
import java.util.List;

/**
 * 一个数据集的一个 schema 版本。
 *
 * @param dataset       数据集名
 * @param version       版本号（数据集内唯一）
 * @param parentVersion 父版本号（首版本为 null）
 * @param fields        字段列表
 * @param fingerprint   内容指纹（规范化后的 SHA-256），用于识别完全相同的重复提交
 * @param rollbackOf    若本版本是回滚产生，记录回滚到的源版本号；否则为 null
 * @param createdAt     创建时间
 */
public record SchemaVersion(
        String dataset,
        int version,
        Integer parentVersion,
        List<SchemaField> fields,
        String fingerprint,
        Integer rollbackOf,
        Instant createdAt) {
}
