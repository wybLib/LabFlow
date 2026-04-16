package org.example.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import lombok.extern.slf4j.Slf4j;
import org.example.config.RabbitConfig;
import org.example.dto.commentPut;
import org.example.dto.notePublish;
import org.example.exception.CustomException;
import org.example.mapper.NoteMapper;
import org.example.mapper.TopicsMapper;
import org.example.pojo.*;
import org.example.service.NoteService;
import org.example.task.RecommendTask;
import org.example.task.ZsetRecommendTask;
import org.example.utils.ThreadLocalUtil;
import org.example.dto.LikeMessage;
import org.example.vo.noteAndUser;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataAccessException;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
public class NoteServiceImpl implements NoteService {
    @Autowired
    private NoteMapper noteMapper;
    @Autowired
    private TopicsMapper topicsMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RabbitTemplate rabbitTemplate;

//    public static final String setKey = "note:likes:";
//    public static final String countKey = "note:count:like:";

    public static final String NOTE_DETAIL_KEY = "note:detail:";
    public static final String LOCK_NOTE_DETAIL = "lock:note:detail:";
    // 缓存某篇笔记的最新 10 条热评
    public static final String CACHE_TOP10_COMMENTS = "note:comments:top:";
    // 🚀 新增：热榜 ZSET 的 Key
    public static final String RANK_ZSET_KEY = "labflow:notes:recommend:zset";
    // 专门用于后台异步重建缓存的线程池（避免每次都 new Thread 消耗资源）
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Override
    public PageResult<noteAndUser> GetNotesByPage(Integer page, Integer size, Integer topicId, String keyword) {
//        匹配 title（标题）和 summary（摘要），绝对不要用 MySQL 去匹配 content（正文）！
//        为什么不能匹配 content？
//        因为你的 content 字段在数据库里是 TEXT 类型，存储的是长篇大论甚至带有 HTML/Markdown 标签的长文本。
//        如果你在 MySQL 里写 WHERE content LIKE '%关键字%'，这会导致极其可怕的全表扫描（Full Table Scan）。
//        MySQL 的 B+ 树索引在面对以 % 开头的模糊查询时会直接失效。哪怕表里只有几万条数据，搜索一次也可能卡顿好几秒，直接把数据库 CPU 打满
//        如何搜索可问ai   引入 Elasticsearch (ES) 分布式搜索引擎！
        //设置分页参数
        PageHelper.startPage(page,size);
        //查询  这个rows就是一个Page对象 但是同时Page也是List的实现类 所以可以封装  体现了多态
        //这里适配前端  把rows改名list
        List<noteAndUser> list = noteMapper.GetNotesByPage(topicId,keyword);
//        injectNoteStatus(list);  //核心：返回给前端前，用全局注入器洗一遍数据！
        //解析结果并返回
        Page<noteAndUser> p = (Page<noteAndUser>) list;  //从Page对象中获取total和数据列表
        return new PageResult<noteAndUser>(p.getTotal(),p.getResult());
    }
    //这里目前有bug  返回给前端的只有笔记内容 没有作者相关信息  导致展示的时候看不到作者、头像等  建一个vo

    @Override
    public PageResult<noteAndUser> GetNotesByPageByPOJP(NoteQuery noteQuery) {
        //设置分页参数
        PageHelper.startPage(noteQuery.getPage(),noteQuery.getSize());
        //查询  这个rows就是一个Page对象 但是同时Page也是List的实现类 所以可以封装  体现了多态
        //这里适配前端  把rows改名list
        List<noteAndUser> list = noteMapper.GetNotesByPage(noteQuery.getTopicId(),noteQuery.getKeyword());
        injectNoteStatus(list);  //核心：返回给前端前，用全局注入器洗一遍数据！
        //解析结果并返回
        Page<noteAndUser> p = (Page<noteAndUser>) list;  //从Page对象中获取total和数据列表
        return new PageResult<noteAndUser>(p.getTotal(),p.getResult());
    }

