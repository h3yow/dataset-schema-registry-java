package com.github.h3yow.datasetschemaregistryjava.tests;

import com.github.h3yow.datasetschemaregistryjava.compat.CompatibilityChecker;
import com.github.h3yow.datasetschemaregistryjava.exception.RegistryException;
import com.github.h3yow.datasetschemaregistryjava.model.FieldType;
import com.github.h3yow.datasetschemaregistryjava.model.SchemaDefinition;
import com.github.h3yow.datasetschemaregistryjava.model.SchemaField;
import com.github.h3yow.datasetschemaregistryjava.model.SchemaVersion;
import com.github.h3yow.datasetschemaregistryjava.service.SchemaRegistryService;
import com.github.h3yow.datasetschemaregistryjava.store.LocalFileSchemaStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发注册测试：同一数据集被多线程同时提交。
 * 打印输入、判定依据与最终结论，验证：无版本号冲突 / 无丢版本 / 无半写入 / 结论确定。
 */
class ConcurrentRegistrationTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentRegistrationTest.class);
    private static final String DATASET = "events";

    @TempDir
    Path tempDir;

    private SchemaDefinition schemaFor(int i) {
        // 每个线程的新字段名称不同 -> 内容互异且全部向后兼容
        return new SchemaDefinition(List.of(
                new SchemaField("id", FieldType.LONG, false, null),
                new SchemaField("tag_" + i, FieldType.STRING, true, null)));
    }

    @Test
    void concurrentRegistrationsAreSerializedWithoutGapsOrDuplicates() throws Exception {
        LocalFileSchemaStore store = new LocalFileSchemaStore(tempDir.toString());
        store.init();
        SchemaRegistryService service =
                new SchemaRegistryService(store, new CompatibilityChecker());
        // 先串行建立 v1，所有并发线程都隐式以"当前最新"为父
        service.register(DATASET, null, null,
                new SchemaDefinition(List.of(new SchemaField("id", FieldType.LONG,
                        false, null))));

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        AtomicInteger success = new AtomicInteger();
        List<String> failureCodes = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                SchemaDefinition input = schemaFor(idx);
                try {
                    SchemaVersion saved = service.register(DATASET, null, null, input);
                    success.incrementAndGet();
                    String msg = "ACCEPTED v" + saved.version();
                    log.info("【输入】线程={} schema 新字段=tag_{}；【判定依据】基于最新父版本向后兼容、内容互异；【最终结论】{}",
                            idx, idx, msg);
                    return msg;
                } catch (RegistryException ex) {
                    failureCodes.add(ex.getErrorCode());
                    log.info("【输入】线程={} schema 新字段=tag_{}；【判定依据】{} ({}); 【最终结论】REJECTED {}",
                            idx, idx, ex.getMessage(), ex.getErrorCode(), ex.getErrorCode());
                    return "REJECTED:" + ex.getErrorCode();
                }
            }));
        }

        ready.await();
        start.countDown();
        for (Future<String> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        List<SchemaVersion> versions = service.listVersions(DATASET);
        log.info("【最终结论】成功={}, 失败={}, 失败错误码分布={}",
                success.get(), failureCodes.size(), buildCount(failureCodes));

        // 1) 成功 + 失败 == 总提交数，没有"只写入一半"的悬空状态
        assertEquals(threads, success.get() + failureCodes.size());

        // 2) 成功版本号严格 2..2+success-1，连续无缺口、无重复
        List<Integer> numbers = versions.stream().map(SchemaVersion::version).sorted().toList();
        Set<Integer> unique = new HashSet<>(numbers);
        assertEquals(numbers.size(), unique.size(), "版本号不得重复");
        for (int v = 1; v <= numbers.size(); v++) {
            assertTrue(numbers.contains(v), "版本链不得出现缺口，缺少 v" + v);
        }

        // 3) 每条演进链 parent 指向前一版本（无断链）
        for (int i = 1; i < versions.size(); i++) {
            assertEquals(versions.get(i - 1).version(), versions.get(i).parentVersion(),
                    "v" + versions.get(i).version() + " 的父版本必须是前一版本");
        }

        // 4) 全部成功的版本内容指纹互异
        long distinctHash = versions.stream().map(SchemaVersion::contentHash).distinct().count();
        assertEquals(versions.size(), distinctHash, "已注册版本内容必须互异");

        // 5) head 与最大版本一致
        assertEquals(numbers.get(numbers.size() - 1), service.getLatest(DATASET).version());

        log.info("【最终结论】版本链={}, head=v{}", numbers, service.getLatest(DATASET).version());
    }

    @Test
    void concurrentSameContentSubmissionsOnlyOneSucceeds() throws Exception {
        LocalFileSchemaStore store = new LocalFileSchemaStore(tempDir.toString());
        store.init();
        SchemaRegistryService service =
                new SchemaRegistryService(store, new CompatibilityChecker());
        service.register(DATASET, null, null,
                new SchemaDefinition(List.of(new SchemaField("id", FieldType.LONG,
                        false, null))));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger duplicateContent = new AtomicInteger();
        AtomicInteger duplicateVersion = new AtomicInteger();

        SchemaDefinition same = new SchemaDefinition(List.of(
                new SchemaField("id", FieldType.LONG, false, null),
                new SchemaField("same_extra", FieldType.STRING, true, null)));

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    try {
                        service.register(DATASET, null, null, same);
                        success.incrementAndGet();
                    } catch (RegistryException ex) {
                        switch (ex.getErrorCode()) {
                            case "DUPLICATE_SCHEMA" -> duplicateContent.incrementAndGet();
                            case "DUPLICATE_VERSION" -> duplicateVersion.incrementAndGet();
                            default -> throw new IllegalStateException(
                                    "意外错误码: " + ex.getErrorCode());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        log.info("【输入】8 个线程提交完全相同的 v2 内容；【判定依据】版本号串行分配 + 内容指纹去重；"
                + "【最终结论】成功={}, 内容重复={}, 版本号重复={}",
                success.get(), duplicateContent.get(), duplicateVersion.get());

        assertEquals(1, success.get(), "相同内容只应有一个注册成功");
        assertEquals(threads - 1, duplicateContent.get() + duplicateVersion.get(),
                "其余提交必须被明确拒绝");
        assertEquals(2, service.listVersions(DATASET).size(), "不得有半写入/覆盖");
    }

    private String buildCount(List<String> codes) {
        Set<String> unique = new HashSet<>(codes);
        List<String> parts = new ArrayList<>();
        for (String code : unique) {
            parts.add(code + "=" + codes.stream().filter(code::equals).count());
        }
        return parts.toString();
    }
}
