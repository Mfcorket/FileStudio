package com.filestudio.core;

import java.lang.ref.SoftReference;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 文档解析结果的软引用 LRU 缓存。
 *
 * <p>用于避免重复解析同一文件，特别是大文件或频繁切换标签页时。
 * 使用 {@link SoftReference} 让 GC 在内存紧张时自动回收，配合 LRU 淘汰策略。
 *
 * <p>缓存键为文件路径 + 最后修改时间，确保文件变更时自动失效。
 */
public final class DocumentCache {

    private static final int DEFAULT_MAX_SIZE = 64;

    private final int maxSize;
    private final Map<String, SoftReference<CachedDocument>> cache = new ConcurrentHashMap<>();
    private final ReentrantLock lruLock = new ReentrantLock();
    private final LinkedHashMap<String, Long> accessOrder = new LinkedHashMap<>();

    /**
     * 创建默认大小 (64) 的缓存。
     */
    public DocumentCache() {
        this(DEFAULT_MAX_SIZE);
    }

    /**
     * 创建指定最大条目数的缓存。
     *
     * @param maxSize 最大缓存条目数，必须大于 0
     */
    public DocumentCache(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be > 0");
        }
        this.maxSize = maxSize;
    }

    /**
     * 获取缓存的文档。
     *
     * @param path 文件路径
     * @param lastModified 文件最后修改时间（毫秒）
     * @return 缓存的文档，未命中或已失效时返回 null
     */
    public Document get(Path path, long lastModified) {
        String key = makeKey(path, lastModified);
        SoftReference<CachedDocument> ref = cache.get(key);
        if (ref == null) {
            return null;
        }
        CachedDocument doc = ref.get();
        if (doc == null) {
            // 被 GC 回收了
            cache.remove(key);
            return null;
        }
        // 更新访问顺序
        touch(key);
        return doc.document();
    }

    /**
     * 放入缓存。
     *
     * @param path 文件路径
     * @param lastModified 文件最后修改时间
     * @param document 解析得到的文档
     */
    public void put(Path path, long lastModified, Document document) {
        String key = makeKey(path, lastModified);
        CachedDocument cached = new CachedDocument(document, Instant.now());
        cache.put(key, new SoftReference<>(cached));
        touch(key);
        evictIfNeeded();
    }

    /** 清空缓存。 */
    public void clear() {
        lruLock.lock();
        try {
            cache.clear();
            accessOrder.clear();
        } finally {
            lruLock.unlock();
        }
    }

    /** 当前缓存条目数（含已被 GC 回收但未清理的软引用）。
     *
     * @return 当前缓存大小
     */
    public int size() {
        return cache.size();
    }

    /** 配置的最大条目数。
     *
     * @return 最大缓存容量
     */
    public int maxSize() {
        return maxSize;
    }

    private String makeKey(Path path, long lastModified) {
        return path.toAbsolutePath().toString() + "#" + lastModified;
    }

    private void touch(String key) {
        lruLock.lock();
        try {
            accessOrder.put(key, System.nanoTime());
        } finally {
            lruLock.unlock();
        }
    }

    private void evictIfNeeded() {
        lruLock.lock();
        try {
            while (accessOrder.size() > maxSize) {
                String oldest = accessOrder.entrySet().iterator().next().getKey();
                accessOrder.remove(oldest);
                cache.remove(oldest);
            }
        } finally {
            lruLock.unlock();
        }
    }

    /** 缓存条目：文档 + 存入时间。 */
    private record CachedDocument(Document document, Instant cachedAt) {}
}