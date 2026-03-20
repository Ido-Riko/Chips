import anthropic
import httpx
from app.config import settings

anthropic_client = anthropic.AsyncAnthropic(api_key=settings.anthropic_api_key) if settings.anthropic_api_key else None

async def get_response(user_message: str, system_prompt: str) -> str:
    # 1. Anthropic (Primary Choice if available)
    if settings.anthropic_api_key and anthropic_client:
        message = await anthropic_client.messages.create(
            model="claude-4-5-haiku-latest",
            max_tokens=256,
            system=system_prompt,
            messages=[{"role": "user", "content": user_message}]
        )
        return message.content[0].text
        
    # 2. OpenAI
    elif settings.openai_api_key:
        async with httpx.AsyncClient() as client:
            resp = await client.post(
                "https://api.openai.com/v1/chat/completions",
                headers={"Authorization": f"Bearer {settings.openai_api_key}"},
                json={
                    "model": "gpt-5.4-nano",
                    "messages": [
                        {"role": "system", "content": system_prompt},
                        {"role": "user", "content": user_message}
                    ],
                    "max_tokens": 256
                }
            )
            resp.raise_for_status()
            return resp.json()["choices"][0]["message"]["content"]
            
    # 3. Gemini
    elif settings.gemini_api_key:
        async with httpx.AsyncClient() as client:
            resp = await client.post(
                f"https://generativelanguage.googleapis.com/v1beta/models/gemini-3-flash:generateContent?key={settings.gemini_api_key}",
                json={
                    "system_instruction": {"parts": [{"text": system_prompt}]},
                    "contents": [{"parts": [{"text": user_message}]}],
                    "generationConfig": {"maxOutputTokens": 256}
                }
            )
            resp.raise_for_status()
            return resp.json()["candidates"][0]["content"]["parts"][0]["text"]
            
    else:
        raise ValueError("No API Key configured for any LLM provider (Anthropic, OpenAI, or Gemini)")
