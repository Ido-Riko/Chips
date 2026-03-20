import anthropic
from app.config import settings

client = anthropic.AsyncAnthropic(api_key=settings.anthropic_api_key)

async def get_response(user_message: str, system_prompt: str) -> str:
    message = await client.messages.create(
        model="claude-sonnet-4-20250514",
        max_tokens=256,
        system=system_prompt,
        messages=[{"role": "user", "content": user_message}]
    )
    return message.content[0].text
