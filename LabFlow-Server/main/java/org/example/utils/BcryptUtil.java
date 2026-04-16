package org.example.utils;

import org.mindrot.jbcrypt.BCrypt;

/**
 * Bcrypt 密码加密工具类
 */
public class BcryptUtil {

    /**
     * 对明文密码进行加密
     *
     * @param rawPassword 明文密码 (例如: "123456")
     * @return 加密后的密文 (长度通常为 60 的字符串)
     */
    public static String encrypt(String rawPassword) {
        // BCrypt.gensalt() 默认使用 10 轮 Hash 强度，既能保证安全性，又不会因为加密太慢影响并发性能
        return BCrypt.hashpw(rawPassword, BCrypt.gensalt());
    }

    /**
     * 校验明文密码与密文是否匹配
     *
     * @param rawPassword     用户输入的明文密码 (例如: "123456")
     * @param encodedPassword 数据库中存储的密文 (例如: "$2a$10$N.zmdr...")
     * @return true: 密码正确; false: 密码错误
     */
    public static boolean match(String rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null) {
            return false;
        }
        try {
            return BCrypt.checkpw(rawPassword, encodedPassword);
        } catch (Exception e) {
            // 防止数据库中存入的不是标准 Bcrypt 格式导致校验抛出异常
            return false;
        }
    }
}