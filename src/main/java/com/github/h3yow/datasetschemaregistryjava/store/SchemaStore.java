package com.github.h3yow.datasetschemaregistryjava.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.h3yow.datasetschemaregistryjava.model.SchemaVersion;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 本地存储：每个数据集一个 JSON 文件，保存全部 schema 版本。
 *
 * 并发与一致性保证：
 * - 每个数据集一把锁，所有读-改-写都在锁内完成，并发注册不会丢版本；
 * - 写入采用“临时文件 + 原子移动”，不会出现只写一半的文件；
 * - 校验失败时 mutation 返回 persist=false，完全不落盘。
 */
@Repository
public class SchemaStore {

    private final Path baseDir;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public SchemaStore(@Value("${schema.registry.dir:./data/schema-registry}") String baseDir,
                       ObjectMapper objectMapper) {
        this.baseDir = Path.of(baseDir);
        this.objectMapper = objectMapper;
    }

    /**
     * 在数据集级排他锁内读取-修改-写回该数据集的版本列表。
     * mutation 返回 persist=true 时把 versions 原子落盘，否则保持原状。
     */
    public <R> R mutate(String dataset, Function<List<SchemaVersion>, Mutation<R>> mutation) {
        synchronized (lockOf(dataset)) {
            List<SchemaVersion> current = readAll(dataset);
            Mutation<R> m = mutation.apply(List.copyOf(current));
            if (m.persist()) {
                writeAll(dataset, m.versions());
            }
            return m.result();
        }
    }

    /**
     * 读取某数据集的全部版本（按版本号升序）。
     */
    public List<SchemaVersion> list(String dataset) {
        synchronized (lockOf(dataset)) {
            return readAll(dataset);
        }
    }

    /**
     * 按版本号查询。
     */
    public Optional<SchemaVersion> find(String dataset, int version) {
        return list(dataset).stream().filter(v -> v.version() == version).findFirst();
    }

    private Object lockOf(String dataset) {
        return locks.computeIfAbsent(dataset, k -> new Object());
    }

    private Path fileOf(String dataset) {
        // 数据集名做简单转义，避免路径穿越
        String safe = dataset.replaceAll("[^A-Za-z0-9._-]", "_");
        return baseDir.resolve(safe + ".json");
    }

    private List<SchemaVersion> readAll(String dataset) {
        Path file = fileOf(dataset);
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            List<SchemaVersion> versions = objectMapper.readValue(file.toFile(),
                    new TypeReference<List<SchemaVersion>>() {
                    });
            versions.sort(Comparator.comparingInt(SchemaVersion::version));
            return versions;
        } catch (IOException e) {
            throw new UncheckedIOException("读取数据集存储失败: " + dataset, e);
        }
    }

    private void writeAll(String dataset, List<SchemaVersion> versions) {
        try {
            Files.createDirectories(baseDir);
            Path file = fileOf(dataset);
            Path tmp = Files.createTempFile(baseDir, file.getFileName().toString(), ".tmp");
            objectMapper.writeValue(tmp.toFile(), versions);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("写入数据集存储失败: " + dataset, e);
        }
    }

    /**
     * 变更结果：persist=false 表示不落盘（校验失败）。
     */
    public record Mutation<R>(boolean persist, List<SchemaVersion> versions, R result) {

        public static <R> Mutation<R> persist(List<SchemaVersion> versions, R result) {
            return new Mutation<>(true, versions, result);
        }

        public static <R> Mutation<R> reject(R result) {
            return new Mutation<>(false, null, result);
        }
    }
}
