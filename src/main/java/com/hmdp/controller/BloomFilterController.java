package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.utils.BloomFilterUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * 布隆过滤器管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/bloom")
public class BloomFilterController {

    @Resource
    private BloomFilterUtil bloomFilterUtil;

    /**
     * 查询布隆过滤器统计信息
     * @return 统计信息
     */
    @GetMapping("/stats")
    public Result getStats() {
        long count = bloomFilterUtil.getCount();
        return Result.ok(java.util.Collections.singletonMap("elementCount", count));
    }

    /**
     * 测试布隆过滤器 - 判断ID是否存在
     * @param id 店铺ID
     * @return 是否可能存在
     */
    @GetMapping("/contains/{id}")
    public Result contains(@PathVariable Long id) {
        boolean exists = bloomFilterUtil.mightContain(id);
        return Result.ok(java.util.Collections.singletonMap("mightExist", exists));
    }

    /**
     * 手动添加ID到布隆过滤器
     * @param id 店铺ID
     * @return 结果
     */
    @PostMapping("/add/{id}")
    public Result add(@PathVariable Long id) {
        boolean success = bloomFilterUtil.add(id);
        return Result.ok(java.util.Collections.singletonMap("success", success));
    }

    /**
     * 重置布隆过滤器（谨慎使用）
     * @return 结果
     */
    @PostMapping("/reset")
    public Result reset() {
        try {
            bloomFilterUtil.reset();
            return Result.ok("布隆过滤器已重置");
        } catch (Exception e) {
            log.error("重置布隆过滤器失败", e);
            return Result.fail("重置失败：" + e.getMessage());
        }
    }
}
