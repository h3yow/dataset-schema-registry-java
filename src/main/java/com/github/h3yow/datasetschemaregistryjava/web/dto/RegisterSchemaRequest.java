package com.github.h3yow.datasetschemaregistryjava.web.dto;

import java.util.List;

/**
 * 注册请求。
 * version 省略时自动分配下一个版本号；parentVersion 省略时按最新版本处理。
 */
public record RegisterSchemaRequest(Integer version, Integer parentVersion,
                                    List<FieldDto> fields) {
}
