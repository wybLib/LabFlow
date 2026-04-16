package org.example.service.impl;

import org.example.mapper.TopicsMapper;
import org.example.service.TopicsService;
import org.example.vo.topicGet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TopicsServiceImpl implements TopicsService {
    @Autowired
    private TopicsMapper topicsMapper;
    @Override
    public List<topicGet> GetAllTopics() {
        return topicsMapper.getAll();
    }
}
