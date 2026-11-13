from sqlalchemy import BigInteger, Column, String, Text
from app.database import Base


class PantryTransaction(Base):
    __tablename__ = "pantry_transactions"
    owner_id = Column(String(36), primary_key=True)
    id = Column(String(36), primary_key=True)
    created_at = Column(BigInteger, nullable=False)
    payload = Column(Text, nullable=False)