    //并发设计  redis缓存
    @Override
    public PageResult<note> getTop50(NoteQuery noteQuery) {
        // 1. 获取基础数据底座
        String jsonStr = stringRedisTemplate.opsForValue().get(RecommendTask.TOP50_BASE_KEY);
        List<note> resultList;
        if (StrUtil.isNotBlank(jsonStr)) {
            resultList = JSONUtil.toList(jsonStr, note.class);
        } else {
            resultList = noteMapper.GetTop50();
        }
        // 2. 内存过滤 (如果传了关键字，就把不包含的踢出去)
        if (StrUtil.isNotBlank(noteQuery.getKeyword())) {
            resultList = resultList.stream()
                    .filter(n ->
                            (n.getTitle() != null && n.getTitle().contains(noteQuery.getKeyword())) ||
                                    (n.getSummary() != null && n.getSummary().contains(noteQuery.getKeyword()))
                    )
                    .collect(Collectors.toList());
        }
        // 3. 内存分页计算
        int total = resultList.size();
        int fromIndex = (noteQuery.getPage() - 1) * noteQuery.getSize();
        int toIndex = Math.min(noteQuery.getPage() * noteQuery.getSize(), total);

        List<note> pageList;
        if (fromIndex >= total) {
            pageList = new ArrayList<>(); // 页码超出，返回空列表
        } else {
            // 切割出当前页的数据
            pageList = resultList.subList(fromIndex, toIndex);
        }

        //抽离成了一个私有方法
//        Integer userId = null;
//        Map<String, Object> map = ThreadLocalUtil.get();
//        if (map != null && map.get("id") != null) {
//            userId = (Integer) map.get("id");
//        }
//
//        // 2. 内存拼装千人千面状态与实时总数
//        for (note n : resultList) {
//            // 实时状态检测
//            if (userId != null) {
//                String setKey = "note:likes:" + n.getId();
//                Boolean isLiked = stringRedisTemplate.opsForSet().isMember(setKey, userId.toString());
//                n.setIsLiked(Boolean.TRUE.equals(isLiked));
//            } else {
//                n.setIsLiked(false);
//            }
//
//            // 实时点赞数覆盖 (缓存里的数据可能是 5 分钟前的，这里用 Redis String 里的最新秒级数据覆盖它)
//            String countKey = "note:count:like:" + n.getId();
//            String realTimeCount = stringRedisTemplate.opsForValue().get(countKey);
//            if (StrUtil.isNotBlank(realTimeCount)) {
//                n.setLikes(Integer.parseInt(realTimeCount));
//            }
//        }

//        injectNoteStatus(pageList);  //核心：返回给前端前，用全局注入器洗一遍数据！
        return new PageResult<note>((long)total,pageList);

    }


//    @Override
//    public PageResult<noteAndUser> ZsetgetTop50(NoteQuery noteQuery) {
//        // 1. 从 ZSET 获取全部候选笔记 ID 和最新的实时分数 (按分数从大到小)
//        // reverseRangeWithScores 拿出来的顺序，就是绝对实时的、经历了无数次点赞超车后的最终排名！
//        Set<ZSetOperations.TypedTuple<String>> rankTuples = stringRedisTemplate.opsForZSet()
//                .reverseRangeWithScores(ZsetRecommendTask.RANK_ZSET_KEY, 0, -1);
//
//        if (rankTuples == null || rankTuples.isEmpty()) {
//            return new PageResult<>(0L, new ArrayList<>());
//        }
//
//        List<noteAndUser> allNotes = new ArrayList<>();
//
//        // 2. 遍历 ZSET 的结果，去 Hash 里捞详细数据(遍历ID，从 Hash 取完整笔记)
//        for (ZSetOperations.TypedTuple<String> tuple : rankTuples) {
//            String noteIdStr = tuple.getValue();
//            Double realTimeScore = tuple.getScore(); // 这里的 Score 就是刚刚被点赞改变过的最新赞数！
//
//            // 去 Hash 拿 JSON 详情
//            Object jsonObj = stringRedisTemplate.opsForHash().get(ZsetRecommendTask.DETAIL_HASH_KEY, noteIdStr);
//            if (jsonObj != null) {
//                //反序列化出笔记对象
//                noteAndUser n = JSONUtil.toBean(jsonObj.toString(), noteAndUser.class);
//                // 🚀 关键覆盖：用 ZSET 里最热乎的实时分数，覆盖掉旧 JSON 里的赞数
//                n.setLikes(realTimeScore != null ? realTimeScore.intValue() : n.getLikes());
//                allNotes.add(n);
//            }
//        }
//
//        //我们limit返回的100个  因为点赞是实时变化的  后50名的点赞可能会反超上来   需要备选池
//        //但是我们只给前端返回50个  并且只能在这50个里面搜  不然搜到了前端没有展示出来的推荐top50是不符合用户体验的
//        // 🚀 核心修正 1：无条件绝对截断！无论搜不搜索，我们的世界里只有前 50 名！
//        if (allNotes.size() > 50) {
//            allNotes = allNotes.subList(0, 50);
//        }
//
//        // 🚀 核心修正 2：在已经被严格阉割的 50 个名单里，进行关键字搜索  (忽略大小写)
//        //原生的 String.contains(keyword) 方法是区分大小的
//        if (StrUtil.isNotBlank(noteQuery.getKeyword())) {
//            allNotes = allNotes.stream()
//                    .filter(n ->
////                            (n.getTitle() != null && n.getTitle().contains(noteQuery.getKeyword())) ||
////                                    (n.getSummary() != null && n.getSummary().contains(noteQuery.getKeyword()))
//                                    // 使用 Hutool 的 containsIgnoreCase，自动忽略大小写，且内部自带 null 安全检查！
//                                    StrUtil.containsIgnoreCase(n.getTitle(), noteQuery.getKeyword()) ||
//                                            StrUtil.containsIgnoreCase(n.getSummary(), noteQuery.getKeyword())
//                    )
//                    .collect(Collectors.toList());
//        }
//
//        // 4. 内存分页切割
//        int total = allNotes.size();
//        int fromIndex = (noteQuery.getPage() - 1) * noteQuery.getSize();
//        int toIndex = Math.min(noteQuery.getPage() * noteQuery.getSize(), total);
//
//        List<noteAndUser> pageList;
//        if (fromIndex >= total) {
//            pageList = new ArrayList<>();
//        } else {
//            pageList = allNotes.subList(fromIndex, toIndex);
//        }
//
//        // 5. 注入用户的千人千面点赞高亮状态 (复用我们之前的全局注入器)
//        // 注入：当前用户是否点赞 + 最新点赞数
//        injectNoteStatus(pageList);  //java中传递对象是地址传递 不是值传递   所以这里不需要return也可有效修改
//
//        return new PageResult<>((long) total, pageList);
//    }

//@Override
//public PageResult<noteAndUser> ZsetgetTop50(NoteQuery noteQuery) {
//    // 🚀 优化 1：不要拉取全部 (-1)，只拉取前 50 名！在 Redis 端就截断，节省几倍的网络带宽！
//    Set<ZSetOperations.TypedTuple<String>> rankTuples = stringRedisTemplate.opsForZSet()
//            .reverseRangeWithScores(ZsetRecommendTask.RANK_ZSET_KEY, 0, 49);
//
//    if (rankTuples == null || rankTuples.isEmpty()) {
//        return new PageResult<>(0L, new ArrayList<>());
//    }
//
//    // 🚀 优化 2：把 50 个 ID 提取到一个集合中
//    List<Object> noteIds = new ArrayList<>();
//    List<Double> scores = new ArrayList<>(); // 对应存一下分数
//    for (ZSetOperations.TypedTuple<String> tuple : rankTuples) {
//        noteIds.add(tuple.getValue());
//        scores.add(tuple.getScore());
//    }
//
//    // 🚀 优化 3：使用 multiGet 一次性从 Hash 中捞出 50 篇文章详情！
//    // 把 50 次网络 I/O 变成 1 次！
//    List<Object> jsonObjs = stringRedisTemplate.opsForHash().multiGet(ZsetRecommendTask.DETAIL_HASH_KEY, noteIds);
//
//    List<noteAndUser> allNotes = new ArrayList<>();
//    for (int i = 0; i < jsonObjs.size(); i++) {
//        Object jsonObj = jsonObjs.get(i);
//        if (jsonObj != null) {
//            noteAndUser n = JSONUtil.toBean(jsonObj.toString(), noteAndUser.class);
//            Double realTimeScore = scores.get(i);
//            n.setLikes(realTimeScore != null ? realTimeScore.intValue() : n.getLikes());
//            allNotes.add(n);
//        }
//    }
//
//    // 🚀 核心修正 2：在 50 个名单里，进行关键字搜索 (忽略大小写)
//    if (StrUtil.isNotBlank(noteQuery.getKeyword())) {
//        allNotes = allNotes.stream()
//                .filter(n -> StrUtil.containsIgnoreCase(n.getTitle(), noteQuery.getKeyword()) ||
//                        StrUtil.containsIgnoreCase(n.getSummary(), noteQuery.getKeyword()))
//                .collect(Collectors.toList());
//    }
//
//    // 4. 内存分页切割
//    int total = allNotes.size();
//    int fromIndex = (noteQuery.getPage() - 1) * noteQuery.getSize();
//    int toIndex = Math.min(noteQuery.getPage() * noteQuery.getSize(), total);
//
//    List<noteAndUser> pageList;
//    if (fromIndex >= total) {
//        pageList = new ArrayList<>();
//    } else {
//        pageList = allNotes.subList(fromIndex, toIndex);
//    }
//
//    // 5. 注入用户的点赞状态和最新点赞数
//    injectNoteStatus(pageList);
//
//    return new PageResult<>((long) total, pageList);
//}


