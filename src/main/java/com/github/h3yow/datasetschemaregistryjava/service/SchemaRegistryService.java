package com.github.h3yow.datasetschemaregistryjava.service;

import com.github.h3yow.datasetschemaregistryjava.compatibility.CompatibilityChecker;
import com.github.h3yow.datasetschemaregistryjava.exception.RegistryException;
import com.github.h3yow.datasetschemaregistryjava.model.CompatibilityConflict;
import com.github.h3yow.datasetschemaregistryjava.model.CompatibilityResult;
import com.github.h3yow.datasetschemaregistryjava.model.SchemaField;
import com.github.h3yow.datasetschemaregistryjava.model.SchemaVersion;
import com.github.h3yow.datasetschemaregistryjava.store.SchemaStore;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 注册、查询与回滚的业务入口。
 *
 * 所有校验与写入都在 SchemaStore 的数据集级锁内完成，
 * 校验失败时抛异常且不落盘，保证不会部分生效。
 */
@Service
public class SchemaRegistryService {

    private final SchemaStore store;
    private final CompatibilityChecker checker;

    public SchemaRegistryService(SchemaStore store, CompatibilityChecker checker) {
        this.store = store;
        this.checker = checker;
    }

    /**
     * 注册新版本。parentVersion 为 null 时默认以当前最新版为父版本。
     *
     * 拒绝场景（错误码可互相区分）：
     * - 版本号已存在：DUPLICATE_VERSION
     * - 内容与已有版本完全相同：IDENTICAL_CONTENT
     * - 指定的父版本不存在：UNKNOWN_PARENT_VERSION
     * - 父版本不是当前最新版（链路断裂）：BROKEN_CHAIN
     * - 相对父版本不兼容：INCOMPATIBLE_SCHEMA
     */
    public SchemaVersion register(String dataset, int version, Integer parentVersion, List<SchemaField> fields) {
        validateFields(fields);
        return store.mutate(dataset, versions -> {
            // 1. 版本号重复
            boolean versionExists = versions.stream().anyMatch(v -> v.version() == version);
            if (versionExists) {
                throw new RegistryException(RegistryException.Code.DUPLICATE_VERSION,
                        "数据集 '" + dataset + "' 已存在版本 " + version + "，禁止覆盖");
            }

            // 2. 内容与已有版本完全相同
            String fingerprint = fingerprint(fields);
            for (SchemaVersion v : versions) {
                if (v.fingerprint().equals(fingerprint)) {
                    throw new RegistryException(RegistryException.Code.IDENTICAL_CONTENT,
                            "提交的内容与数据集 '" + dataset + "' 的版本 " + v.version()
                                    + " 完全相同（fingerprint=" + fingerprint + "），请使用回滚接口");
                }
            }

            // 3. 父版本解析与链路校验
            SchemaVersion parent;
            if (versions.isEmpty()) {
                if (parentVersion != null) {
                    throw new RegistryException(RegistryException.Code.UNKNOWN_PARENT_VERSION,
                            "数据集 '" + dataset + "' 尚无任何版本，父版本 " + parentVersion + " 不存在");
                }
                parent = null;
            } else {
                SchemaVersion latest = latestOf(versions);
                int parentVer = parentVersion != null ? parentVersion : latest.version();
                parent = versions.stream()
                        .filter(v -> v.version() == parentVer)
                        .findFirst()
                        .orElseThrow(() -> new RegistryException(RegistryException.Code.UNKNOWN_PARENT_VERSION,
                                "数据集 '" + dataset + "' 不存在父版本 " + parentVer));
                if (parent.version() != latest.version()) {
                    throw new RegistryException(RegistryException.Code.BROKEN_CHAIN,
                            "父版本 " + parent.version() + " 不是数据集 '" + dataset
                                    + "' 的当前最新版 " + latest.version() + "，演进链断裂");
                }
            }

            // 4. 兼容性判定
            if (parent != null) {
                CompatibilityResult result = checker.check(parent.fields(), fields);
                if (!result.compatible()) {
                    String detail = result.conflicts().stream()
                            .map(c -> "[" + c.kind() + "] " + c.path() + ": " + c.message())
                            .collect(Collectors.joining("; "));
                    throw new RegistryException(RegistryException.Code.INCOMPATIBLE_SCHEMA,
                            "版本 " + version + " 相对父版本 " + parent.version() + " 不兼容: " + detail);
                }
            }

            // 5. 落盘
            SchemaVersion created = new SchemaVersion(dataset, version,
                    parent != null ? parent.version() : null,
                    List.copyOf(fields), fingerprint, null, Instant.now());
            List<SchemaVersion> next = new ArrayList<>(versions);
            next.add(created);
            return SchemaStore.Mutation.persist(next, created);
        });
    }

