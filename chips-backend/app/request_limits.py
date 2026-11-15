"""Bound multipart bodies before Starlette spools uploads to disk."""
from starlette.responses import JSONResponse


class UploadLimit:
    def __init__(self, app, limit=10 * 1024 * 1024):
        self.app, self.limit = app, limit

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        total = 0
        async def bounded_receive():
            nonlocal total
            message = await receive()
            total += len(message.get("body", b""))
            if total > self.limit:
                from starlette.exceptions import HTTPException
                raise HTTPException(413, "Upload too large")
            return message
        headers = dict(scope.get("headers", []))
        try:
            too_large = int(headers.get(b"content-length", b"0")) > self.limit
        except ValueError:
            too_large = True
        if too_large:
            return await JSONResponse({"error": {"code": "upload_too_large"}}, status_code=413)(scope, receive, send)
        await self.app(scope, bounded_receive, send)
