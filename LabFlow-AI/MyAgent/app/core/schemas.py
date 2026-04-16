#这段代码服务于 FastAPI（后端框架），它是用来接收前端（如网页、Java端）发来的 HTTP 请求的
#核心技术点：数据序列化与验证 (Data Validation)
# 在全栈开发中，前后端分离最怕的就是“你传的数据格式不对”。Pydantic (BaseModel) 就是专门解决这个问题的，它能自动把前端的 JSON 转换成 Python 对象，并做严格的类型检查。
from pydantic import BaseModel
from typing import TypedDict

#这是 FastAPI 的**“门神/保安”**。当你的前端给 /ai/chat 发送一个 POST 请求时，带了一串 JSON 数据。FastAPI 会拿着 ChatRequest 这个保安去核对：
# “你传的数据里有没有 question 这个字段？它是字符串吗？” -> 如果不是，保安直接把请求踢回去（报 422 错误），根本不会让错误数据进入你的系统。
# “你没传 session_id？” -> 没关系，保安会自动给它贴上 "default" 的默认标签。
class ChatRequest(BaseModel):
    question: str  #如果前端传过来 {"question": 123}，Pydantic 会立刻报错大喊：“不行！规则写了 question 必须是字符串（str），你传了个数字！”
    session_id: str = "default"


class AgentRequest(BaseModel):
    task: str
    reset: bool = False

#仅仅是给程序员（或者 IDE 编辑器）看的：明确告诉你，这个字典里必须有一个叫 user_id 的字符串
class AgentContext(TypedDict):
    user_id: str
    some_other_info: str | None