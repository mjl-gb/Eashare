package com.eashare.controller;


import com.eashare.dto.Result;
import com.eashare.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 */
@RestController
@RequestMapping("/follow")
public class FollowController {
    @Resource
    private IFollowService followService;
    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long FollowUserId, @PathVariable("isFollow") Boolean isFollow) {
        return followService.follow(FollowUserId, isFollow);
    }
    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long followUserId) {
        return followService.isFollow(followUserId);
    }
    @GetMapping("/common/{id}")
    public Result common(@PathVariable("id") Long id) {
        return followService.followCommon(id);
    }

}