    //SSOT模式
@Override
public PageResult<noteAndUser> ZsetgetTop50(NoteQuery noteQuery) {
    // 1. 从 ZSet 拉取前 50 名的笔记 ID 和实时分数 (纯事件驱动，无需定时任务)
//    Set<ZSetOperations.TypedTuple<String>> rankTuples = stringRedisTemplate.opsForZSet()
//            .reverseRangeWithScores(ZsetRecommendTask.RANK_ZSET_KEY, 0, 49);
    Set<ZSetOperations.TypedTuple<String>> rankTuples = stringRedisTemplate.opsForZSet()
            .reverseRangeWithScores(RANK_ZSET_KEY, 0, 49);

    if (rankTuples == null || rankTuples.isEmpty()) {
        return new PageResult<>(0L, new ArrayList<>());
    }

    // 提取 ID 列表和分数映射
    List<String> noteIds = new ArrayList<>();
    Map<String, Double> scoreMap = new HashMap<>(); // 记住分数，用于排序和覆盖
    for (ZSetOperations.TypedTuple<String> tuple : rankTuples) {
        noteIds.add(tuple.getValue());
        scoreMap.put(tuple.getValue(), tuple.getScore());
    }

    // 2. 🚀 SSOT 核心：组装详情页缓存的 Key (note:detail:xxx)
    List<String> detailKeys = noteIds.stream()
            .map(id -> NOTE_DETAIL_KEY + id)
            .collect(Collectors.toList());

    // 3. 🚀 一次性批量拉取 50 篇详情缓存！
    List<String> jsonObjs = stringRedisTemplate.opsForValue().multiGet(detailKeys);

    List<noteAndUser> allNotes = new ArrayList<>();
    List<Integer> missingIds = new ArrayList<>(); // 收集没命中缓存的“倒霉蛋”ID

    // 4. 遍历解析 RedisData 逻辑过期外壳
    for (int i = 0; i < noteIds.size(); i++) {
        String jsonStr = jsonObjs.get(i);
        String currentId = noteIds.get(i);

        if (StrUtil.isNotBlank(jsonStr)) {
            // 过滤掉为了防穿透存入的物理空值 ""
            if (!"".equals(jsonStr)) {
                // ⚠️ 核心：反序列化为逻辑过期包装类 RedisData，再拿出里面的真实数据
                RedisData redisData = JSONUtil.toBean(jsonStr, RedisData.class);
                if (redisData != null && redisData.getData() != null) {
                    allNotes.add(redisData.getData());
                }
            }
        } else {
            // 彻底没命中物理缓存：记录下这个 ID，准备去 MySQL 批量捞！
            missingIds.add(Integer.parseInt(currentId));
        }
    }

    // 5. 🚀 批量回源 MySQL (Batch Fallback)
    if (!missingIds.isEmpty()) {
        log.info("首页推荐触发批量回源查库，缺失 ID: {}", missingIds);
        // 需要在 NoteMapper 新增一个 selectNoteAndUserByIds 方法 (使用 <foreach>)   避免N+1 查询问题
        List<noteAndUser> missingNotes = noteMapper.selectNoteAndUserByIds(missingIds);

        if (missingNotes != null && !missingNotes.isEmpty()) {
            allNotes.addAll(missingNotes);

            // 🚀 顺手把查出来的数据包装成 RedisData 并批量写回 Redis，修复缓存！
            stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (noteAndUser n : missingNotes) {
                    byte[] key = (NOTE_DETAIL_KEY + n.getId()).getBytes();

                    // 封装逻辑过期数据
                    RedisData newData = new RedisData();
                    newData.setData(n);
                    newData.setExpireTime(LocalDateTime.now().plusMinutes(30)); // 逻辑存活 30 分钟

                    byte[] value = JSONUtil.toJsonStr(newData).getBytes();
                    connection.stringCommands().setEx(key, 24 * 3600, value); // 24小时物理兜底
                }
                return null;
            });
        }
    }

    // 6. 🚨 恢复 ZSet 的真实顺序 (因为 MySQL 查出来的数据和原有数据混合后顺序乱了)
    allNotes.sort((n1, n2) -> {
        Double score1 = scoreMap.getOrDefault(n1.getId().toString(), 0.0);
        Double score2 = scoreMap.getOrDefault(n2.getId().toString(), 0.0);
        return Double.compare(score2, score1); // 按分数倒序
    });

    // 7. 覆盖实时点赞数 (直接用 ZSet 里的 score，绝对实时！)
    for (noteAndUser n : allNotes) {
        Double realTimeScore = scoreMap.get(n.getId().toString());
        if (realTimeScore != null) {
            n.setLikes(realTimeScore.intValue());
        }
    }
    // 注入当前用户是否高亮了这篇笔记的红心 (isLiked)
    injectNoteStatus(allNotes);

    // 8. 内存分页与过滤
    if (StrUtil.isNotBlank(noteQuery.getKeyword())) {
        allNotes = allNotes.stream()
                .filter(n -> StrUtil.containsIgnoreCase(n.getTitle(), noteQuery.getKeyword()) ||
                        StrUtil.containsIgnoreCase(n.getSummary(), noteQuery.getKeyword()))
                .collect(Collectors.toList());
    }

    int total = allNotes.size();
    int fromIndex = (noteQuery.getPage() - 1) * noteQuery.getSize();
    int toIndex = Math.min(noteQuery.getPage() * noteQuery.getSize(), total);

    List<noteAndUser> pageList;
    if (fromIndex >= total) {
        pageList = new ArrayList<>();
    } else {
        pageList = allNotes.subList(fromIndex, toIndex);
    }

    return new PageResult<>((long) total, pageList);
}


    @Override
    public void toggleLike(Integer noteId) {
        Map<String,Object> map = ThreadLocalUtil.get();
        Integer userId = (Integer) map.get("id");

        String setKey = "note:likes:" + noteId;
        String countKey = "note:count:like:" + noteId;

        // 1. O(1) 的纯内存幂等校验
        // SADD: 元素不存在则加入并返回 1；已存在则不操作并返回 0
        Long addResult = stringRedisTemplate.opsForSet().add(setKey, userId.toString());

        if (addResult != null && addResult > 0) {
            // 【场景 A：全新点赞】
            stringRedisTemplate.opsForValue().increment(countKey); // 内存实时总数 +1
            rabbitTemplate.convertAndSend(RabbitConfig.LIKE_EXCHANGE, RabbitConfig.LIKE_ROUTING_KEY,
                    new LikeMessage(noteId, userId, 1));
        } else {
            // 【场景 B：取消点赞，或手抖重试】
            // SREM: 元素存在则移除并返回 1；不存在则返回 0
            Long removeResult = stringRedisTemplate.opsForSet().remove(setKey, userId.toString());

            if (removeResult != null && removeResult > 0) {
                // 真的移除了，说明是正常的取消点赞动作
                stringRedisTemplate.opsForValue().decrement(countKey); // 内存实时总数 -1
                rabbitTemplate.convertAndSend(RabbitConfig.LIKE_EXCHANGE, RabbitConfig.LIKE_ROUTING_KEY,
                        new LikeMessage(noteId, userId, 0));
            }
            // 如果 removeResult 也是 0，说明什么？说明是极端的网络延迟重复包，Redis 直接无视，完美防重！
        }
    }

