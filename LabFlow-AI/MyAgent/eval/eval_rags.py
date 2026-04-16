""" eval/eval_ragas.py - 最终稳定精简版（百分百防崩溃、防死锁、防下标越界） """

import asyncio
import json
import os
from typing import List

from dotenv import load_dotenv
from langchain_core.messages import HumanMessage, SystemMessage
from langchain_openai import ChatOpenAI
from openai import AsyncOpenAI

from ranx import Qrels, Run, evaluate
from ragas.dataset_schema import EvaluationDataset, SingleTurnSample
from ragas.embeddings import HuggingFaceEmbeddings
from ragas.llms import llm_factory
from ragas.metrics.collections import ContextPrecision, Faithfulness, SemanticSimilarity

from app.core.rag import make_rag_retriever, rerank_docs
from app.storage.embeddings import _get_model
from app.storage.sync import get_vectorstore

load_dotenv()

LABELED_DATA_PATH = os.path.join(os.path.dirname(__file__), "labeled_data.json")
# 🚀 增加这一行：强行使用国内 Hugging Face 镜像站，防止网络连接超时（WinError 10060）
os.environ["HF_ENDPOINT"] = "https://hf-mirror.com"
K = 6

# ==================== 1. 加载人工标注数据 ====================
def load_labeled_data() -> List[dict]:
    if not os.path.exists(LABELED_DATA_PATH):
        raise FileNotFoundError(f"❌ 找不到标注文件：{LABELED_DATA_PATH}")
    with open(LABELED_DATA_PATH, encoding="utf-8") as f:
        data = json.load(f)
    print(f"✅ 加载人工标注数据：{len(data)} 条")
    return data

# ==================== 2. 真实检索（全局检索） ====================
def do_retrieve(retriever_factory, question: str):
    retriever = retriever_factory(None)   # None = 全站检索
    raw_docs = retriever.invoke(question)
    return rerank_docs(question, raw_docs, top_n=K)

# ==================== 3. 真实生成 ====================
def generate_answer(llm, question: str, docs: list) -> str:
    if not docs:
        return "抱歉，没有找到相关笔记。"
    context_text = "\n\n---\n\n".join(doc.page_content for doc in docs)
    messages = [
        SystemMessage(content=(
            "你是用户的笔记助手 Rover。请严格基于以下笔记内容回答，不得编造。\n\n"
            f"笔记内容：\n{context_text}"
        )),
        HumanMessage(content=question),
    ]
    return llm.invoke(messages).content

# ==================== 4. 检索端评估（仅 Recall@K） ====================
def evaluate_retrieval(retriever_factory, labeled_data: List[dict]) -> dict:
    print(f"\n{'─' * 55}\n📊 检索端评估（ranx） Recall@{K}\n{'─' * 55}")
    qrels_dict = {}
    run_dict = {}

    for i, item in enumerate(labeled_data):
        query_id = f"q{i}"
        question = item["question"]
        relevant_ids = item.get("relevant_note_ids", [])

        if not relevant_ids:
            continue

        qrels_dict[query_id] = {str(nid): 1 for nid in relevant_ids}

        docs = do_retrieve(retriever_factory, question)
        run_dict[query_id] = {
            str(doc.metadata["note_id"]): 1.0 / (rank + 1)
            for rank, doc in enumerate(docs)
            if doc.metadata.get("note_id")
        }

    if not qrels_dict:
        print("⚠️ 没有有效的 Qrels 数据")
        return {}

    metric_name = f"recall@{K}"
    # 🛡️ 终极防御 1：去掉列表符号，防止旧版 ranx 返回字典；增加 isinstance 判定，确保一定能拿到 float
    raw_score = evaluate(Qrels(qrels_dict), Run(run_dict), metric_name)
    if isinstance(raw_score, dict):
        raw_score = raw_score.get(metric_name, 0.0)

    score = round(float(raw_score), 4)

    print(f"  Recall@{K} = {score} （覆盖率）")
    return {"Recall@K": score}

# ==================== 5. 构建 RAGAS 数据集 ====================
def build_ragas_dataset(labeled_data, retriever_factory, llm):
    samples = []
    raw_rows = []
    skipped = 0

    print("\n正在构建 RAGAS 评估数据集...")
    for item in labeled_data:
        question = item["question"]
        ground_truth = item["ground_truth"]

        docs = do_retrieve(retriever_factory, question)
        if not docs:
            skipped += 1
            continue

        contexts = [doc.page_content for doc in docs]
        answer = generate_answer(llm, question, docs)

        samples.append(SingleTurnSample(
            user_input=question,
            retrieved_contexts=contexts,
            response=answer,
            reference=ground_truth,
        ))
        raw_rows.append({"question": question, "contexts": contexts, "answer": answer, "ground_truth": ground_truth})

    dataset = EvaluationDataset(samples=samples)
    print(f"✅ 数据集构建完成：{len(samples)} 条（跳过 {skipped} 条无结果）")
    return dataset, raw_rows

