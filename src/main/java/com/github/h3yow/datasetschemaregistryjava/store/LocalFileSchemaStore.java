package com.github.h3yow.datasetschemaregistryjava.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.h3yow.datasetschemaregistryjava.exception.DuplicateVersionException;
import com.github.h3yow.datasetschemaregistryjava.model.SchemaDefinition;
import com.github.h3yow.datasetschemaregistryjava.model.SchemaVersion;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 基于本地文件系统的存储实现。
 *
 * <p>目录结构：
 * <pre>
 *   ${registry.data-dir}/
 *     &lt;dataset&gt;/
 *       versions/
 *         v-000001.json   # 每个版本一个不可变 JSON 文件，先写临时文件再原子 rename
 *         v-000002.json
 *       head.txt          # 当前生效（head）版本号，原子替换
 * </pre>
 *
 * <p>并发控制：每个数据集一把 {@link ReentrantLock}（不同数据集互不阻塞）。
 * 注册的"检查 + 写版本文件 + 更新 head"在同一把锁内完成，因此同一数据集并发提交时
 * 不会出现版本号冲突或半写入状态；跨进程重启后以磁盘上的不可变版本文件为准恢复。
 */
@Repository
public class LocalFileSchemaStore implements SchemaStore {

    private static final Logger log = LoggerFactory.getLogger(LocalFileSchemaStore.class);
    private static final Pattern VERSION_FILE = Pattern.compile("v-(\\d+)\\.json");

    private final Path dataDir;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public LocalFileSchemaStore(
            @Value("${registry.data-dir:./data/schema-registry}") String dataDir) {
        this.dataDir = Path.of(dataDir).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(dataDir);
            log.info("schema registry 本地存储目录: {}", dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException("无法初始化存储目录: " + dataDir, e);
        }
    }

    private ReentrantLock lockFor(String dataset) {
        return locks.computeIfAbsent(dataset, k -> new ReentrantLock());
    }

    private Path datasetDir(String dataset) {
        return dataDir.resolve(dataset);
    }

    private Path versionsDir(String dataset) {
        return datasetDir(dataset).resolve("versions");
    }

    private Path versionFile(String dataset, int version) {
        return versionsDir(dataset).resolve(String.format("v-%06d.json", version));
    }

    private Path headFile(String dataset) {
        return datasetDir(dataset).resolve("head.txt");
    }

    @Override
    public SchemaVersion appendVersion(String dataset, int version, SchemaDefinition schema,
                                       Integer parentVersion, String contentHash) {
        ReentrantLock lock = lockFor(dataset);
        lock.lock();
        try {
            Path target = versionFile(dataset, version);
            if (Files.exists(target)) {
                // 存储层兜底：版本文件已存在，绝不静默覆盖
                throw new DuplicateVersionException(dataset, version);
            }
            Files.createDirectories(versionsDir(dataset));

            SchemaVersion record = new SchemaVersion(dataset, version, schema, parentVersion,
                    contentHash, Instant.now());
            writeJsonAtomically(target, record);

            // head 指针：版本号严格大于当前 head 时前推，回滚场景追加新版本不会倒退
            int currentHead = readHead(dataset).orElse(0);
            if (version > currentHead) {
                writeTextAtomically(headFile(dataset), Integer.toString(version));
            }
            log.debug("已持久化 dataset={}, version={}, parent={}, hash={}",
                    dataset, version, parentVersion, contentHash);
            return record;
        } catch (IOException e) {
            throw new UncheckedIOException("写入版本失败: dataset=" + dataset
                    + ", version=" + version, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<SchemaVersion> listVersions(String dataset) {
        Path dir = versionsDir(dataset);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> VERSION_FILE.matcher(p.getFileName().toString()).matches())
                    .forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException("读取版本目录失败: " + dir, e);
        }
        List<SchemaVersion> result = new ArrayList<>();
        for (Path file : files) {
            result.add(readJson(file));
        }
        result.sort(Comparator.comparingInt(SchemaVersion::version));
        return List.copyOf(result);
    }

    @Override
    public Optional<SchemaVersion> findVersion(String dataset, int version) {
        Path file = versionFile(dataset, version);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        return Optional.of(readJson(file));
    }

    @Override
    public boolean datasetExists(String dataset) {
        return Files.isDirectory(versionsDir(dataset));
    }

    @Override
    public boolean moveHead(String dataset, int targetVersion) {
        ReentrantLock lock = lockFor(dataset);
        lock.lock();
        try {
            if (!Files.exists(versionFile(dataset, targetVersion))) {
                return false;
            }
            Files.createDirectories(datasetDir(dataset));
            writeTextAtomically(headFile(dataset), Integer.toString(targetVersion));
            log.info("head 指针已移动 dataset={}, headVersion={}", dataset, targetVersion);
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException("移动 head 失败: dataset=" + dataset, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<Integer> readHead(String dataset) {
        Path file = headFile(dataset);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(file).trim();
            return Optional.of(Integer.parseInt(content));
        } catch (IOException | NumberFormatException e) {
            throw new IllegalStateException("head 指针文件损坏: " + file, e);
        }
    }

    private SchemaVersion readJson(Path file) {
        try {
            return mapper.readValue(Files.readAllBytes(file), SchemaVersion.class);
        } catch (IOException e) {
            throw new UncheckedIOException("读取版本文件失败: " + file, e);
        }
    }

    /** 先写同目录临时文件再原子 rename，保证不会出现只写入一半的版本文件。 */
    private void writeJsonAtomically(Path target, Object value) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp-"
                + java.util.UUID.randomUUID());
        mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), value);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private void writeTextAtomically(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp-"
                + java.util.UUID.randomUUID());
        Files.writeString(tmp, content);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 仅供测试/运维观察用：解析文件名中的版本号。 */
    static int parseVersionFileName(String name) {
        Matcher m = VERSION_FILE.matcher(name);
        if (!m.matches()) {
            throw new IllegalArgumentException("非法版本文件名: " + name);
        }
        return Integer.parseInt(m.group(1));
    }
}
