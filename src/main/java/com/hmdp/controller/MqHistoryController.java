package com.hmdp.controller;

import com.hmdp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 消息队列历史记录查询控制器
 */
@Slf4j
@RestController
@RequestMapping("/mq-history")
public class MqHistoryController {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final String MQ_HISTORY_KEY = "mq:history:seckill:";

    /**
     * 查询最近的成功记录列表
     * @param count 查询数量，默认10条
     * @return 成功记录列表
     */
    @GetMapping("/success/list")
    public Result getSuccessList(@RequestParam(defaultValue = "10") Integer count) {
        try {
            String key = MQ_HISTORY_KEY + "success";
            Long size = stringRedisTemplate.opsForList().size(key);
            
            if (size == null || size == 0) {
                return Result.ok(new ArrayList<>());
            }
            
            // 获取最新的count条记录
            List<String> records = stringRedisTemplate.opsForList().range(key, -count, -1);
            return Result.ok(records);
        } catch (Exception e) {
            log.error("查询成功记录失败", e);
            return Result.fail("查询失败：" + e.getMessage());
        }
    }

    /**
     * 查询最近的失败记录列表
     * @param count 查询数量，默认10条
     * @return 失败记录列表
     */
    @GetMapping("/failure/list")
    public Result getFailureList(@RequestParam(defaultValue = "10") Integer count) {
        try {
            String key = MQ_HISTORY_KEY + "failure";
            Long size = stringRedisTemplate.opsForList().size(key);
            
            if (size == null || size == 0) {
                return Result.ok(new ArrayList<>());
            }
            
            // 获取最新的count条记录
            List<String> records = stringRedisTemplate.opsForList().range(key, -count, -1);
            return Result.ok(records);
        } catch (Exception e) {
            log.error("查询失败记录失败", e);
            return Result.fail("查询失败：" + e.getMessage());
        }
    }

    /**
     * 根据订单ID查询详细历史
     * @param orderId 订单ID
     * @return 订单详细信息
     */
    @GetMapping("/order/{orderId}")
    public Result getOrderHistory(@PathVariable Long orderId) {
        try {
            String key = MQ_HISTORY_KEY + orderId;
            Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(key);
            
            if (entries.isEmpty()) {
                return Result.fail("未找到订单历史记录");
            }
            
            return Result.ok(entries);
        } catch (Exception e) {
            log.error("查询订单历史失败", e);
            return Result.fail("查询失败：" + e.getMessage());
        }
    }

    /**
     * 查询所有历史记录的key
     * @return 历史记录key列表
     */
    @GetMapping("/keys")
    public Result getHistoryKeys() {
        try {
            Set<String> keys = stringRedisTemplate.keys(MQ_HISTORY_KEY + "*");
            return Result.ok(keys);
        } catch (Exception e) {
            log.error("查询历史keys失败", e);
            return Result.fail("查询失败：" + e.getMessage());
        }
    }

    /**
     * 统计信息
     * @return 统计数据
     */
    @GetMapping("/stats")
    public Result getStats() {
        try {
            String successKey = MQ_HISTORY_KEY + "success";
            String failureKey = MQ_HISTORY_KEY + "failure";
            
            Long successCount = stringRedisTemplate.opsForList().size(successKey);
            Long failureCount = stringRedisTemplate.opsForList().size(failureKey);
            
            Map<String, Object> stats = new HashMap<>();
            stats.put("successCount", successCount != null ? successCount : 0);
            stats.put("failureCount", failureCount != null ? failureCount : 0);
            stats.put("totalCount", (successCount != null ? successCount : 0) + (failureCount != null ? failureCount : 0));
            
            return Result.ok(stats);
        } catch (Exception e) {
            log.error("查询统计信息失败", e);
            return Result.fail("查询失败：" + e.getMessage());
        }
    }

    /**
     * 清空历史记录（谨慎使用）
     * @return 结果
     */
    @DeleteMapping("/clear")
    public Result clearHistory() {
        try {
            Set<String> keys = stringRedisTemplate.keys(MQ_HISTORY_KEY + "*");
            if (keys != null && !keys.isEmpty()) {
                stringRedisTemplate.delete(keys);
                log.info("已清空所有MQ历史记录，共删除 {} 个key", keys.size());
            }
            return Result.ok("清空成功");
        } catch (Exception e) {
            log.error("清空历史记录失败", e);
            return Result.fail("清空失败：" + e.getMessage());
        }
    }
}
