package com.github.h3yow.datasetschemaregistryjava.service;

import com.github.h3yow.datasetschemaregistryjava.compat.CompatibilityChecker;
import com.github.h3yow.datasetschemaregistryjava.exception.DatasetNotFoundException;
import com.github.h3yow.datasetschemaregistryjava.exception.DuplicateSchemaException;
import com.github.h3yow.datasetschemaregistryjava.exception.DuplicateVersionException;
import com.github.h3yow.datasetschemaregistryjava.exception.IncompatibleSchemaException;
import com.github.h3yow.datasetschemaregistryjava.exception.InvalidEvolutionChainException;
import com.github.h3yow.datasetschemaregistryjava.exception.InvalidRollbackException;
import com.github.h3yow.datasetschemaregistryjava.exception.InvalidSchemaException;
import com.github.h3yow.datasetschemaregistryjava.exception.ParentVersionNotFoundException;
import com.github.h3yow.datasetschemaregistryjava.exception.VersionNotFoundException;
import com.github.h3yow.datasetschemaregistryjava.model.CompatibilityResult;
import com.github.h3yow.datasetschemaregistryjava.model.SchemaDefinition;
import com.github.h3yow.datasetschemaregistryjava.model.SchemaField;
import com.github.h3yow.datasetschemaregistryjava.model.SchemaVersion;
import com.github.h3yow.datasetschemaregistryjava.store.SchemaStore;
import com.github.h3yow.datasetschemaregistryjava.util.ContentHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 注册中心核心服务：版本注册 / 查询 / 回滚。
 *
 * <p>演进链语义：版本号从 1 开始严格连续递增，且每个新版本的 parent 必须是
 * 当前最大版本（线性链，不允许分叉）。回滚只移动 head 指针，不删除、不改写任何历史版本。
 *
 * <p>并发语义：每个数据集一把锁，注册/回滚期间的"读取现状 → 校验 → 落盘"全程持锁，
 * 因此并发提交只会被串行化成确定的成功/失败结论，不会出现版本号冲突、丢版本或半写入。
 */
@Service
public class SchemaRegistryService {

    private static final Logger log = LoggerFactory.getLogger(SchemaRegistryService.class);
    private static final Pattern DATASET_NAME = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private final SchemaStore store;
    private final CompatibilityChecker compatibilityChecker;
    private final ConcurrentHashMap<String, ReentrantLock> datasetLocks = new ConcurrentHashMap<>();

    public SchemaRegistryService(SchemaStore store, CompatibilityChecker compatibilityChecker) {
        this.store = store;
        this.compatibilityChecker = compatibilityChecker;
    }

    private ReentrantLock lockFor(String dataset) {
        return datasetLocks.computeIfAbsent(dataset, k -> new ReentrantLock());
    }

    /**
     * 注册一个新版本。
     *
     * @param dataset         数据集名称
     * @param requestedVersion 期望版本号，null 表示自动分配（最大版本 + 1）
     * @param parentVersion   父版本号，null 表示自动取当前最大版本（首个版本无父）
     * @param schema          schema 字段定义
     * @return 已持久化的版本记录
     */
    public SchemaVersion register(String dataset, Integer requestedVersion,
                                  Integer parentVersion, SchemaDefinition schema) {
        validateDatasetName(dataset);
        validateSchema(schema);
        String contentHash = ContentHasher.sha256(schema);
        String canonical = ContentHasher.canonicalJson(schema);

        ReentrantLock lock = lockFor(dataset);
        lock.lock();
        try {
            List<SchemaVersion> versions = store.listVersions(dataset);

            // ---- 首个版本 ----
            if (versions.isEmpty()) {
                if (requestedVersion != null && requestedVersion != 1) {
                    throw new InvalidEvolutionChainException(
                            "数据集首个版本必须是 version=1，收到: " + requestedVersion);
                }
                if (parentVersion != null) {
                    throw new InvalidEvolutionChainException(
                            "数据集首个版本不能指定父版本，收到 parentVersion=" + parentVersion);
                }
                SchemaVersion saved = store.appendVersion(dataset, 1, schema, null, contentHash);
                log.info("注册首个版本: dataset={}, version=1, hash={}, 定义={}",
                        dataset, contentHash, canonical);
                return saved;
            }

            // ---- 后续版本 ----
            SchemaVersion latest = versions.get(versions.size() - 1);

            // 1) 版本号判定
            int targetVersion;
            if (requestedVersion == null) {
                targetVersion = latest.version() + 1;
            } else if (requestedVersion <= latest.version()) {
                throw new DuplicateVersionException(dataset, requestedVersion);
            } else if (requestedVersion != latest.version() + 1) {
                throw new InvalidEvolutionChainException(
                        "版本号必须连续递增，期望 " + (latest.version() + 1)
                                + "，收到 " + requestedVersion);
            } else {
                targetVersion = requestedVersion;
            }

            // 2) 父版本判定（存在性 + 必须是链上最新，杜绝分叉/断链）
            int effectiveParent = parentVersion == null ? latest.version() : parentVersion;
            SchemaVersion parent = store.findVersion(dataset, effectiveParent)
                    .orElseThrow(() -> new ParentVersionNotFoundException(dataset, effectiveParent));
            if (effectiveParent != latest.version()) {
                throw new InvalidEvolutionChainException(
                        "演进链断裂：父版本必须是当前最新版本 v" + latest.version()
                                + "，收到 parentVersion=v" + effectiveParent);
            }

            // 3) 内容去重（与任何历史版本完全相同都拒绝，保证结论可解释）
            for (SchemaVersion existing : versions) {
                if (existing.contentHash().equals(contentHash)) {
                    throw new DuplicateSchemaException(dataset + "#v" + existing.version(),
                            contentHash);
                }
            }

            // 4) 兼容性判定（相对于父版本）
            CompatibilityResult result =
                    compatibilityChecker.checkBackwardCompatible(parent.schema(), schema);
            if (!result.compatible()) {
                log.warn("兼容性判定不通过: dataset={}, 新版本=v{}, 父版本=v{}, 冲突={}, 新定义={}",
                        dataset, targetVersion, effectiveParent, result.conflicts(), canonical);
                throw new IncompatibleSchemaException(result.conflicts());
            }

            SchemaVersion saved = store.appendVersion(dataset, targetVersion, schema,
                    effectiveParent, contentHash);
            log.info("注册成功: dataset={}, version=v{}, parent=v{}, hash={}, 判定依据=向后兼容(新增可空字段/放宽可空/备注变化), 定义={}",
                    dataset, targetVersion, effectiveParent, contentHash, canonical);
            return saved;
        } finally {
            lock.unlock();
        }
    }

