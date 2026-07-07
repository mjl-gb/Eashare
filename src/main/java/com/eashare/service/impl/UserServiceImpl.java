package com.eashare.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.eashare.dto.LoginFormDTO;
import com.eashare.dto.Result;
import com.eashare.dto.UserDTO;
import com.eashare.entity.User;
import com.eashare.mapper.UserMapper;
import com.eashare.service.IUserService;
import com.eashare.utils.RegexUtils;
import com.eashare.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.eashare.utils.RedisConstants.*;
import static com.eashare.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        //2.如果不符合，返回手机号不符合错误信息
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号不符合规范");
        }
        //3.符合，生成验证码
        String code = RandomUtil.randomString(6);
        //4.保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        log.debug("返回验证码成功: {}",code);
        //5.返回ok
        return Result.ok();

    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        //2.如果不符合，返回手机号不符合错误信息
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            return Result.fail("手机号不符合规范");
        }
        //3.从redis获取验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + loginForm.getPhone());
        //4.判断验证码是否一致
        if (cacheCode == null || !cacheCode.equals(loginForm.getCode())) {
            //不一致，返回验证码错误
            return Result.fail("验证码错误");
        }
        //5.一致，根据手机号查询用户
        User user = query().eq("phone", loginForm.getPhone()).one();
        //6.判断用户是否存在
        if (user == null) {
            user = new User();
            user.setPhone(loginForm.getPhone());
            user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
            save(user);
            //不存在，返回用户不存在错误信息
            return Result.fail("用户不存在");
        }

        //7.保存用户到redis
        //7.1.随机生成token
        String token = UUID.randomUUID().toString(true);
        //7.2.将User对象转为Hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(
                userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())
                );
        //7.3.存储
        stringRedisTemplate.opsForHash( ).putAll(LOGIN_USER_KEY + token, userMap);
        //7.4.设置token有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.DAYS);
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        //获取日期
        LocalDateTime now = LocalDateTime.now();
        //获取今天是本月的第几天
        int day = now.getDayOfMonth();
        //拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        stringRedisTemplate.opsForValue().setBit(key, day - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        //获取日期
        LocalDateTime now = LocalDateTime.now();
        //获取今天是本月的第几天
        int daymonth = now.getDayOfMonth();
        //生成key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //获取本月截止今天为止的所有的签到记录，返回的是一个十进制的数字
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(daymonth)).valueAt(0));
        if (result == null || result.size() == 0) {
            //没有签到结果
            return Result.ok(0);
        }
        Long number = result.get(0);
        if(number == null|| number == 0) return Result.ok(0);
        //循环遍历
        int count = 0;
        while (true) {
            //让这个数字与1做按位与运算，得到数字的最后一个bit位
            long bit = number & 1;
            //判断这个bit位是否为1
            if (bit == 1) {
                //如果为1，说明这个日期有签到，计数器+1
                count++;
            }
            //如果为0，说明这个日期没有签到，结束
            if (bit == 0) {
                break;
            }
            //把数字右移一位，继续下一个bit位
            number >>>= 1;
        }
        //返回
        return Result.ok(count);
    }
}
