package com.eashare.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.eashare.dto.Result;
import com.eashare.dto.UserDTO;
import com.eashare.entity.Follow;
import com.eashare.mapper.FollowMapper;
import com.eashare.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.eashare.service.IUserService;
import com.eashare.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate stringRedisTemplate ;
    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = "follow:" + userId;
        //判断是关注还是取关
        if (isFollow) {
            //保存关注数据到数据库
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean issucessed = save(follow);
            if (issucessed) {
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        } else {
            //取消关注，从数据库中删除关注数据
            boolean issucessed = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", followUserId));
            if (issucessed) {
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        //判断当前用户是否关注了该用户
        //获取当前用户ID
        Long userId = UserHolder.getUser().getId();
        //判断当前用户是否关注了该用户
        String key = "follow:" + userId;
        //查询判断当前用户是否关注了该用户
        //stringRedisTemplate.opsForSet().isMember(key, followUserId.toString())
        Integer count = query()
                .eq("user_id", userId)
                .eq("follow_user_id", followUserId)
                .count();
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommon(Long id) {
        //获取当前用户id
        Long userId = UserHolder.getUser().getId();
        String key = "follow:" + userId;
        //获取当前用户和传入用户id的交集
        String key2 = "follow:" + id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if (intersect == null || intersect.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        //解析出其中的用户id
        List<Long> userIds = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //查询用户信息
        List<UserDTO> users = userService.listByIds(userIds).stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(users);

    }
}