//    @Override
//    public void ZsettoggleLike(Integer noteId) {
//        Map<String,Object> map = ThreadLocalUtil.get();
//        Integer userId = (Integer) map.get("id");
//
//        String setKey = "note:likes:" + noteId; // 去重Set  用户对笔记的点赞
//        String countKey = "note:count:like:" + noteId; // 计数String  String原子操作实时维护点赞数 笔记的点赞数
//        String noteIdStr = noteId.toString();
//        // 🚀 核心防御：防止 INCR 从 0 开始！
//        if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(countKey))) {
//            // 如果 Redis 里没这个计数器了，赶紧去 MySQL 查出真实赞数兜底
//            note dbNote = noteMapper.selectById(noteId);
//            if (dbNote != null && dbNote.getLikes() != null) {
//                stringRedisTemplate.opsForValue().setIfAbsent(countKey, dbNote.getLikes().toString(), 24, TimeUnit.HOURS);
//            }
//        }
//
//        // 1. 内存幂等判断  Redis Set 去重：一个用户只能点一次   幂等业务(点赞幂)   还有mysql唯一索引兜底幂等
//        // Redis 做实时幂等，MySQL 做持久化幂等
//        Long addResult = stringRedisTemplate.opsForSet().add(setKey, userId.toString());
//
//        if (addResult != null && addResult > 0) {
//            // ================= 【全新点赞】 =================
//
//            // 🚀 修复点 1：恢复全局 String 计数器！(保证首页、搜索页数据同步)
//            stringRedisTemplate.opsForValue().increment(countKey); // 点赞数+1
//
//            // 🚀 修复点 2：尝试更新 ZSET 实时榜单 (保证推荐页物理超车)
//            // 细节防线：先看这篇笔记在不在 ZSET(Top100) 里。在的话才加分，不在就不管，防止把冷门笔记塞进热榜内存！
//            Double score = stringRedisTemplate.opsForZSet().score(ZsetRecommendTask.RANK_ZSET_KEY, noteIdStr);
//            if (score != null) {  //不在Top100内，不放入内存
//                stringRedisTemplate.opsForZSet().incrementScore(ZsetRecommendTask.RANK_ZSET_KEY, noteIdStr, 1);
//            }
//
//            // 异步落库 发送消息：异步写入数据库
//            rabbitTemplate.convertAndSend(RabbitConfig.LIKE_EXCHANGE, RabbitConfig.LIKE_ROUTING_KEY,
//                    new LikeMessage(noteId, userId, 1));
//        } else {
//            // ================= 【取消点赞】 =================
//            Long removeResult = stringRedisTemplate.opsForSet().remove(setKey, userId.toString());
//
//            if (removeResult != null && removeResult > 0) {
//                // 🚀 同步减全局 String 计数器
//                stringRedisTemplate.opsForValue().decrement(countKey);
//
//                // 🚀 同步减 ZSET 榜单分数
//                Double score = stringRedisTemplate.opsForZSet().score(ZsetRecommendTask.RANK_ZSET_KEY, noteIdStr);
//                if (score != null) {
//                    stringRedisTemplate.opsForZSet().incrementScore(ZsetRecommendTask.RANK_ZSET_KEY, noteIdStr, -1);
//                }
//
//                // 异步落库
//                rabbitTemplate.convertAndSend(RabbitConfig.LIKE_EXCHANGE, RabbitConfig.LIKE_ROUTING_KEY,
//                        new LikeMessage(noteId, userId, 0));
//            }
//        }
//    }

    @Override
    public void ZsettoggleLike(Integer noteId) {
        Map<String,Object> map = ThreadLocalUtil.get();
        Integer userId = (Integer) map.get("id");
        // 🚨 压测专用代码：模拟 1 到 100 万的随机并发用户
        // 压测结束后，请务必把这两行注销，恢复 ThreadLocal 的真实逻辑！
//        Integer userId = new java.util.Random().nextInt(1000000) + 1; // 随机生成 1~1000000 的伪用户ID

        String setKey = "note:likes:" + noteId;
        String countKey = "note:count:like:" + noteId;
        String noteIdStr = noteId.toString();

        // =========================================================
        // 🚀 核心防御升级：DCL 双重检查锁，防范写并发导致的缓存击穿！
        // =========================================================
        if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(countKey))) {
            // 利用 intern() 保证对同一个笔记 ID 加锁，不同笔记 ID 不互相阻塞
            synchronized (noteIdStr.intern()) {
                // 进入锁后，再查一次！因为可能别的线程刚帮你查完塞进 Redis 了
                if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(countKey))) {
                    note dbNote = noteMapper.selectById(noteId);
                    int baseLikes = (dbNote != null && dbNote.getLikes() != null) ? dbNote.getLikes() : 0;
                    // 查到真实数据后，作为基数塞回 Redis兜底
                    stringRedisTemplate.opsForValue().setIfAbsent(countKey, String.valueOf(baseLikes), 24, TimeUnit.HOURS);
                }
            }
        }

        // 1. 内存幂等判断：Redis Set 去重 (O(1) 极速操作)
        Long addResult = stringRedisTemplate.opsForSet().add(setKey, userId.toString());

        if (addResult != null && addResult > 0) {
            // ================= 【全新点赞】 =================
            // 1. 增加总数
            stringRedisTemplate.opsForValue().increment(countKey);

            // 2. 尝试更新推荐榜单
            Double score = stringRedisTemplate.opsForZSet().score(RANK_ZSET_KEY, noteIdStr);
            if (score != null) {
                stringRedisTemplate.opsForZSet().incrementScore(RANK_ZSET_KEY, noteIdStr, 1);
            }

            // 3. MQ 异步落库削峰
            rabbitTemplate.convertAndSend(RabbitConfig.LIKE_EXCHANGE, RabbitConfig.LIKE_ROUTING_KEY,
                    new LikeMessage(noteId, userId, 1));
        } else {
            // ================= 【取消点赞】 =================
            Long removeResult = stringRedisTemplate.opsForSet().remove(setKey, userId.toString());

            if (removeResult != null && removeResult > 0) {
                stringRedisTemplate.opsForValue().decrement(countKey);

                Double score = stringRedisTemplate.opsForZSet().score(RANK_ZSET_KEY, noteIdStr);
                if (score != null) {
                    stringRedisTemplate.opsForZSet().incrementScore(RANK_ZSET_KEY, noteIdStr, -1);
                }

                rabbitTemplate.convertAndSend(RabbitConfig.LIKE_EXCHANGE, RabbitConfig.LIKE_ROUTING_KEY,
                        new LikeMessage(noteId, userId, 0));
            }
        }
    }


    /**
     * 全局状态注入器：为任意笔记列表注入【实时点赞数】与【千人千面点赞状态】
     */
    //给每篇笔记填上两个前端必须的字段
//    private void injectNoteStatus(List<noteAndUser> notes) {
//        if (notes == null || notes.isEmpty()) return;
//
//        Integer userId = null;
//        Map<String, Object> map = ThreadLocalUtil.get();
//        if (map != null && map.get("id") != null) {
//            userId = (Integer) map.get("id");
//        }
//
//        for (noteAndUser n : notes) {
//            // 1. 当前用户是否点赞 注入点赞状态 (高亮星星)
//            if (userId != null) {
//                String setKey = "note:likes:" + n.getId();
//                Boolean isLiked = stringRedisTemplate.opsForSet().isMember(setKey, userId.toString());
//                n.setIsLiked(Boolean.TRUE.equals(isLiked));
////                n.setIsLiked(isLiked);  //这样其实就OK
//            } else {
//                n.setIsLiked(false);
//            }
//
//            // 2. 注入实时点赞数 覆盖实时点赞数
//            String countKey = "note:count:like:" + n.getId();
//            String realTimeCount = stringRedisTemplate.opsForValue().get(countKey);
//
////            ① 第一次加载冷门笔记
////            Redis 无计数器
////                setIfAbsent("note:count:like:1", "20");
////            Redis 现在 = 20
////② 用户点赞
////            increment(countKey) → 20 + 1 = 21
////            Redis 现在 = 21
////③ 下次再加载页面
////            String realTimeCount = get(countKey); // 拿到 21
////            覆盖 n.setLikes(21);
//            if (StrUtil.isNotBlank(realTimeCount)) {
//                // 场景 A：如果 Redis 里有实时数字，直接用它覆盖 MySQL 的旧数据
//                n.setLikes(Integer.parseInt(realTimeCount));
//            } else {  //一旦点赞过，Redis 就永久有值了，永远不会再走 else！
//                // 🚀 核心防线 (场景 B)：如果 Redis 里没有这个 String 计数器！
//                // 比如这是一篇刚刚被搜出来的冷门笔记。
//                // 此时 MySQL 查出来的 n.getLikes() 就是最准的基准值。
//                // 我们趁机把它塞进 Redis 兜底，作为未来 increment 操作的基座！
//                if (n.getLikes() != null) {
//                    stringRedisTemplate.opsForValue().setIfAbsent(countKey, n.getLikes().toString(), 24, TimeUnit.HOURS);
//                }
//            }
//        }
//    }


    // 注入状态方法的重构
    private void injectNoteStatus(List<noteAndUser> notes) {
        if (notes == null || notes.isEmpty()) return;

        Integer userId = null;
        Map<String, Object> map = ThreadLocalUtil.get();
        if (map != null && map.get("id") != null) {
            userId = (Integer) map.get("id");
        }

        final String userIdStr = userId != null ? userId.toString() : null;

        // 🚀 优化 4：批量组装 Redis Key
        List<String> countKeys = new ArrayList<>();
        for (noteAndUser n : notes) {
            countKeys.add("note:count:like:" + n.getId());
        }

        // 🚀 优化 5：使用 multiGet 一次性获取这 10 篇文章的最新点赞数！(10次变1次)
        List<String> realTimeCounts = stringRedisTemplate.opsForValue().multiGet(countKeys);

        // 🚀 优化 6：使用 Redis Pipeline (管道) 批量查询用户是否点赞！(10次变1次)
        List<Object> isLikedResults = null;
        if (userIdStr != null) {
            isLikedResults = stringRedisTemplate.executePipelined(new SessionCallback<Object>() {
                @Override
                public Object execute(RedisOperations operations) throws DataAccessException {
                    for (noteAndUser n : notes) {
                        operations.opsForSet().isMember("note:likes:" + n.getId(), userIdStr);
                    }
                    return null; // Pipeline 要求这里必须返回 null
                }
            });
        }

        // 把批量拿回来的数据，组装回对象里
        for (int i = 0; i < notes.size(); i++) {
            noteAndUser n = notes.get(i);

            // 1. 设置是否点赞
            if (isLikedResults != null && isLikedResults.size() > i) {
                n.setIsLiked(Boolean.TRUE.equals(isLikedResults.get(i)));
            } else {
                n.setIsLiked(false);
            }

            // 2. 覆盖实时点赞数
            String realTimeCount = realTimeCounts != null ? realTimeCounts.get(i) : null;
            if (StrUtil.isNotBlank(realTimeCount)) {
                n.setLikes(Integer.parseInt(realTimeCount));
            } else {
                // 如果是冷门笔记，Redis 没存它的点赞数缓存，兜底放进去
                if (n.getLikes() != null) {
                    stringRedisTemplate.opsForValue().setIfAbsent(countKeys.get(i), n.getLikes().toString(), 24, TimeUnit.HOURS);
                }
            }
        }
    }


    //这里有个bug    就是把笔记详情的context存在了redis中，没实时更新  12小时过期  若笔记作者修改了之后 无法更新实时内容
    //需要用户更新后  先更新mysql，再删除这个redis中的缓存key，然后用户重新进入笔记详情页面时重新获取key
    //已修改
