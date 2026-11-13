from sqlalchemy import BigInteger, Column, String, Text
from app.database import Base


class BeatSaberConnection(Base):
    __tablename__ = "beat_saber_connections"
    owner_id = Column(String(36), primary_key=True)
    player_id = Column(String(25), nullable=False)
    generation = Column(String(36), nullable=False)
    updated_at = Column(BigInteger, nullable=False, default=0)
    snapshot = Column(Text, nullable=True)


class BeatSaverCache(Base):
    __tablename__ = "beat_saver_cache"
    hash = Column(String(40), primary_key=True)
    fetched_at = Column(BigInteger, nullable=False)
    payload = Column(Text, nullable=False)
