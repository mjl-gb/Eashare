package com.eashare.controller;

import com.eashare.dto.Result;
import com.eashare.utils.TwoLevelCacheClient;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * 缓存管理控制器
 */
@RestController
@RequestMapping("/cache")
public class CacheController {

    @Resource
    private TwoLevelCacheClient twoLevelCacheClient;

    /**
     * 获取二级缓存统计信息
     */
    @GetMapping("/stats")
    public Result getCacheStats() {
        String stats = twoLevelCacheClient.getCacheStats();
        return Result.ok(stats);
    }

    /**
     * 清空本地缓存
     */
    @PostMapping("/clear/local")
    public Result clearLocalCache() {
        twoLevelCacheClient.clearLocalCache();
        return Result.ok("本地缓存已清空");
    }

    /**
     * 删除指定缓存
     */
    @DeleteMapping("/{key}")
    public Result evictCache(@PathVariable String key) {
        twoLevelCacheClient.evict(key);
        return Result.ok("缓存已删除: " + key);
    }

    /**
     * 批量删除缓存（支持通配符）
     */
    @DeleteMapping("/pattern/{pattern}")
    public Result evictByPattern(@PathVariable String pattern) {
        twoLevelCacheClient.evictByPattern(pattern);
        return Result.ok("缓存已删除: " + pattern);
    }
}

