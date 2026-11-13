from pydantic_settings import BaseSettings
from typing import Optional

class Settings(BaseSettings):
    anthropic_api_key: Optional[str] = None
    openai_api_key: Optional[str] = None
    gemini_api_key: Optional[str] = None
    drawing_vision_url: Optional[str] = None
    drawing_vision_model: Optional[str] = None
    drawing_vision_api_key: Optional[str] = None
    database_url: str
    allow_insecure_local: bool = False
    redis_url: str

    class Config:
        env_file = ".env"

settings = Settings()
