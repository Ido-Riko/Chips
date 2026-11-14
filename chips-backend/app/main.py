from fastapi import FastAPI
from app.routers import voice, health, activities, beat_saber, drawings, pantry, heart_rate

app = FastAPI(title="CHIPS Backend Phase 0")

app.include_router(activities.router)
app.include_router(beat_saber.router)
app.include_router(drawings.router)
app.include_router(pantry.router)
app.include_router(heart_rate.router)
app.include_router(voice.router)
app.include_router(health.router)

@app.get("/")
def read_root():
    return {"message": "CHIPS Backend Phase 0 is running."}
