package org.example.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;

import java.util.Date;
import java.util.Map;

public class JwtUtils {

    private static String signKey = "d3liNjY2";  //密钥 d3liNjY2是wyb666的base64编译后的结果
    private static Long expire = 43200000L;  //12小时过期

    /**
     * 生成JWT令牌
     * @return
     */
    public static String generateJwt(Map<String,Object> claims){
        String jwt = Jwts.builder()
                .addClaims(claims) //添加自定义信息
                .signWith(SignatureAlgorithm.HS256, signKey)  //设置前面算法和密钥
                .setExpiration(new Date(System.currentTimeMillis() + expire)) //设置过期时间
                .compact();  //生成
        return jwt;
    }

    /**
     * 解析JWT令牌
     * @param jwt JWT令牌
     * @return JWT第二部分负载 payload 中存储的内容
     */
    public static Claims parseJWT(String jwt){
        Claims claims = Jwts.parser()
                .setSigningKey(signKey)
                .parseClaimsJws(jwt)
                .getBody();
        return claims;
    }
}
