package com.github.h3yow.datasetschemaregistryjava.model;

import java.time.Instant;

/**
 * 已注册的一个 schema 版本。
 *
 * @param dataset       数据集名称
 * @param version       单调递增版本号（首个版本为 1）
 * @param schema        字段定义
 * @param parentVersion 父版本号；首个版本为 null
 * @param contentHash   内容指纹（SHA-256）
 * @param createdAt     注册时间
 */
public record SchemaVersion(String dataset, int version, SchemaDefinition schema,
                            Integer parentVersion, String contentHash, Instant createdAt) {
}