    /**
     * 回滚：以 targetVersion 的内容产生一个新版本（parent 为当前最新版）。
     * 回滚是显式操作，不受 IDENTICAL_CONTENT 限制；但目标版本不存在、
     * 新版本号重复、目标就是最新版等无效请求会被明确拒绝且不落盘。
     */
    public SchemaVersion rollback(String dataset, int targetVersion, int newVersion) {
        return store.mutate(dataset, versions -> {
            if (versions.isEmpty()) {
                throw new RegistryException(RegistryException.Code.INVALID_ROLLBACK,
                        "数据集 '" + dataset + "' 尚无任何版本，无法回滚");
            }
            SchemaVersion target = versions.stream()
                    .filter(v -> v.version() == targetVersion)
                    .findFirst()
                    .orElseThrow(() -> new RegistryException(RegistryException.Code.INVALID_ROLLBACK,
                            "回滚目标版本 " + targetVersion + " 在数据集 '" + dataset + "' 中不存在"));
            SchemaVersion latest = latestOf(versions);
            if (target.version() == latest.version()) {
                throw new RegistryException(RegistryException.Code.INVALID_ROLLBACK,
                        "目标版本 " + targetVersion + " 已是最新版，回滚无意义");
            }
            boolean versionExists = versions.stream().anyMatch(v -> v.version() == newVersion);
            if (versionExists) {
                throw new RegistryException(RegistryException.Code.DUPLICATE_VERSION,
                        "数据集 '" + dataset + "' 已存在版本 " + newVersion + "，回滚产生的新版本号必须唯一");
            }

            SchemaVersion created = new SchemaVersion(dataset, newVersion, latest.version(),
                    target.fields(), target.fingerprint(), targetVersion, Instant.now());
            List<SchemaVersion> next = new ArrayList<>(versions);
            next.add(created);
            return SchemaStore.Mutation.persist(next, created);
        });
    }

    public SchemaVersion get(String dataset, int version) {
        List<SchemaVersion> versions = store.list(dataset);
        if (versions.isEmpty()) {
            throw new RegistryException(RegistryException.Code.DATASET_NOT_FOUND,
                    "数据集 '" + dataset + "' 不存在");
        }
        return versions.stream()
                .filter(v -> v.version() == version)
                .findFirst()
                .orElseThrow(() -> new RegistryException(RegistryException.Code.VERSION_NOT_FOUND,
                        "数据集 '" + dataset + "' 不存在版本 " + version));
    }

    public SchemaVersion getLatest(String dataset) {
        List<SchemaVersion> versions = store.list(dataset);
        if (versions.isEmpty()) {
            throw new RegistryException(RegistryException.Code.DATASET_NOT_FOUND,
                    "数据集 '" + dataset + "' 不存在");
        }
        return latestOf(versions);
    }

    public List<SchemaVersion> list(String dataset) {
        return store.list(dataset);
    }

    /**
     * 最新版 = 演进链的链头，即不被任何其他版本当作父版本的版本。
     * 由于注册时强制线性链（父版本必须是当时的链头），链头必然唯一。
     * 注意：链头不一定是版本号最大的版本（版本号由客户端指定，只保证唯一）。
     */
    private static SchemaVersion latestOf(List<SchemaVersion> versions) {
        Set<Integer> referencedAsParent = versions.stream()
                .map(SchemaVersion::parentVersion)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return versions.stream()
                .filter(v -> !referencedAsParent.contains(v.version()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("演进链不存在链头，数据已损坏"));
    }

    private static void validateFields(List<SchemaField> fields) {
        if (fields == null || fields.isEmpty()) {
            throw new RegistryException(RegistryException.Code.INVALID_REQUEST,
                    "schema 至少要包含一个字段");
        }
        Set<String> names = new HashSet<>();
        for (SchemaField f : fields) {
            if (!names.add(f.name())) {
                throw new RegistryException(RegistryException.Code.INVALID_REQUEST,
                        "字段名重复: '" + f.name() + "'");
            }
        }
    }

    /**
     * 内容指纹：字段按名称排序后拼接规范化串，取 SHA-256。
     * 与字段顺序无关，保证同一内容判定稳定、可复现。
     */
    static String fingerprint(List<SchemaField> fields) {
        String canonical = fields.stream()
                .sorted(Comparator.comparing(SchemaField::name))
                .map(f -> f.name() + ":" + f.type() + ":" + f.nullable() + ":"
                        + (f.defaultValue() == null ? "<null>" : f.defaultValue()))
                .collect(Collectors.joining("|"));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
