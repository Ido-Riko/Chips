"""Apply additive SQL migrations in one transaction; existing voice data is untouched."""
import asyncio
from pathlib import Path
from sqlalchemy import text
from app.database import engine


async def main():
    async with engine.begin() as conn:
        migrations = Path(__file__).resolve().parents[2] / "migrations"
        for path in sorted(migrations.glob("[0-9][0-9][0-9]_*.sql")):
            for statement in path.read_text().split(";"):
                if statement.strip():
                    await conn.execute(text(statement))
    await engine.dispose()


if __name__ == "__main__":
    asyncio.run(main())