    /** 查询指定版本。 */
    public SchemaVersion getVersion(String dataset, int version) {
        validateDatasetName(dataset);
        if (!store.datasetExists(dataset)) {
            throw new DatasetNotFoundException(dataset);
        }
        return store.findVersion(dataset, version)
                .orElseThrow(() -> new VersionNotFoundException(dataset, version));
    }

    /** 查询当前生效（head）版本。 */
    public SchemaVersion getLatest(String dataset) {
        validateDatasetName(dataset);
        if (!store.datasetExists(dataset)) {
            throw new DatasetNotFoundException(dataset);
        }
        int head = store.readHead(dataset)
                .orElseThrow(() -> new IllegalStateException("数据集缺少 head 指针: " + dataset));
        return store.findVersion(dataset, head)
                .orElseThrow(() -> new VersionNotFoundException(dataset, head));
    }

    /** 列出全部历史版本（按版本号升序）。 */
    public List<SchemaVersion> listVersions(String dataset) {
        validateDatasetName(dataset);
        if (!store.datasetExists(dataset)) {
            throw new DatasetNotFoundException(dataset);
        }
        return store.listVersions(dataset);
    }

    /**
     * 回滚：把 head 指针移动到任一已存在的历史版本。不删除新版本、不改写历史，
     * 后续新注册仍按"最大版本 + 1"继续编号。
     */
    public SchemaVersion rollback(String dataset, int targetVersion) {
        validateDatasetName(dataset);
        ReentrantLock lock = lockFor(dataset);
        lock.lock();
        try {
            if (!store.datasetExists(dataset)) {
                throw new DatasetNotFoundException(dataset);
            }
            SchemaVersion target = store.findVersion(dataset, targetVersion)
                    .orElseThrow(() -> new VersionNotFoundException(dataset, targetVersion));
            int currentHead = store.readHead(dataset)
                    .orElseThrow(() -> new InvalidRollbackException("数据集缺少 head 指针: "
                            + dataset));
            if (currentHead == targetVersion) {
                throw new InvalidRollbackException(
                        "当前 head 已经是 v" + targetVersion + "，回滚请求无效");
            }
            if (!store.moveHead(dataset, targetVersion)) {
                throw new VersionNotFoundException(dataset, targetVersion);
            }
            log.info("回滚完成: dataset={}, head: v{} -> v{}",
                    dataset, currentHead, targetVersion);
            return target;
        } finally {
            lock.unlock();
        }
    }

    private void validateDatasetName(String dataset) {
        if (dataset == null || !DATASET_NAME.matcher(dataset).matches()) {
            throw new InvalidSchemaException("非法数据集名称: " + dataset
                    + "（允许字母、数字、点、下划线、连字符，长度 1-128）");
        }
    }

    private void validateSchema(SchemaDefinition schema) {
        if (schema == null || schema.fields() == null || schema.fields().isEmpty()) {
            throw new InvalidSchemaException("schema 必须至少包含一个字段");
        }
        Set<String> names = new HashSet<>();
        for (SchemaField field : schema.fields()) {
            if (field == null || field.name() == null || field.name().isBlank()) {
                throw new InvalidSchemaException("字段名称不能为空");
            }
            if (field.type() == null) {
                throw new InvalidSchemaException("字段 [" + field.name() + "] 缺少类型");
            }
            if (!names.add(field.name())) {
                throw new InvalidSchemaException("字段名称重复: " + field.name());
            }
        }
    }
}
