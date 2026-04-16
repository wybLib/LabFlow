# eval/generate_labeled_data.py
import os
import json
from dotenv import load_dotenv
from langchain_deepseek import ChatDeepSeek
from app.storage.build_index import load_notes_from_mysql, notes_to_documents

load_dotenv()

llm = ChatDeepSeek(
    model=os.getenv("DEEPSEEK_MODEL", "deepseek-chat"),
    api_key=os.getenv("DEEPSEEK_API_KEY"),
    api_base=os.getenv("DEEPSEEK_BASE_URL"),
    temperature=0,
)

# 复用你已有的生成逻辑
raw_notes = load_notes_from_mysql()[:30]          # 取30篇笔记，避免太多
docs = notes_to_documents(raw_notes)

labeled = []

print("🚀 正在生成人工标注级别数据（30条）...")
for idx, doc in enumerate(docs):
    chunk = doc.page_content
    meta = doc.metadata
    note_id = meta.get("note_id")
    user_id = meta.get("user_id")

    # 让 DeepSeek 生成高质量问题 + 标准答案
    prompt = f"""根据下面笔记片段，生成一个真实用户会问的问题和对应的标准答案。
要求：
- 问题要自然、口语化
- 答案必须100%来自片段，不能编造
- 只输出 JSON 格式

笔记片段：
{chunk[:800]}...

请直接输出：
{{
  "question": "...",
  "ground_truth": "..."
}}
"""

    try:
        response = llm.invoke(prompt).content
        qa = json.loads(response)
        labeled.append({
            "question": qa["question"],
            "user_id": str(user_id),
            "relevant_note_ids": [str(note_id)],      # 单条相关笔记（真实标注风格）
            "ground_truth": qa["ground_truth"]
        })
        print(f"✅ {idx+1:2d} 生成成功")
    except:
        print(f"⚠️  {idx+1:2d} 生成失败，跳过")

os.makedirs("eval", exist_ok=True)
with open("generate_labeled_data.json", "w", encoding="utf-8") as f:
    json.dump(labeled, f, ensure_ascii=False, indent=2)

print(f"\n🎉 生成完成！共 {len(labeled)} 条标注数据 → eval/generate_labeled_data.json")