//    更新数据库后，直接删除缓存（Delete），让下一个读请求去负责把最新数据拉回缓存。这叫“懒加载”，既安全，又省性能。
//    @Override
//    public noteAndUser detail(Integer noteId,Boolean isEdit) {
//        String cacheKey = NOTE_DETAIL_KEY + noteId;
//        String jsonStr = stringRedisTemplate.opsForValue().get(cacheKey);
//
//        // 1. 缓存命中，直接返回基础数据（需再拼装动态数据）  isNotBlank 不是Null、空串才会返回true
//        //DCL外层 快速返回，避免进入锁竞争
//        if (StrUtil.isNotBlank(jsonStr)) {
//            return buildDynamicDetailAndAddView(JSONUtil.toBean(jsonStr, noteAndUser.class), noteId,isEdit);
//        }
//
//        // 防御：缓存穿透  这段代码是读取空值，作为消费者  从缓存读取到空值时 识别空值并抛出异常
//        if (jsonStr != null && "".equals(jsonStr)) {
//            throw new CustomException("笔记不存在或已删除");
//        }
//
//        // ================= 防御：缓存击穿 (原生 SETNX 分布式锁)  这里逻辑用的递归 =================
////        在 Java 中，无边界的递归极容易耗尽线程的栈内存，引发 StackOverflowError，直接导致服务崩溃挂掉
//        // ================= 防御：缓存击穿 (自旋替代递归) 也是用的setnx分布式锁 但是用自旋而非递归=================
//        String lockKey = LOCK_NOTE_DETAIL + noteId;
//        //使用delete释放锁会存在误删可能性，即线程A把线程B获得的锁删了 优化：给每个线程的锁分配唯一的 UUID 标识，配合Lua 脚本原子释放
//        String lockValue = UUID.randomUUID().toString();
//        int retryCount = 0; // 加一个最大重试次数，防止死循环
//
//        while (retryCount < 10) { // 最多重试 10 次（也就是等待 500ms） 自旋机制
//            try {
//                // SETNX 获取锁
//                Boolean isLock = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", 10, TimeUnit.SECONDS);
//
//                if (Boolean.TRUE.equals(isLock)) {
//                    try {
//                        // 【Double Check】 完善的 Double Check，必须与外层的逻辑完全一致（镜像拦截）内层 防止重复查询数据库
//                        jsonStr = stringRedisTemplate.opsForValue().get(cacheKey);
//                        if (StrUtil.isNotBlank(jsonStr)) {
//                            return buildDynamicDetailAndAddView(JSONUtil.toBean(jsonStr, noteAndUser.class), noteId, isEdit);
//                        }
//                        // 必须拦截空字符串，防止并发排队线程再次穿透到 DB！  double check
//                        if (jsonStr != null && "".equals(jsonStr)) {
//                            throw new CustomException("笔记不存在或已删除");
//                        }
//
//                        // 去 MySQL 查
//                        log.info("🚨 警告：缓存未命中，正在穿透去 MySQL 查询真实数据！笔记 ID: {}", noteId);
//                        noteAndUser noteEntity = noteMapper.selectNoteAndUserById(noteId);
//                        if (noteEntity == null) {
//                            // 防御：缓存穿透  这段代码是写入空值，作为生产者  查数据库发现不存在时	写入空值到缓存 并设置过期时间
//    // 缓存空值有一个问题  有恶意攻击用海量且不重复的随机ID疯狂请求接口，每次都把这些随机生成的空值缓存起来，redis内存岂不是要爆炸
//    //  处理策略 三层架构 参数合法校验+布隆过滤器+缓存控制配合短过期时间
//    //在代码入口处，进行严格的ID规则和范围校验，快速失败。
//    //布隆过滤器说某个 ID 不存在，那它肯定不存在！它说某个 ID 存在，那它大概率存在（有极小的误判率）
//    //接下来是核心防御：在 Redis 之前架设 布隆过滤器（Bloom Filter）。将所有笔记 ID 放入其中，利用其 O(1) 的时间和极低的内存消耗，将 99% 的恶意假 ID 直接拦截。
//    //只有布隆过滤器判定存在的 ID，才允许查询 Redis。此时就算有布隆过滤器极小概率的‘假阳性误判’穿透过去，我原有的短 TTL 空值缓存就能完美兜底。
//    //最后，配合 Redis 的 LRU 内存淘汰策略，即使发生极端情况，也能保证核心服务不宕机。
////当 Redis 内存快满时，它会主动把那些最近没人用的旧缓存（比如黑客刚才塞进去的空字符串）给踢掉，腾出空间给新数据，确保 Redis 绝对不会因为 OOM 而宕机
//                            stringRedisTemplate.opsForValue().set(cacheKey, "", 2, TimeUnit.MINUTES);
//                            throw new CustomException("笔记不存在或已删除");
//                        }
//
//                        // ================= 防御：缓存雪崩 =================
//                        // 基础时间 12 小时 + 随机 0~12 小时
//                        long expireHours = 12 + new java.util.Random().nextInt(12);
//                        stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(noteEntity), expireHours, TimeUnit.HOURS);
//
//                        return buildDynamicDetailAndAddView(noteEntity, noteId, isEdit);
//                    } finally {
//                        // 释放锁必须放在 finally 里，确保只要抢到了锁，无论业务是否报错都会释放！
////                        stringRedisTemplate.delete(lockKey);   //容易误删
//                        // 优化使用 Lua 脚本保证“判断锁是否是自己的”和“删除锁”这两个操作的原子性
//                        String script = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
//                        stringRedisTemplate.execute(
//                                new DefaultRedisScript<>(script, Long.class),
//                                Collections.singletonList(lockKey),
//                                lockValue
//                        );
//                    }
//                } else {
//                    // 没抢到锁，休眠后进入下一次 while 循环重试，而不是危险的递归！
//                    Thread.sleep(50);
//                    retryCount++;
//                }
//            } catch (InterruptedException e) {
//                throw new CustomException("系统繁忙，请稍后再试");
//            }
//        }
//
//// 循环了 10 次（等了半秒钟）还是没拿到锁，直接降级或报错返回，保护 Tomcat 线程不被耗尽
//        throw new CustomException("系统拥挤，请稍后刷新重试");
//    }


