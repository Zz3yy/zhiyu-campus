from fastapi import FastAPI
from pydantic import BaseModel, Field

app = FastAPI(title="知愈校园 AI 服务", version="0.1.0")


class RiskRequest(BaseModel):
    text: str = Field(min_length=1, max_length=4000)


class RiskResponse(BaseModel):
    risk_level: str
    reasons: list[str]


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "service": "zhiyu-ai-service"}


@app.post("/internal/ai/risk/analyze", response_model=RiskResponse)
def analyze_risk(request: RiskRequest) -> RiskResponse:
    text = request.text.lower()
    high_risk_terms = ("自杀", "自残", "伤害自己", "不想活了")
    if any(term in text for term in high_risk_terms):
        return RiskResponse(risk_level="L3", reasons=["命中高风险表达，需要进入求助流程"])
    if any(term in text for term in ("活着没意义", "不想醒来", "撑不下去")):
        return RiskResponse(risk_level="L2", reasons=["出现被动死亡或模糊危机表达，需要进一步安全澄清"])
    if any(term in text for term in ("焦虑", "压力", "失眠", "低落", "孤独")):
        return RiskResponse(risk_level="L1", reasons=["识别到明显情绪困扰或压力相关表达"])
    return RiskResponse(risk_level="L0", reasons=["暂未识别到明显危机表达"])
