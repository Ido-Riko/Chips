from sqlalchemy import BigInteger, Column, LargeBinary, String, Text
from app.database import Base


class Drawing(Base):
    __tablename__ = "drawings"
    owner_id = Column(String(36), primary_key=True)
    id = Column(String(36), primary_key=True)
    sha256 = Column(String(64), nullable=False)
    image = Column(LargeBinary, nullable=True)
    goal = Column(Text, nullable=False, default="")
    created_at = Column(BigInteger, nullable=False)
    feedback = Column(Text, nullable=True)
    analyzed_at = Column(BigInteger, nullable=True)
    model = Column(String(200), nullable=True)
    deleted = Column(BigInteger, nullable=False, default=0)
