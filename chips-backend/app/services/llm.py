"""Explicit compatible endpoint/model selection; never implicit API-key fallback."""
import json
import httpx
from fastapi import HTTPException
from app.config import settings


async def get_response(user_message: str, system_prompt: str, history: list | None = None) -> str:
    if not settings.llm_url or not settings.llm_model:
        raise HTTPException(503, detail={"code": "llm_unavailable"})
    headers = {"Authorization": f"Bearer {settings.llm_api_key}"} if settings.llm_api_key else {}
    try:
        async with httpx.AsyncClient(timeout=70, follow_redirects=False) as client:
            async with client.stream("POST", settings.llm_url, headers=headers, json={
                "model": settings.llm_model, "stream": False, "max_tokens": settings.llm_max_tokens,
                **({"reasoning_effort": settings.llm_reasoning_effort} if settings.llm_reasoning_effort else {}),
                "messages": [{"role": "system", "content": system_prompt},
                             *(history or []), {"role": "user", "content": user_message}],
            }) as response:
                response.raise_for_status()
                body = bytearray()
                async for chunk in response.aiter_bytes():
                    body.extend(chunk)
                    if len(body) > 256 * 1024:
                        raise ValueError()
        result = json.loads(body)["choices"][0]["message"]["content"]
        if not isinstance(result, str) or not result.strip() or len(result) > 64000 or len(result.encode("utf-8")) > 200 * 1024:
            raise ValueError()
        return result.strip()
    except (httpx.HTTPError, ValueError, KeyError, TypeError, IndexError):
        raise HTTPException(503, detail={"code": "llm_unavailable"})