# ==================== 6. RAGAS 评估 ====================
async def run_ragas_evaluation(dataset: EvaluationDataset) -> dict:
    client = AsyncOpenAI(
        api_key=os.getenv("DEEPSEEK_API_KEY"),
        base_url=os.getenv("DEEPSEEK_BASE_URL"),
    )
    evaluator_llm = llm_factory(os.getenv("DEEPSEEK_MODEL"), client=client)
    embeddings = HuggingFaceEmbeddings(model="BAAI/bge-m3")

    cp_metric = ContextPrecision(llm=evaluator_llm)
    ff_metric = Faithfulness(llm=evaluator_llm)
    ss_metric = SemanticSimilarity(embeddings=embeddings)

    async def score_sample(sample):
        # 🛡️ 终极防御 2：捕获具体报错，提取 .value 增加安全判定
        try:
            cp = await cp_metric.ascore(user_input=sample.user_input, reference=sample.reference, retrieved_contexts=sample.retrieved_contexts)
            cp_score = cp.value if hasattr(cp, 'value') else float(cp)
        except Exception as e:
            print(f"  [CP Error]: {e}")
            cp_score = None

        try:
            ff = await ff_metric.ascore(user_input=sample.user_input, response=sample.response, retrieved_contexts=sample.retrieved_contexts)
            ff_score = ff.value if hasattr(ff, 'value') else float(ff)
        except Exception as e:
            print(f"  [FF Error]: {e}")
            ff_score = None

        try:
            # 🛡️ 终极防御 3：强制使用同步 score() 绕过本地 bge-m3 的异步死锁，并兼容不同返回格式
            ss = ss_metric.score(response=sample.response, reference=sample.reference)
            if isinstance(ss, dict):
                ss_score = ss.get("semantic_similarity", 0.0)
            elif hasattr(ss, 'value'):
                ss_score = ss.value
            else:
                ss_score = float(ss)
        except Exception as e:
            print(f"  [SS Error]: {e}")
            ss_score = None

        return cp_score, ff_score, ss_score

    print("\n正在并发评估 RAGAS 指标...")
    tasks = [score_sample(s) for s in dataset.samples]
    all_scores = await asyncio.gather(*tasks, return_exceptions=True)

    # 🛡️ 终极防御 4：彻底筛除异常和 None 值，确保 len() > 0
    cp_scores = [s[0] for s in all_scores if not isinstance(s, Exception) and s[0] is not None]
    ff_scores = [s[1] for s in all_scores if not isinstance(s, Exception) and s[1] is not None]
    ss_scores = [s[2] for s in all_scores if not isinstance(s, Exception) and s[2] is not None]

    aggregated = {
        "ContextPrecision": round(sum(cp_scores) / len(cp_scores), 4) if len(cp_scores) > 0 else None,
        "Faithfulness": round(sum(ff_scores) / len(ff_scores), 4) if len(ff_scores) > 0 else None,
        "SemanticSimilarity": round(sum(ss_scores) / len(ss_scores), 4) if len(ss_scores) > 0 else None,
    }

    print("\n📊 RAGAS 生成端指标：")
    for k, v in aggregated.items():
        print(f"  {k:20} = {v if v is not None else 'N/A'}")
    return aggregated

# ==================== 主函数 ====================
async def main():
    labeled_data = load_labeled_data()
    _get_model()

    llm = ChatOpenAI(
        model=os.getenv("DEEPSEEK_MODEL"),
        api_key=os.getenv("DEEPSEEK_API_KEY"),
        base_url=os.getenv("DEEPSEEK_BASE_URL"),
    )

    project_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    chroma_path = os.path.join(project_root, "chroma_db")

    from langchain_chroma import Chroma
    from app.storage.embeddings import BGEEmbeddings
    vectorstore = Chroma(
        persist_directory=chroma_path,
        embedding_function=BGEEmbeddings(),
        collection_name=os.getenv("CHROMA_COLLECTION", "black_note_all")
    )

    retriever_factory = lambda _: make_rag_retriever(vectorstore, user_id=None)

    retrieval_scores = evaluate_retrieval(retriever_factory, labeled_data)
    dataset, _ = build_ragas_dataset(labeled_data, retriever_factory, llm)
    generation_scores = await run_ragas_evaluation(dataset)

    print("\n" + "=" * 70)
    print("🎯 【简历专用最终指标】")
    print("=" * 70)
    print(f"Recall@{K:<6}          : {retrieval_scores.get('Recall@K', 'N/A')}")
    print(f"ContextPrecision     : {generation_scores.get('ContextPrecision', 'N/A')}")
    print(f"SemanticSimilarity   : {generation_scores.get('SemanticSimilarity', 'N/A')}")
    print(f"Faithfulness         : {generation_scores.get('Faithfulness', 'N/A')}")
    print("=" * 70)

if __name__ == "__main__":
    asyncio.run(main())