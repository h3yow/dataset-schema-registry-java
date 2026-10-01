package com.github.h3yow.datasetschemaregistryjava.web.dto;

/** 回滚请求：把数据集 head 指针移动到指定历史版本。 */
public record RollbackRequest(Integer version) {
}
