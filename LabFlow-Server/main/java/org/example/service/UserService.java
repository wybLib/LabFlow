package org.example.service;
import jakarta.validation.constraints.Pattern;
import org.example.dto.UpdatePwdDTO;
import org.example.dto.userRegister;
import org.example.pojo.PageResult;
import org.example.pojo.note;
import org.example.pojo.user;
import org.example.vo.commentGet;
import org.example.vo.topicGet;
import org.example.vo.userProfile;

import java.util.List;
import java.util.Map;

public interface UserService {
    void register(userRegister userR);

    user findByUserName(String username);

    Map<String,Object> login(@Pattern(regexp = "^\\S{5,16}$",message = "用户名必须是5到16位非空字符") String username, @Pattern(regexp = "^\\S{5,16}$",message = "密码必须是5到16位非空字符") String password);

    userProfile getById();

    void editProfile(userProfile newuserProfile);

    void setTopics(List<Integer> topicsId);

    List<topicGet> getTopics();

    PageResult<note> getPublished(Integer page, Integer size);

    PageResult<note> getLikes(Integer page, Integer size);

    void logout(String token);

    Boolean updatePWD(UpdatePwdDTO pwdDTO, String token);
}
