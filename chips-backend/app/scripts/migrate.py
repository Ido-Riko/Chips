"""Apply additive SQL migrations in one transaction; existing voice data is untouched."""
import asyncio
from pathlib import Path
from sqlalchemy import text
from app.database import engine


async def main():
    async with engine.begin() as conn:
        sql = (Path(__file__).resolve().parents[2] / "migrations/001_activity_sync.sql").read_text()
        for statement in sql.split(";"):
            if statement.strip():
                await conn.execute(text(statement))
    await engine.dispose()


if __name__ == "__main__":
    asyncio.run(main())