@Override
public noteAndUser detail(Integer noteId, Boolean isEdit) {
    String cacheKey = NOTE_DETAIL_KEY + noteId;
    String jsonStr = stringRedisTemplate.opsForValue().get(cacheKey);

    // ================= 1. 防御：缓存穿透 (物理空值) =================
    if ("".equals(jsonStr)) {
        throw new CustomException("笔记不存在或已删除");
    }

    // ================= 2. 冷启动防御：物理缓存未命中 =================
    // 比如笔记刚发布第一次被访问，或者 24 小时的物理兜底 TTL 到期了。
    // 这时手里连“旧数据”都没有，必须阻塞排队去查 MySQL (复用 DCL 逻辑)
    if (StrUtil.isBlank(jsonStr)) {
        return rebuildCacheWithDCL(noteId, cacheKey, isEdit);
    }

    // ================= 3. 命中物理缓存，解析逻辑过期对象 =================
    RedisData redisData = JSONUtil.toBean(jsonStr, RedisData.class);
    noteAndUser baseNote = redisData.getData();
    LocalDateTime expireTime = redisData.getExpireTime();

    // 4. 判断逻辑时间是否过期
    if (expireTime != null && expireTime.isAfter(LocalDateTime.now())) {
        // 🟢 未过期：直接组装动态数据并返回
        return buildDynamicDetailAndAddView(baseNote, noteId, isEdit);
    }

    // ================= 5. 已逻辑过期：异步重建 + 极速返回旧数据 =================
    String lockKey = LOCK_NOTE_DETAIL + noteId;
    String lockValue = UUID.randomUUID().toString();

    // 尝试获取互斥锁（SETNX，防击穿）
    Boolean isLock = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, 10, TimeUnit.SECONDS);

    if (Boolean.TRUE.equals(isLock)) {
        // 抢到锁的幸运儿：开启独立线程异步重建缓存，绝对不阻塞当前主流程！
        CACHE_REBUILD_EXECUTOR.submit(() -> {
            try {
                // 查询 MySQL 最新数据
                noteAndUser noteEntity = noteMapper.selectNoteAndUserById(noteId);
                if (noteEntity != null) {
                    // 封装新的逻辑过期数据（例如：逻辑过期 30 分钟）
                    RedisData newData = new RedisData();
                    newData.setData(noteEntity);

                    // 🚀 逻辑过期时间随机化 (基础 30 分钟 + 随机 0~10 分钟)
                    int randomLogicMinutes = 30 + new java.util.Random().nextInt(10);
                    newData.setExpireTime(LocalDateTime.now().plusMinutes(randomLogicMinutes));

                    // 🚀 物理 TTL 随机化，同时防雪崩兜底防内存浪费 (基础 12 小时 + 随机 0~12 小时)
                    long expireHours = 12 + new java.util.Random().nextInt(12);
                    stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(newData), expireHours, TimeUnit.HOURS);
                    log.info("♻️ 后台异步线程成功刷新了笔记 {} 的逻辑过期缓存", noteId);
                }
            } catch (Exception e) {
                log.error("后台异步重建缓存失败", e);
            } finally {
                // 安全释放锁 (Lua)
                String script = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
                stringRedisTemplate.execute(new DefaultRedisScript<>(script, Long.class), Collections.singletonList(lockKey), lockValue);
            }
        });
    }

    // 🟡 无论是否抢到锁，当前线程都立刻拿着手里的“旧数据”返回！丝滑无感 0 延迟！
    return buildDynamicDetailAndAddView(baseNote, noteId, isEdit);
}

    /**
     * 专用于冷启动的 DCL 重建缓存机制（当物理缓存彻底失效时调用）
     */
    private noteAndUser rebuildCacheWithDCL(Integer noteId, String cacheKey, Boolean isEdit) {
        String lockKey = LOCK_NOTE_DETAIL + noteId;
        String lockValue = UUID.randomUUID().toString();
        int retryCount = 0;

        while (retryCount < 10) {
            try {
                Boolean isLock = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, 10, TimeUnit.SECONDS);

                if (Boolean.TRUE.equals(isLock)) {
                    try {
                        // 【Double Check】镜像拦截
                        String jsonStr = stringRedisTemplate.opsForValue().get(cacheKey);
                        if (StrUtil.isNotBlank(jsonStr)) {
                            if ("".equals(jsonStr)) throw new CustomException("笔记不存在或已删除");
                            RedisData redisData = JSONUtil.toBean(jsonStr, RedisData.class);
                            return buildDynamicDetailAndAddView(redisData.getData(), noteId, isEdit);
                        }

                        // 去 MySQL 查真实数据
                        log.info("🚨 物理缓存未命中，正在穿透去 MySQL 查询真实数据！笔记 ID: {}", noteId);
                        noteAndUser noteEntity = noteMapper.selectNoteAndUserById(noteId);
                        if (noteEntity == null) {
                            stringRedisTemplate.opsForValue().set(cacheKey, "", 2, TimeUnit.MINUTES);
                            throw new CustomException("笔记不存在或已删除");
                        }

                        // 组装逻辑过期外壳
                        RedisData newData = new RedisData();
                        newData.setData(noteEntity);

                        // 🚀 逻辑过期时间随机化
                        int randomLogicMinutes = 30 + new java.util.Random().nextInt(10);
                        newData.setExpireTime(LocalDateTime.now().plusMinutes(randomLogicMinutes));

                        // 🚀 物理 TTL 随机化，防雪崩兜底防内存浪费
                        long expireHours = 12 + new java.util.Random().nextInt(12);
                        stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(newData), expireHours, TimeUnit.HOURS);

                        return buildDynamicDetailAndAddView(noteEntity, noteId, isEdit);
                    } finally {
                        // 安全释放锁 (Lua)
                        String script = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
                        stringRedisTemplate.execute(new DefaultRedisScript<>(script, Long.class), Collections.singletonList(lockKey), lockValue);
                    }
                } else {
                    // 没抢到锁，因为连旧数据都没有，只能老老实实睡眠等待
                    Thread.sleep(50);
                    retryCount++;
                }
            } catch (InterruptedException e) {
                throw new CustomException("系统繁忙，请稍后再试");
            }
        }
        throw new CustomException("系统拥挤，请稍后刷新重试");
    }


    @Override
    public void publish(notePublish newNote) {
        //这里没有设置newNote里面的liks,views,create_time等  因为表在创建时 给这几个字段设了默认值
        Map<String,Object> map = ThreadLocalUtil.get();
        Integer userId = (Integer) map.get("id");
        String topicName = topicsMapper.getNameById(newNote.getTopicId());
        noteMapper.publish(userId,topicName,newNote);
        //主键回填后 获取id
        Integer noteId = newNote.getId();
        // 🚀 触发 AI 向量库增量同步
        Map<String, Object> syncMsg = new HashMap<>();
        syncMsg.put("note_id", noteId);
        syncMsg.put("action", "sync");
        rabbitTemplate.convertAndSend(RabbitConfig.VECTOR_SYNC_QUEUE, syncMsg);
    }

