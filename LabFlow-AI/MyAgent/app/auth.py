# app/auth.py
from typing import Optional
import redis.asyncio as aioredis
import os
import jwt
from fastapi import Header, HTTPException, Request
from dotenv import load_dotenv

load_dotenv()

_redis: Optional[aioredis.Redis] = None

def get_redis() -> aioredis.Redis:
    global _redis
    if _redis is None:
        _redis = aioredis.Redis(
            host=os.getenv("REDIS_HOST", "127.0.0.1"),
            port=int(os.getenv("REDIS_PORT", "6379")),
            password=os.getenv("REDIS_PASSWORD") or None,
            decode_responses=True, #从 Redis 拿出来的数据全是 b'xyz'（字节类型），加了之后会自动帮你解码成 Python 的字符串格式（String），省去手动 decode 的麻烦。
        )
    return _redis

async def get_request_user_id(
        request: Request,
        authorization: Optional[str] = Header(default=None, alias="Authorization"),
) -> str:
    """
    对接 Labflow 的 JWT + Redis 鉴权体系
    """
    if not authorization or not authorization.strip():
        raise HTTPException(status_code=401, detail="缺少 Authorization token")

    # 1. 规范处理 Bearer 前缀，提取纯净的 Token
    token = authorization.strip()
    if token.startswith("Bearer "):
        token = token[7:]

    if not token:
        raise HTTPException(status_code=401, detail="Token 格式异常")

    try:
        # 去 Redis 里搜一下这个 Token。如果搜不到（返回 None），说明 Java 端设置的过期时间到了，或者用户退出了登录，直接拦截并报错 401（未授权）。
        redis_val = await get_redis().get(token)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Redis连接失败: {e}")

    # 查出来是 None，说明已过期或主动注销，和 Java 拦截器逻辑一致
    if redis_val is None:
        raise HTTPException(status_code=401, detail="Token 无效或已过期，请重新登录")

    # 3. 提取 userId (利用 jwt 无密钥解码)
    # 正常解析 JWT 需要提供 Secret Key（秘钥）。但秘钥在 Java 后端的手里。既然我们刚才已经在 Redis 里查到了这个 Token，说明 Java 已经盖过章认可是合法的了。
    # 所以在这里，我们直接跳过签名校验，强行拆开 JWT 的包裹（Payload），拿出里面的 id
    try:
        payload = jwt.decode(token, options={"verify_signature": False})
        user_id = payload.get("id") # 对应你 Java ThreadLocal 里存的 id
        if not user_id:
            raise ValueError("JWT 载荷中找不到用户 id")
    except Exception as e:
        raise HTTPException(status_code=401, detail=f"Token 解析失败: {e}")

    # 顺手续期（可选，看你 Java 端是否也有这个需求）  不要这个需求  java后端写定了token过期时间
    # await get_redis().expire(token, 60 * 60)

    return str(user_id)