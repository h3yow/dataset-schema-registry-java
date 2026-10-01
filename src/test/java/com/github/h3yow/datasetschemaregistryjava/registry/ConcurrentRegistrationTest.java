package com.github.h3yow.datasetschemaregistryjava.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.h3yow.datasetschemaregistryjava.compatibility.CompatibilityChecker;
import com.github.h3yow.datasetschemaregistryjava.exception.RegistryException;
import com.github.h3yow.datasetschemaregistryjava.model.FieldType;
import com.github.h3yow.datasetschemaregistryjava.model.SchemaField;
import com.github.h3yow.datasetschemaregistryjava.model.SchemaVersion;
import com.github.h3yow.datasetschemaregistryjava.service.SchemaRegistryService;
import com.github.h3yow.datasetschemaregistryjava.store.SchemaStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发注册场景：同一数据集被并发提交时，不能出现版本号冲突、丢版本或只写一半的状态。
 */
class ConcurrentRegistrationTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentRegistrationTest.class);

    private static final String DATASET = "events";

    @TempDir
    Path tempDir;

    private SchemaRegistryService service;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        SchemaStore store = new SchemaStore(tempDir.toString(), mapper);
        service = new SchemaRegistryService(store, new CompatibilityChecker());
    }

    @Test
    void concurrentDistinctVersionsAllPersistedWithConsistentChain() throws InterruptedException {
        int threads = 16;
        // 每个线程提交不同版本号、不同内容（各加一个独有的可空字段，保证兼容且内容不同）
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<SchemaVersion> succeeded = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> failed = new ConcurrentLinkedQueue<>();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 1; i <= threads; i++) {
            final int version = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    // 模拟真实客户端：基于最新版追加自己的独有字段；与他线程冲突时重试 rebase
                    SchemaVersion created = null;
                    for (int attempt = 1; attempt <= 50 && created == null; attempt++) {
                        List<SchemaField> base;
                        try {
                            base = new ArrayList<>(service.getLatest(DATASET).fields());
                        } catch (RegistryException e) {
                            base = new ArrayList<>(List.of(
                                    new SchemaField("id", FieldType.LONG, false, null)));
                        }
                        base.add(new SchemaField("attr_" + version, FieldType.STRING, true, null));
                        log.info("输入: 线程提交 version={}, attempt={}, fields={}", version, attempt, base);
                        try {
                            created = service.register(DATASET, version, null, base);
                        } catch (RegistryException e) {
                            if (e.getCode() != RegistryException.Code.INCOMPATIBLE_SCHEMA) {
                                throw e;
                            }
                            log.info("判定依据: attempt={} 与他线程并发冲突({}), 基于最新版重试", attempt, e.getMessage());
                        }
                    }
                    if (created == null) {
                        throw new IllegalStateException("version " + version + " 重试 50 次仍未成功");
                    }
                    succeeded.add(created);
                } catch (Throwable t) {
                    failed.add(t);
                }
            });
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        log.info("判定依据: 数据集级锁串行化读-改-写，所有兼容提交都应成功且不丢版本");
        log.info("最终结论: 成功 {} 个, 失败 {} 个", succeeded.size(), failed.size());
        failed.forEach(t -> log.warn("意外失败: {}", t.toString()));
        assertTrue(failed.isEmpty(), "并发下不应有失败: " + failed);
        assertEquals(threads, succeeded.size());

        // 不丢版本：1..N 全部存在且唯一
        List<SchemaVersion> all = service.list(DATASET);
        assertEquals(threads, all.size());
        Set<Integer> versions = all.stream().map(SchemaVersion::version).collect(Collectors.toSet());
        assertEquals(threads, versions.size());
        for (int i = 1; i <= threads; i++) {
            assertTrue(versions.contains(i), "缺少版本 " + i);
        }

        // 链路一致：恰好一个根版本，其余每个版本的父版本都存在，且构成一条覆盖全部版本的链
        Map<Integer, SchemaVersion> byVersion = new HashMap<>();
        all.forEach(v -> byVersion.put(v.version(), v));
        List<SchemaVersion> roots = all.stream().filter(v -> v.parentVersion() == null).toList();
        assertEquals(1, roots.size(), "应恰好有一个根版本");
        for (SchemaVersion v : all) {
            if (v.parentVersion() != null) {
                assertTrue(byVersion.containsKey(v.parentVersion()),
                        "版本 " + v.version() + " 的父版本 " + v.parentVersion() + " 缺失");
            }
        }
        // 从最新版沿 parent 指针回走，必须覆盖全部版本（无断裂、无分叉）
        SchemaVersion cursor = service.getLatest(DATASET);
        int walked = 0;
        while (cursor != null) {
            walked++;
            cursor = cursor.parentVersion() == null ? null : byVersion.get(cursor.parentVersion());
        }
        log.info("最终结论: 版本数={}, 链回走覆盖数={}", all.size(), walked);
        assertEquals(all.size(), walked, "演进链必须覆盖全部版本");
    }

    @Test
    void concurrentSameVersionOnlyOneWins() throws InterruptedException {
        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<SchemaVersion> succeeded = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<RegistryException> rejected = new ConcurrentLinkedQueue<>();

        List<SchemaField> fields = List.of(new SchemaField("id", FieldType.LONG, false, null));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    log.info("输入: 线程提交 version=1, fields={}", fields);
                    succeeded.add(service.register(DATASET, 1, null, fields));
                } catch (RegistryException e) {
                    rejected.add(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        log.info("判定依据: 同一版本号并发提交，锁内先到的成功，其余必须因 DUPLICATE_VERSION 被拒");
        log.info("最终结论: 成功 {} 个, 拒绝 {} 个, 拒绝码={}",
                succeeded.size(), rejected.size(),
                rejected.stream().map(e -> e.getCode().name()).distinct().toList());
        assertEquals(1, succeeded.size());
        assertEquals(threads - 1, rejected.size());
        assertTrue(rejected.stream().allMatch(e -> e.getCode() == RegistryException.Code.DUPLICATE_VERSION));
        assertEquals(1, service.list(DATASET).size());
    }
}