//    你修改笔记后，后端确实成功把新数据存进了 MySQL。所以当你在个人中心查看“我发布的”列表时（列表是直接查 MySQL 的），数据是最新的。
//    但是！ 你忘了你的详情页（getNoteDetail）为了扛住高并发，是优先查 Redis 缓存 (note:detail:{id}) 的！
//    你在更新 MySQL 后，忘了把 Redis 里那份旧的缓存炸掉！ 所以详情页和编辑回显，永远拿到的都是 Redis 里那份没人管的旧 JSON。
//    找到你后端负责“修改笔记”的那个方法（比如叫 updateNote），在更新完 MySQL 之后，必须加上一行极其关键的代码：删除 Redis 缓存！
//    这在分布式系统里叫做**“Cache Aside 旁路缓存模式”**。
//    @Override
//    public void editNote(Integer noteId,note editNote) {
//        editNote.setUpdateTime(LocalDateTime.now());
//        noteMapper.editNote(noteId,editNote);
//        // ==========================================
//        // 🚀 核心修复：双写一致性！必须把旧的 Redis 缓存炸掉！
//        // ==========================================
//        String cacheKey = "note:detail:" + noteId;
//        stringRedisTemplate.delete(cacheKey);
//        // 下一次用户再访问详情页，代码发现 Redis 里没数据，
//        // 就会乖乖去 MySQL 查出你刚改的新数据，并重新放进 Redis！
//
//        //还需要更新一下与之相关的其他键
//        //这里为什么不用旁路缓存，删除缓存，懒加载的原因
////        单体简单缓存（如商品详情、用户资料）：👉 坚决使用“旁路缓存（删除）”。依赖用户的下一次读请求来懒加载，永远不会有并发脏数据。
////        高频聚合缓存（如首页推荐、热搜榜单）：👉 坚决不能删！只能“局部原地更新” 或 “等待定时任务覆盖”。
////        ZSET 里还存着它的排名，但 Hash 里它的肉身已经被你删了。
////        当上万个用户同时打开首页时，系统去 Redis 里拿数据，拿到 50 个 ID，去 Hash 里一取，发现第 8 名是个 null！
////        这时候你的首页接口怎么办？为了补齐这个 null，难道要在几万并发的首页接口里，当场去查一次 MySQL 吗？这会瞬间拖垮首页的响应速度，把极其纯粹的 Redis O(1) 内存操作，变成了夹杂着磁盘 I/O 的噩梦。
//        // 3. 🚀 优雅更新：只覆盖 Hash 里的旧外观，保留 ZSET 里的排名！
//        // 先判断这篇被修改的笔记，到底在不在首页推荐的 Hash 池子里？
//        Boolean isTop50 = stringRedisTemplate.opsForHash().hasKey(ZsetRecommendTask.DETAIL_HASH_KEY, noteId.toString());
//
//        if (Boolean.TRUE.equals(isTop50)) {
//            // 如果它是一篇上榜的爆款笔记，我们就去数据库查出它刚更新后的【完整最新数据】
//            note latestNote = noteMapper.selectById(noteId); // 查出修改后的最新样子
//
//            if (latestNote != null) {
//                // 🚀 核心：直接覆盖 Hash 里的旧 JSON 字符串！
//                // 因为没动 ZSET，它的排名岿然不动，但是外面展示的标题和封面瞬间变成了最新的！（原地更新）
//                stringRedisTemplate.opsForHash().put(
//                        ZsetRecommendTask.DETAIL_HASH_KEY,
//                        noteId.toString(),
//                        JSONUtil.toJsonStr(latestNote)
//                );
//                log.info("已同步更新首页推荐榜单中笔记 {} 的最新外观数据", noteId);
//            }
//        }
//    }


    @Override
    public void editNote(Integer noteId, note editNote) {
        editNote.setUpdateTime(LocalDateTime.now());
        noteMapper.editNote(noteId, editNote);

        // ==========================================
        // 🚀 SSOT 极简双写一致性：必须把旧的 Redis 详情缓存炸掉！
        // 至于首页推荐？再也不用管了！首页会自动复用详情缓存，懒加载最新数据！
        // ==========================================
        String cacheKey = NOTE_DETAIL_KEY + noteId;
        stringRedisTemplate.delete(cacheKey);

        log.info("笔记 {} 修改成功，已清除单点真实数据源缓存", noteId);
        // 🚀 触发 AI 向量库增量同步
        Map<String, Object> syncMsg = new HashMap<>();
        syncMsg.put("note_id", noteId);
        syncMsg.put("action", "sync");
        rabbitTemplate.convertAndSend(RabbitConfig.VECTOR_SYNC_QUEUE, syncMsg);

        // 把你原来那一大坨 opsForHash().hasKey 和 put 的代码全部删掉！！！
    }

