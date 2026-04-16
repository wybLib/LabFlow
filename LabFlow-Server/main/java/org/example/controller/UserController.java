package org.example.controller;

import cn.hutool.core.util.StrUtil;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
//import org.apache.catalina.User;
import org.example.dto.UpdatePwdDTO;
import org.example.dto.userRegister;
import org.example.pojo.PageResult;
import org.example.pojo.Result;
import org.example.pojo.note;
import org.example.pojo.user;
import org.example.service.UserService;
import org.example.utils.BcryptUtil;
import org.example.vo.commentGet;
import org.example.vo.topicGet;
import org.example.vo.userLogin;
import org.example.vo.userProfile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@Slf4j
@RequestMapping("/api/v1")
@Valid
public class UserController {
    @Autowired
    private UserService userService;

    @PostMapping("/auth/register")
    public Result register(@RequestBody @Validated userRegister userR){
        user user1 = userService.findByUserName(userR.getUsername());
        if(user1==null){
            log.info("用户注册....{}",userR.getUsername());
            userService.register(userR);
        }else{
            log.info("用户已存在...");
            return Result.error("用户已存在");
        }

        return Result.success();
    }

    @PostMapping("/auth/login")
    public Result login(@RequestBody @Validated userRegister userR){
        user user1 = userService.findByUserName(userR.getUsername());
        if(user1==null){
            return Result.error("用户不存在");
        }
        //这里还需要实现把token存入redis中，做一个主动失效功能 实现用户修改密码后使之前的token失效
        //先要去redis-server.exe中启动
        Map<String,Object> map = userService.login(userR.getUsername(),userR.getPassword());
        if(map.get("token")!=null){
            log.info("登录.....");
            return Result.success(map);
        }else{
            return Result.error("密码错误");
        }
    }

    //退出登录的本质就是前端丢弃本地 Token，后端从 Redis 白名单中删除 Token。
    @PostMapping("/auth/logout")
    public Result logout(@RequestHeader("Authorization") String token){
        log.info("退出登录...");
        // 通常前端传来的 token 带有 "Bearer " 前缀，需要处理一下
        if (StrUtil.isNotBlank(token) && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        userService.logout(token);
        return Result.success();
    }

    @GetMapping("/users/me")
    public Result profile(){
        userProfile userp = userService.getById();
        log.info("用户个人信息获取....");
        return Result.success(userp);
    }

    //这里前端校验了昵称不能为空  但表字段没有设置not null限制 若通过postman等工具可以恶意访问  所以要执行后端校验   而bio本身就可以为空
    @PutMapping("/users/me")
    public Result editProfile(@RequestBody @Validated userProfile newuserProfile){
        log.info("修改用户信息...");
        userService.editProfile(newuserProfile);
        return Result.success();
    }

    @PatchMapping("/users/me/password")
    public Result updatePWD(@RequestBody @Validated UpdatePwdDTO pwdDTO,@RequestHeader("Authorization") String token){
        log.info("用户修改密码");
        // 剥离 Bearer 前缀，拿到纯净 token
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        Boolean isSuccess = userService.updatePWD(pwdDTO,token);
        if(isSuccess){
            return Result.success();
        }else{
            return Result.error("原密码错误，修改失败");
        }

    }

    //选择话题
    @PostMapping("/users/me/topics")
    public Result setTopics(@RequestBody List<Integer> topicsId){
        userService.setTopics(topicsId);
        return Result.success();
    }

    //返回已选择话题
    @GetMapping("/users/me/topics")
    public Result getTopics(){
        List<topicGet> topics = userService.getTopics();
        return Result.success(topics);
    }


    @GetMapping("/users/me/notes")
    public Result getComments(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "9") Integer size) {

        PageResult<note> pageResult = userService.getPublished(page, size);
        return Result.success(pageResult);
    }

    @GetMapping("/users/me/likes")
    public Result getLikes(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "9") Integer size) {

        PageResult<note> pageResult = userService.getLikes(page, size);
        return Result.success(pageResult);
    }





}
