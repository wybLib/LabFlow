package org.example.controller;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.example.pojo.Result;
import org.example.service.TopicsService;
import org.example.vo.topicGet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@Slf4j
@Valid
public class TopicsController {
    @Autowired
    private TopicsService topicsService;
    //首次登录选话题
    @GetMapping("/topics")
    public Result FirstTopics(){
        log.info("获取所有备选话题 (供冷启动弹窗)");
        List<topicGet> topics = topicsService.GetAllTopics();
        return Result.success(topics);
    }
}
