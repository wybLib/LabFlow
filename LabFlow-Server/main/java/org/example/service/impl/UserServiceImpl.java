package org.example.service.impl;

import cn.hutool.core.util.StrUtil;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import org.example.dto.UpdatePwdDTO;
import org.example.dto.userRegister;
import org.example.mapper.UserMapper;
import org.example.mapper.UserTopicMapper;
import org.example.pojo.PageResult;
import org.example.pojo.note;
import org.example.pojo.user;
import org.example.service.TopicsService;
import org.example.service.UserService;
import org.example.utils.BcryptUtil;
import org.example.utils.JwtUtils;
import org.example.utils.ThreadLocalUtil;
import org.example.vo.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class UserServiceImpl implements UserService {

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private UserTopicMapper userTopicMapper;

    @Override
    public user findByUserName(String username) {
        return userMapper.findByUserName(username);
    }


    @Override
    public void register(userRegister userR) {
        user newUser = new user();
        newUser.setUsername(userR.getUsername());
        newUser.setPassword(userR.getPassword());
        newUser.setCreateTime(LocalDateTime.now());
        newUser.setUpdateTime(LocalDateTime.now());
        //对密码进行加密 再存入数据库
        String encrypt = BcryptUtil.encrypt(userR.getPassword());
        newUser.setPassword(encrypt);
        // ---------------------------------------------------------
        // 2. 核心补充：为新用户分配默认资料，填补前端没有传的空缺字段
        // ---------------------------------------------------------

        // 生成随机默认昵称 (例如: "研思极客_8a2b3c")
        // 使用 UUID 截取前 6 位保证一定的随机性，避免大家名字都一样
        String randomSuffix = UUID.randomUUID().toString().substring(0, 6);
        newUser.setName("研思极客_" + randomSuffix);

        // 分配一个极简的默认头像 URL (可以使用 Element Plus 官方的那个默认灰底小人图，或者你自己准备的图)
        newUser.setAvatar("https://cube.elemecdn.com/3/7c/3ea6beec64369c2642b92c6726f1epng.png");

        // 顺手给个默认的个人简介
        newUser.setBio("探索技术的无限可能...");
        userMapper.register(newUser);
    }

    @Override
    public Map<String,Object> login(String username, String password) {
        user user1 = userMapper.findByUserName(username);
        //比对密码是否正确
        if(BcryptUtil.match(password,user1.getPassword())){
            //生成Jwt
            Map<String, Object> claims = new HashMap<>();
            claims.put("id",user1.getId());
            claims.put("name",user1.getName());
//            //把业务数据存储到线程  这个应该放在拦截器
//            ThreadLocalUtil.set(claims);

            String token = JwtUtils.generateJwt(claims);
            //实现令牌失效机制 还需把token存入redis中
            ValueOperations<String, String> operations = stringRedisTemplate.opsForValue();
            operations.set(token,token,12, TimeUnit.HOURS);  //过期时间与jwt令牌一样
            userLogin userlogin = new userLogin();
            userlogin.setAvatar(user1.getAvatar());
            userlogin.setName(user1.getName());
            userlogin.setIsTopicInitialized(user1.getIsTopicInitialized());
            userlogin.setId(user1.getId());


            Map<String,Object> map = new HashMap<>();
            map.put("token",token);
            map.put("user",userlogin);

            return map;
        }
        return null;
    }

    @Override
    public userProfile getById() {
        Map<String,Object> map = ThreadLocalUtil.get();
        Integer id = (Integer) map.get("id");
        return userMapper.getById(id);
    }

    @Override
    public void editProfile(userProfile newuserProfile) {
        user newuser = new user();
        newuser.setBio(newuserProfile.getBio());
        newuser.setAvatar(newuserProfile.getAvatar());
        newuser.setName(newuserProfile.getName());
        newuser.setUpdateTime(LocalDateTime.now());
        Map<String,Object> map = ThreadLocalUtil.get();
        Integer id = (Integer) map.get("id");
        newuser.setId(id);
        userMapper.editProfile(newuser);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)   //多次操作数据库表 开启事务
    public void setTopics(List<Integer> topicsId) {
        //先删再插
//        这是一个非常经典的后端开发场景。你想，用户不仅会在“第一次冷启动”时选话题，以后在个人中心点击“⚙️管理话题”时，也会重新选。
//        如果他原来选了 [1, 2]，现在改成了 [2, 3]。如果你去写对比逻辑（“保留2，删除1，新增3”），代码会极其复杂且容易出 Bug。
//        工业界最优雅的做法是：全删全插（先 Delete 后 Batch Insert）。
        Map<String,Object> map = ThreadLocalUtil.get();
        Integer id = (Integer) map.get("id");
        userTopicMapper.deleteByUserId(id);
        //如果前端传来的数组不为空，则批量插入新的关联关系  如果为空，表示不选  则上面已经删除
        if(topicsId!=null && !topicsId.isEmpty()){
            userTopicMapper.insertData(id,topicsId);   //批量插入  动态sql
        }
        //设置状态
        userMapper.updateTopicInitialized(id);
    }

    @Override
    public List<topicGet> getTopics() {
        Map<String,Object> map = ThreadLocalUtil.get();
        Integer id = (Integer) map.get("id");
        //返回结果 用于前端渲染
        return userTopicMapper.selectUserTopics(id);
    }

    @Override
    public PageResult<note> getPublished(Integer page, Integer size) {
        Map<String,Object> map = ThreadLocalUtil.get();
        Integer userId = (Integer) map.get("id");
        //设置分页参数
        PageHelper.startPage(page,size);
        //查询  这个rows就是一个Page对象 但是同时Page也是List的实现类 所以可以封装  体现了多态
        //这里适配前端  把rows改名list
        List<note> list = userMapper.getPublished(userId);
        injectNoteStatus(list);  //核心：返回给前端前，用全局注入器洗一遍数据！
        //解析结果并返回
        Page<note> p = (Page<note>) list;  //从Page对象中获取total和数据列表
        return new PageResult<note>(p.getTotal(),p.getResult());
    }

    @Override
    public PageResult<note> getLikes(Integer page, Integer size) {
        Map<String,Object> map = ThreadLocalUtil.get();
        Integer userId = (Integer) map.get("id");
        //设置分页参数
        PageHelper.startPage(page,size);
        //查询  这个rows就是一个Page对象 但是同时Page也是List的实现类 所以可以封装  体现了多态
        //这里适配前端  把rows改名list
        List<note> list = userMapper.getLikes(userId);
        injectNoteStatus(list);  //核心：返回给前端前，用全局注入器洗一遍数据！
        //解析结果并返回
        Page<note> p = (Page<note>) list;  //从Page对象中获取total和数据列表
        return new PageResult<note>(p.getTotal(),p.getResult());
    }

    @Override
    public void logout(String token) {
        stringRedisTemplate.delete(token);
    }

    @Override
    public Boolean updatePWD(UpdatePwdDTO pwdDTO, String token) {
        Map<String,Object> map = ThreadLocalUtil.get();
        Integer userId = (Integer) map.get("id");
        String cruPWD = userMapper.getPWD(userId);
        // 3. 校验旧密码是否正确
        if (!BcryptUtil.match(pwdDTO.getOldPassword(), cruPWD)) {
            return false;
        }
        // 4. 对新密码进行 Bcrypt 加密
        String encryptedNewPwd = BcryptUtil.encrypt(pwdDTO.getNewPassword());

        // 5. 更新数据库中的密码
        userMapper.updatePassword(userId, encryptedNewPwd);

        // 6. 🚀 核心：密码修改成功后，把当前的 token 从 Redis 中删掉！
        // 这样拦截器再查 Redis 就查不到了，强制用户必须重新登录
        stringRedisTemplate.delete(token);

        return true;
    }

    /**
     * 全局状态注入器：为任意笔记列表注入【实时点赞数】与【千人千面点赞状态】
     */
    //给每篇笔记填上两个前端必须的字段
    private void injectNoteStatus(List<note> notes) {
        if (notes == null || notes.isEmpty()) return;

        Integer userId = null;
        Map<String, Object> map = ThreadLocalUtil.get();
        if (map != null && map.get("id") != null) {
            userId = (Integer) map.get("id");
        }

        for (note n : notes) {
            // 1. 当前用户是否点赞 注入点赞状态 (高亮星星)
            if (userId != null) {
                String setKey = "note:likes:" + n.getId();
                Boolean isLiked = stringRedisTemplate.opsForSet().isMember(setKey, userId.toString());
                n.setIsLiked(Boolean.TRUE.equals(isLiked));
//                n.setIsLiked(isLiked);  //这样其实就OK
            } else {
                n.setIsLiked(false);
            }

            // 2. 注入实时点赞数 覆盖实时点赞数
            String countKey = "note:count:like:" + n.getId();
            String realTimeCount = stringRedisTemplate.opsForValue().get(countKey);

//            ① 第一次加载冷门笔记
//            Redis 无计数器
//                setIfAbsent("note:count:like:1", "20");
//            Redis 现在 = 20
//② 用户点赞
//            increment(countKey) → 20 + 1 = 21
//            Redis 现在 = 21
//③ 下次再加载页面
//            String realTimeCount = get(countKey); // 拿到 21
//            覆盖 n.setLikes(21);
            if (StrUtil.isNotBlank(realTimeCount)) {
                // 场景 A：如果 Redis 里有实时数字，直接用它覆盖 MySQL 的旧数据
                n.setLikes(Integer.parseInt(realTimeCount));
            } else {  //一旦点赞过，Redis 就永久有值了，永远不会再走 else！
                // 🚀 核心防线 (场景 B)：如果 Redis 里没有这个 String 计数器！
                // 比如这是一篇刚刚被搜出来的冷门笔记。
                // 此时 MySQL 查出来的 n.getLikes() 就是最准的基准值。
                // 我们趁机把它塞进 Redis 兜底，作为未来 increment 操作的基座！
                if (n.getLikes() != null) {
                    stringRedisTemplate.opsForValue().setIfAbsent(countKey, n.getLikes().toString(), 24, TimeUnit.HOURS);
                }
            }
        }
    }

    // 注入状态方法的重构
//    private void injectNoteStatus(List<noteAndUser> notes) {
//        if (notes == null || notes.isEmpty()) return;
//
//        Integer userId = null;
//        Map<String, Object> map = ThreadLocalUtil.get();
//        if (map != null && map.get("id") != null) {
//            userId = (Integer) map.get("id");
//        }
//
//        final String userIdStr = userId != null ? userId.toString() : null;
//
//        // 🚀 优化 4：批量组装 Redis Key
//        List<String> countKeys = new ArrayList<>();
//        for (noteAndUser n : notes) {
//            countKeys.add("note:count:like:" + n.getId());
//        }
//
//        // 🚀 优化 5：使用 multiGet 一次性获取这 10 篇文章的最新点赞数！(10次变1次)
//        List<String> realTimeCounts = stringRedisTemplate.opsForValue().multiGet(countKeys);
//
//        // 🚀 优化 6：使用 Redis Pipeline (管道) 批量查询用户是否点赞！(10次变1次)
//        List<Object> isLikedResults = null;
//        if (userIdStr != null) {
//            isLikedResults = stringRedisTemplate.executePipelined(new SessionCallback<Object>() {
//                @Override
//                public Object execute(RedisOperations operations) throws DataAccessException {
//                    for (noteAndUser n : notes) {
//                        operations.opsForSet().isMember("note:likes:" + n.getId(), userIdStr);
//                    }
//                    return null; // Pipeline 要求这里必须返回 null
//                }
//            });
//        }
//
//        // 把批量拿回来的数据，组装回对象里
//        for (int i = 0; i < notes.size(); i++) {
//            noteAndUser n = notes.get(i);
//
//            // 1. 设置是否点赞
//            if (isLikedResults != null && isLikedResults.size() > i) {
//                n.setIsLiked(Boolean.TRUE.equals(isLikedResults.get(i)));
//            } else {
//                n.setIsLiked(false);
//            }
//
//            // 2. 覆盖实时点赞数
//            String realTimeCount = realTimeCounts != null ? realTimeCounts.get(i) : null;
//            if (StrUtil.isNotBlank(realTimeCount)) {
//                n.setLikes(Integer.parseInt(realTimeCount));
//            } else {
//                // 如果是冷门笔记，Redis 没存它的点赞数缓存，兜底放进去
//                if (n.getLikes() != null) {
//                    stringRedisTemplate.opsForValue().setIfAbsent(countKeys.get(i), n.getLikes().toString(), 24, TimeUnit.HOURS);
//                }
//            }
//        }
//    }


}
