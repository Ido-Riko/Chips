from pydantic_settings import BaseSettings
from pydantic import Field
from typing import Optional

class Settings(BaseSettings):
    anthropic_api_key: Optional[str] = None
    openai_api_key: Optional[str] = None
    gemini_api_key: Optional[str] = None
    drawing_vision_url: Optional[str] = None
    drawing_vision_model: Optional[str] = None
    drawing_vision_api_key: Optional[str] = None
    stt_provider: str = "local"
    stt_model: str = "small"
    stt_url: Optional[str] = None
    stt_api_key: Optional[str] = None
    stt_language: Optional[str] = None
    llm_url: Optional[str] = None
    llm_model: Optional[str] = None
    llm_max_tokens: int = Field(default=1000, ge=1, le=8192)
    llm_api_key: Optional[str] = None
    llm_reasoning_effort: Optional[str] = None
    drawing_reasoning_effort: Optional[str] = None
    database_url: str
    allow_insecure_local: bool = False
    redis_url: str

    class Config:
        env_file = ".env"

settings = Settings()
