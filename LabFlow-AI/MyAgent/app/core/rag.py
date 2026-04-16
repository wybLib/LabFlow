"""
app/core/rag.py
负责 RAG 检索逻辑。
混合检索（Hybrid Search） + 重排（Rerank）

改动说明：
  - make_rag_retriever：支持“全站全局检索”与“私人检索”的动态切换。
  - 动态生成缓存 Key，防止全局缓存和私人缓存串号。
"""

import os
from typing import List

from dotenv import load_dotenv
from langchain_core.documents import Document
from langchain_chroma import Chroma
from langchain_community.retrievers import BM25Retriever
from langchain_classic.retrievers import EnsembleRetriever
from flashrank import Ranker, RerankRequest
import jieba

load_dotenv()

# 这是一个内存大字典，用来存已经建好的检索器，防止重复干活
_retriever_cache: dict = {}   # key = collection_name:user_id (或 global)，value = retriever
#BM25 检索器在初始化时，需要把数据库里所有的文章都拿出来统计词频，这非常耗时。_retriever_cache 就像是一个储物柜，第一次建好管理员（检索器）后，就把他锁进柜子里。
# 下次同一个用户再来查，直接从柜子里把他拉出来用，实现了接口的“秒级响应”

def make_rag_retriever(vectorstore: Chroma, user_id: str = None):
    """
    构建混合检索器，结果缓存在模块变量里。  vectorstore: 已经连上的 Chroma 数据库对象
    如果不传 user_id，则构建面向全站的全局检索器。
    在企业级 SaaS 系统里，绝对不能让 A 用户搜到 B 用户的隐私数据。在向量数据库（Chroma）中，把所有人的数据放在一个大库里，通过 Metadata（元数据，也就是这里的 user_id）做硬过滤（Hard Filter）。这一步在底层就卡死了越权的可能。
    """
    # 🚀 核心修改 1：动态决定缓存 Key
    cache_key = f"{vectorstore._collection.name}:{user_id if user_id else 'GLOBAL'}"

    if cache_key in _retriever_cache: #如果造好了，直接拿出来用，后面的代码全都不用跑了（秒回！）
        return _retriever_cache[cache_key]

    # 🚀 核心修改 2：动态构建 Chroma 的过滤条件
    if user_id:
        chroma_filter = {
            "$and": [
                {"user_id": {"$eq": str(user_id)}}, # 👈 必须是这个人的笔记
                {"is_deleted": {"$eq": 0}},  # 👈 必须是没有被删除的
            ]
        }
        get_where = {"user_id": str(user_id)} # get_where 是后面给 BM25 捞数据用的简易条件
    else:
        # 全站检索，只过滤未删除的
        chroma_filter = {"is_deleted": {"$eq": 0}} # 只要求没被删就行
        get_where = None

    #双路检索（向量 + 关键词）
    """
    知识点：双路召回 (Dual-path Retrieval)
为什么有了高级的“向量检索”，还要加古老的“BM25 检索”？
向量检索：擅长懂你的“言外之意”。你搜“怎么做红烧肉”，它能帮你找到“猪肉的烹饪技巧”。但它有个致命弱点：对专有名词、缩写、单字极其不敏感。如果你搜“VUE3”，它可能会找出一堆 React 的文章。
BM25 (字面检索)：传统的搜索引擎算法。它只认字，不认意思。你搜“VUE3”，它就死死盯住文章里必须出现“VUE3”这个词。
Jieba 分词：BM25 默认是按英文的“空格”分词的。中文没有空格，如果不加 jieba.cut，它会把一整句话当成一个词，彻底失效。你加上了 jieba，证明你考虑到了中文 NLP 的特殊性。
    """
    # 1. 向量检索器
    vector_retriever = vectorstore.as_retriever(
        search_type="similarity", # 告诉它用“向量相似度”来找文章
        search_kwargs={
            "k": 20, # 一口气捞 20 篇
            "filter": chroma_filter
        },
    )

    # 2. BM25 文本检索器构建
    if get_where: # 如果有特定用户，就只把这个用户的文章全捞出来
        result = vectorstore.get(where=get_where)
    else: # 否则把库里所有的文章全捞出来
        result = vectorstore.get() # 不带 where 就是拿全库数据

    documents = [ #把刚捞出来的数据，打包成 LangChain 认识的 Document 格式
        Document(page_content=text or "", metadata=meta or {}) # 对每一次循环，造一个 Document 对象，里面装文本(page_content)和附加信息(metadata)
        for text, meta in zip(  # zip 的作用是像拉链一样，把“文本列表”和“附加信息列表”一对一缝合起来，同时遍历
            result.get("documents", []),
            result.get("metadatas", []),
        )
        if (meta or {}).get("is_deleted", 0) == 0 # 最后的 if 是一个保险过滤：只把 is_deleted 为 0 的加进结果里
    ]

    # 🚀 核心修改 3：防御性兜底。如果库里一篇笔记都没有，直接返回空向量检索器，否则 BM25 会报错
    if not documents:
        print(f"⚠️ [{cache_key}] 尚无数据，仅返回向量检索器兜底")
        _retriever_cache[cache_key] = vector_retriever # 记录到缓存
        return vector_retriever

    # 2. 关键词检索器 BM25 (找“字面一样”的)
    def chinese_preprocess(text: str): #中文分词函数
        return list(jieba.cut(text)) # 👈 重点：用 jieba 分词

    bm25_retriever = BM25Retriever.from_documents(
        documents,
        k=20,
        preprocess_func=chinese_preprocess,
    )

    # 3. 融合检索器
    """
    EnsembleRetriever 底层使用的是 RRF 算法。因为 BM25 和向量检索的“打分标准”完全不同（就像高考文科和理科的分数没法直接加），RRF 算法只看它们排名的名次。
这里你给 BM25 分配了 0.48，给向量分配了 0.52 的权重。相当于告诉管理员：“两边找出来的文章，我都要，但稍微偏向一点向量检索的结果。”
    """
    retriever = EnsembleRetriever(
        retrievers=[bm25_retriever, vector_retriever],
        weights=[0.48, 0.52],  # 👈 权重分配
        c=60, # 这是算法内部的一个惩罚参数（防止某一个小弟太极端），60是业界标准，不用管
    )

    _retriever_cache[cache_key] = retriever   # 存入缓存
    print(f"✅ 混合检索器已构建并缓存 [{cache_key}]（共 {len(documents)} 个文档）")
    return retriever

