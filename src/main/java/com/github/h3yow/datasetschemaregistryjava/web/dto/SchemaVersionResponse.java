package com.github.h3yow.datasetschemaregistryjava.web.dto;

import java.time.Instant;
import java.util.List;

/** 版本查询响应。 */
public record SchemaVersionResponse(String dataset, int version, Integer parentVersion,
                                    String contentHash, Instant createdAt,
                                    boolean head, List<FieldDto> fields) {
}
