package ru.kuzmichev.forwardbot.utils;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class Caches {
    public static <M, N> LoadingCache<M, N> create(int maxCacheSize, int expireAfterSeconds, final Function<M, N> loadFunction) {
        return CacheBuilder.newBuilder()
            .maximumSize((long) maxCacheSize)
            .expireAfterWrite((long) expireAfterSeconds, TimeUnit.SECONDS)
            .build(new CacheLoader<M, N>() {
            public N load(M id) {
                return loadFunction.apply(id);
            }
        });
    }
}