def rerank_docs(query: str, docs: List[Document], top_n: int = 6) -> List[Document]:
    """FlashRank 重排序（保持不变） 检索器虽然一口气捞了 20 篇出来，但难免鱼龙混杂。接下来就需要重排模型上场了"""
    """
    # 【解释】接收三个参数：
    # 1. query: 用户问的问题（字符串）
    # 2. docs: 刚才检索器找回来的文章列表（List装的Document）
    # 3. top_n: 最后要留几篇（默认留前 6 篇）
    前面的向量模型（如 bge-m3）是双塔模型：问题过一遍模型，文章过一遍模型，然后比对两者的向量距离。速度极快，但比较粗糙。
Reranker 是交叉编码器（Cross-encoder）：它会把“问题 + 这篇文章”同时喂进大模型里，让模型逐字逐句地对比它们的相关性。速度极慢（所以只敢给它喂初步筛选出的 20 篇），但准得可怕。经过它重新排序并截取 Top 6，喂给最终聊天大模型的上下文质量将得到质的飞跃
    """
    if not docs:
        return []

    # passages 提取出纯文本列表，并且只保留长度大于等于 10 个字符的内容（太短的可能是垃圾数据，没法打分）
    passages = [doc.page_content.strip() for doc in docs if len(doc.page_content.strip()) >= 10]
    # valid_docs 保留完整的 Document 对象，同样过滤掉太短的
    valid_docs = [doc for doc in docs if len(doc.page_content.strip()) >= 10]

    if not passages: # 如果过滤完，发现一篇合格的都没有，那就只能硬着头皮把原数据截断前 6 个退回去了
        return docs[:top_n]

    try:
        ranker = Ranker(model_name="BAAI/bge-reranker-v2-m3") # 加载 BAAI 的大杀器：bge-reranker
        results = ranker.rerank(RerankRequest(query=query.strip(), passages=passages)) # 让重排模型对着这 20 篇文章和用户的问题，重新认真打分

        # 根据新打的分数，从高到低排序，只取前 6 篇（top_n=6）
        #最终 sorted_indices 会得到一个排名后的“序号列表”，比如 [3, 0, 1, 2]（代表第3篇分数最高）
        sorted_indices = sorted(
            range(len(results)), #range(len(results)) 产生 0, 1, 2, 3... 这样的序号
            key=lambda i: float(results[i].get("score", 0)), #排名的依据，是去 results 里找第 i 个元素的 "score" 分数。
            reverse=True, #分数最高的排前面  倒序
        )
        # 根据排好的序号，把对应的 Document 从 valid_docs 里提出来，并切片留前 top_n(6) 篇
        return [valid_docs[i] for i in sorted_indices[:top_n] if i < len(valid_docs)]

    except Exception:
        return valid_docs[:top_n]