//    更新 DB -> 尝试删除缓存 -> 失败则发给 MQ -> 消费者不断重试删除。
//    @Override
//    @Transactional(rollbackFor = Exception.class)   //开启事务
//    public void deletedNote(Integer noteId) {
//        //在真正的互联网产品里，如果不做这个校验，黑客只需要写个脚本，
//        // 遍历请求 DELETE /notes/1 到 /notes/9999，就能把全站所有人的笔记全部删光！
//        // 1. 先查出这篇笔记
//        note existNote = noteMapper.selectById(noteId);
//
//        // 校验：笔记是否存在，或者是否已经被删除了
//        if (existNote == null || existNote.getIsDeleted() == 1) {
//            throw new CustomException("笔记不存在或已被删除");
//        }
//
//        Map<String,Object> map = ThreadLocalUtil.get();
//        Integer userId = (Integer) map.get("id");
//        // 2. 防越权校验：必须是作者本人才能删除
//        if (!existNote.getUserId().equals(userId)) {
//            throw new CustomException("非法操作：无权删除他人的笔记！");
//        }
//
//        // 3. 执行逻辑删除 (更新 is_deleted = 1)
//        //删除操作不能mq中去异步落 如果你把删除指令丢进 MQ，万一 MQ 发生积压或者消费延迟，用户刷新页面一看：“哎？我刚才不是删了吗，怎么还在？”
//        noteMapper.deletedNote(noteId);
//        // ==========================================
//        // 4. 🚀 核心：尝试清理 Redis 缓存 + MQ 补偿机制
//        // ==========================================
//        try {
//            // 尝试同步删除详情缓存   一定要删齐全
//            stringRedisTemplate.delete("note:detail:" + noteId);
//
//            // 顺手把这篇笔记的浏览量和点赞量缓存也清掉，节约内存
//            stringRedisTemplate.delete("note:count:view:" + noteId);
//            stringRedisTemplate.delete("note:count:like:" + noteId);
//            // 4.3 🚀 核心新增：清理首页推荐列表中的“幽灵数据”！
//            String noteIdStr = noteId.toString();
//            stringRedisTemplate.opsForZSet().remove(ZsetRecommendTask.RANK_ZSET_KEY, noteIdStr);
//            stringRedisTemplate.opsForHash().delete(ZsetRecommendTask.DETAIL_HASH_KEY, noteIdStr);
//
//            log.info("笔记 {} 逻辑删除成功，Redis 缓存同步清理完毕", noteId);
//
//        } catch (Exception e) {
//            // 🚨 发生异常（如 Redis 宕机、网络超时）
//            log.error("警告：Redis 删除缓存失败，即将触发 MQ 异步补偿机制！noteId: {}", noteId, e);
//
//            // 不要抛出异常！一旦抛出，上面的 MySQL 逻辑删除就会回滚！
//            // 我们选择把这个失败的任务，包装成一条消息丢给 RabbitMQ，让小弟去重试！
//            rabbitTemplate.convertAndSend(
//                    RabbitConfig.CACHE_EXCHANGE,
//                    RabbitConfig.CACHE_DELETE_ROUTING_KEY,
//                    noteId  // 直接把笔记 ID 传给 MQ
//            );
//        }
//
//    }

    @Override
    @Transactional(rollbackFor = Exception.class)   //开启事务
    public void deletedNote(Integer noteId) {
        //在真正的互联网产品里，如果不做这个校验，黑客只需要写个脚本，
        // 遍历请求 DELETE /notes/1 到 /notes/9999，就能把全站所有人的笔记全部删光！
        // 1. 先查出这篇笔记
        note existNote = noteMapper.selectById(noteId);

        // 校验：笔记是否存在，或者是否已经被删除了
        if (existNote == null || existNote.getIsDeleted() == 1) {
            throw new CustomException("笔记不存在或已被删除");
        }

        Map<String,Object> map = ThreadLocalUtil.get();
        Integer userId = (Integer) map.get("id");
        // 2. 防越权校验：必须是作者本人才能删除
        if (!existNote.getUserId().equals(userId)) {
            throw new CustomException("非法操作：无权删除他人的笔记！");
        }

        // 3. 执行逻辑删除 (更新 is_deleted = 1)
        //删除操作不能mq中去异步落 如果你把删除指令丢进 MQ，万一 MQ 发生积压或者消费延迟，用户刷新页面一看：“哎？我刚才不是删了吗，怎么还在？”
        noteMapper.deletedNote(noteId);
        // ==========================================
        // 4. 🚀 核心：尝试清理 Redis 缓存 + MQ 补偿机制
        // ==========================================
        try {
            stringRedisTemplate.delete("note:detail:" + noteId);
            stringRedisTemplate.delete("note:count:view:" + noteId);
            stringRedisTemplate.delete("note:count:like:" + noteId);

            // 🚀 清理首页推荐列表的 ZSet 排名即可，没有 Hash 需要删了！
            String noteIdStr = noteId.toString();
            stringRedisTemplate.opsForZSet().remove(RANK_ZSET_KEY, noteIdStr);

            log.info("笔记 {} 逻辑删除成功，Redis 缓存同步清理完毕", noteId);
            // 🚀 触发 AI 向量库数据抹除
            Map<String, Object> syncMsg = new HashMap<>();
            syncMsg.put("note_id", noteId);
            syncMsg.put("action", "delete");
            rabbitTemplate.convertAndSend(RabbitConfig.VECTOR_SYNC_QUEUE, syncMsg);

        } catch (Exception e) {
            // 🚨 发生异常（如 Redis 宕机、网络超时）
            log.error("警告：Redis 删除缓存失败，即将触发 MQ 异步补偿机制！noteId: {}", noteId, e);

            // 不要抛出异常！一旦抛出，上面的 MySQL 逻辑删除就会回滚！
            // 我们选择把这个失败的任务，包装成一条消息丢给 RabbitMQ，让小弟去重试！
            rabbitTemplate.convertAndSend(
                    RabbitConfig.CACHE_EXCHANGE,
                    RabbitConfig.CACHE_DELETE_ROUTING_KEY,
                    noteId  // 直接把笔记 ID 传给 MQ
            );
        }

    }

    @Override
    public void pushComments(Integer noteId,comment newComment) {
//        newComment.setLikes(0);
//        newComment.setCreateTime();
        //这些数据不用组装  数据库表设置了默认值的   只需要组装发布人的userid以及所属笔记的id
        Map<String,Object> map = ThreadLocalUtil.get();
        Integer userId = (Integer) map.get("id");
        String name = (String) map.get("name");
        newComment.setUserId(userId);
        newComment.setNoteId(noteId);
        commentPut commentToVisual = new commentPut(name,newComment.getContent());
        // 2. 🚀 Redis 秒开缓存 (只保留最新 10 条，挡住 90% 读取洪峰)
        //用户点开详情页面后  只展示最新的前10条评论  用户可以自行点击加载更多来获取完整的评论  这里可以缓解数据库压力
        //原理 首屏拦截（极速秒开）：这 10 万个用户打开页面时，读取的全部是 Redis 里的 List 缓存。Redis 处理 10 万 QPS 就像喝水一样简单，MySQL 此时压力为 0。
        //流量漏斗（自然衰减）：真实的用户行为是，90% 的人（9万人）只会看一眼首屏的前 10 条评论，看完就关掉页面了。他们根本不会去点“加载更多”！
        //穿透到数据库的真实压力：只有剩下 10% 的深度吃瓜群众（1万人），可能会去点“加载更多”。而且这 1 万人点击的时间是分散的（有人看字快，有人看字慢），这 1 万个请求可能被分散在 10 秒内。
        //结果：MySQL 的压力从瞬间 10万 并发，变成了每秒 1000 并发。数据库轻轻松松抗住了！
        //并且这里注意  不能点加载更多就获取全部的评论  这是大量的数据 并且生成大量的对象  造成数据库和内存崩溃 还有前端卡死 网络阻塞
        //需要限制分页 必须严格按页码每次只给 10 条或 20 条
        //提升用户体验：把“手动点击加载更多”改成“滚动到底部自动加载下一页”（也就是刷抖音、刷小红书的体验）。
        String cacheKey = CACHE_TOP10_COMMENTS + noteId.toString();  //构建缓存 Key
        //将评论存入列表头部 leftPush()从列表左侧（头部）插入元素
        stringRedisTemplate.opsForList().leftPush(cacheKey, JSONUtil.toJsonStr(newComment));
        stringRedisTemplate.opsForList().trim(cacheKey, 0, 9); // 修剪 List，只保留前 10 条

        // 3. 🚀 MQ 异步落库：发送给 Insert 队列
        rabbitTemplate.convertAndSend(RabbitConfig.COMMENT_INSERT_QUEUE, newComment);

        // 4. 🚀 MQ 实时广播：发送给 Fanout 交换机
        Map<String, Object> pushMsg = new HashMap<>();
        pushMsg.put("noteId", noteId.toString());
        pushMsg.put("type", "NEW_COMMENT");
//        pushMsg.put("data", "用户{"+name+"}发送评论："+newComment.getContent());
        pushMsg.put("data", commentToVisual);   //这里传对象  因为需要前端处理

        // 路由键为空字符串 ""，因为 Fanout 模式下路由器会无视路由键，直接发给所有绑定的队列
        rabbitTemplate.convertAndSend(RabbitConfig.COMMENT_FANOUT_EXCHANGE, "", JSONUtil.toJsonStr(pushMsg));
    }

    /**
     * 核心私有方法：拼装动态数据 & 触发浏览量异步增加
     */
    private noteAndUser buildDynamicDetailAndAddView(noteAndUser baseNote, Integer noteId, Boolean isEdit) {
        noteAndUser noteDetail = new noteAndUser();
        BeanUtils.copyProperties(baseNote, noteDetail);

        // 1. 处理浏览量 (极速响应 + 异步 MQ 落库)
        String viewCountKey = "note:count:view:" + noteId;   //string原子计数

        // 防御 INCR 归零 Bug：没有基准值先用 DB 兜底
        if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(viewCountKey))) {
            stringRedisTemplate.opsForValue().setIfAbsent(viewCountKey, baseNote.getViews().toString(), 24, TimeUnit.HOURS);
        }

        // 🚀 核心逻辑区分：是不是编辑模式？
        if (isEdit != null && isEdit) {
            // ==========================================
            // 【编辑模式】：只读取当前浏览量，绝不 +1，也绝不发 MQ
            // ==========================================
            String currentViewsStr = stringRedisTemplate.opsForValue().get(viewCountKey);
            noteDetail.setViews(currentViewsStr != null ? Integer.parseInt(currentViewsStr) : baseNote.getViews());
        } else {
            // ==========================================
            // 【正常浏览模式】：浏览量 +1，并发送 MQ 异步落库
            // ==========================================
            Long currentViews = stringRedisTemplate.opsForValue().increment(viewCountKey);
            noteDetail.setViews(currentViews != null ? currentViews.intValue() : baseNote.getViews());

            // 丢给 MQ 异步更新 MySQL，绝不阻塞当前线程
            rabbitTemplate.convertAndSend(RabbitConfig.VIEW_EXCHANGE, RabbitConfig.VIEW_ROUTING_KEY, noteId);
        }

        // 2. 处理实时点赞数 (复用咱们之前写好的逻辑)
        String likeCountKey = "note:count:like:" + noteId;
        String realTimeLikes = stringRedisTemplate.opsForValue().get(likeCountKey);
        if (StrUtil.isNotBlank(realTimeLikes)) {
            noteDetail.setLikes(Integer.parseInt(realTimeLikes));
        }

        // 3. 千人千面：当前用户的点赞高亮状态
        Map<String, Object> map = ThreadLocalUtil.get();
        if (map != null && map.get("id") != null) {
            Integer userId = (Integer) map.get("id");
            noteDetail.setIsLiked(Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember("note:likes:" + noteId, userId.toString())));
        } else {
            noteDetail.setIsLiked(false);
        }
        // 🚨 压测专用：造假百万网友疯狂吃瓜
//        Integer userId = new java.util.Random().nextInt(1000000) + 1; // 随机百万用户
//        if (userId != null) { // 压测时这行直接进
//            // ==========================================
//            noteDetail.setIsLiked(Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember("note:likes:" + noteId, userId.toString())));
//        } else {
//            noteDetail.setIsLiked(false);
//        }

        return noteDetail;
    }
}
