"""
app/worker.py
独立的 AI 异步同步消费者 (RabbitMQ Consumer)
职责：监听 Java 端发来的消息，执行高耗时的 RAG 向量化与删除任务
"""

import asyncio
import json
import os
import aio_pika
from dotenv import load_dotenv

# 引入我们已经对齐 Labflow 字段的同步方法
from app.storage.sync import sync_single_note, delete_note_from_vectorstore

load_dotenv()

async def process_message(message: aio_pika.IncomingMessage):
    async with message.process():  # 自动 ACK/NACK 机制   如果下面的代码顺利跑完，它会自动向 MQ 发送 ACK，MQ 就会把这条消息安全删除。
            #如果下面的代码报错崩溃了，它会自动发 NACK，MQ 会把消息重新放回队列等待重试，绝对不会丢消息！

        #把从 MQ 拿到的二进制字节流，解码成字符串，再转成 Python 字典，提取出 note_id 和我们要做的 action（是同步还是删除）。
        body = message.body.decode("utf-8")
        payload = json.loads(body)

        note_id = payload.get("note_id")
        action = payload.get("action")

        print(f"\n[RabbitMQ] 📩 收到动作: {action.upper()} | 笔记 ID: {note_id}")

        try:
            if action == "delete":
                # asyncio.to_thread开一个子线程跑  主程序继续监听 MQ，极大提升了并发吞吐量
                await asyncio.to_thread(delete_note_from_vectorstore, note_id)
            else:
                await asyncio.to_thread(sync_single_note, note_id)

            print(f"[RabbitMQ] ✅ 笔记 {note_id} 操作完成！")
        except Exception as e:
            print(f"[RabbitMQ] ❌ 笔记 {note_id} 操作失败: {e}")
            raise e  # 抛出异常触发消息重试


async def main():
    print("🚀 正在启动 AI 向量化同步消费者 (Worker)...")

    mq_url = os.getenv("RABBITMQ_URL", "amqp://guest:guest@127.0.0.1:5672/")
    connection = await aio_pika.connect_robust(mq_url)
    channel = await connection.channel()

    # 每次只拿 1 个任务，公平调度
    await channel.set_qos(prefetch_count=1)

    # 声明持久化队列 (假设我们在 Java 里叫 VECTOR_SYNC_QUEUE)
    queue = await channel.declare_queue("VECTOR_SYNC_QUEUE", durable=True)

    print("👂 监听中... [按 Ctrl+C 退出]")
    await queue.consume(process_message)

    try:
        await asyncio.Future()  # 永久挂起监听
    finally:
        await connection.close()


if __name__ == "__main__":
    asyncio.run(main())