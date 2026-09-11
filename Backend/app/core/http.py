"""Bound request bodies before parsing; log only server-generated metadata."""
import logging
from time import monotonic
from uuid import uuid4

from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException
from pydantic import BaseModel

logger = logging.getLogger("click.http")


class ErrorDetail(BaseModel):
    code: str
    message: str
    trace_id: str


class ErrorEnvelope(BaseModel):
    detail: str
    error: ErrorDetail


def error_response(status, code, message, trace_id, headers=None):
    return JSONResponse(status_code=status, headers=headers, content={
        "detail": message,
        "error": {"code": code, "message": message, "trace_id": trace_id},
    })


def install_error_handlers(app):
    @app.exception_handler(HTTPException)
    async def http_error(request, exc):
        codes = {401: "UNAUTHENTICATED", 403: "FORBIDDEN", 404: "NOT_FOUND",
                 409: "CONFLICT", 413: "PAYLOAD_TOO_LARGE", 422: "INVALID_REQUEST", 429: "RATE_LIMITED"}
        # Exception details may contain provider payloads or database identifiers.
        messages = {401: "Authentication required.", 403: "Access denied.",
                    404: "Resource not found.", 409: "Request conflicts with existing data.",
                    413: "Request body too large.", 422: "Invalid request.",
                    429: "Too many requests. Try again later."}
        return error_response(exc.status_code, codes.get(exc.status_code, "HTTP_ERROR"),
                              messages.get(exc.status_code, "Request failed."),
                              request.scope.get("trace_id"), exc.headers)

    @app.exception_handler(RequestValidationError)
    async def validation_error(request, exc):
        return error_response(422, "INVALID_REQUEST", "Invalid request.", request.scope.get("trace_id"))


class HttpBoundary:
    def __init__(self, app, max_body_bytes=1_048_576):
        self.app = app
        self.max_body_bytes = max_body_bytes

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        trace_id = uuid4().hex
        scope["trace_id"] = trace_id
        started = monotonic()
        response_started = False
        status = 500

        async def traced_send(message):
            nonlocal response_started, status
            if message["type"] == "http.response.start":
                response_started = True
                status = message["status"]
                message = {**message, "headers": [*message.get("headers", []), (b"x-request-id", trace_id.encode())]}
            await send(message)

        async def reject(status_code, code, message):
            await error_response(status_code, code, message, trace_id)(scope, receive, traced_send)

        try:
            headers = dict(scope.get("headers", []))
            try:
                declared = int(headers.get(b"content-length", b"0"))
            except ValueError:
                return await reject(422, "INVALID_REQUEST", "Invalid request.")
            if declared < 0:
                return await reject(422, "INVALID_REQUEST", "Invalid request.")
            if declared > self.max_body_bytes:
                return await reject(413, "PAYLOAD_TOO_LARGE", "Request body too large.")
            chunks = []
            size = 0
            while True:
                message = await receive()
                if message["type"] == "http.disconnect":
                    return
                chunk = message.get("body", b"")
                size += len(chunk)
                if size > self.max_body_bytes:
                    return await reject(413, "PAYLOAD_TOO_LARGE", "Request body too large.")
                chunks.append(chunk)
                if not message.get("more_body", False):
                    break
            delivered = False

            async def replay():
                nonlocal delivered
                if not delivered:
                    delivered = True
                    return {"type": "http.request", "body": b"".join(chunks), "more_body": False}
                return await receive()

            await self.app(scope, replay, traced_send)
        except Exception:
            # Do not log exception strings, request paths, bodies, or credentials.
            if response_started:
                raise
            await reject(500, "INTERNAL_ERROR", "Internal server error.")
        finally:
            logger.info("request trace_id=%s status=%s duration_ms=%d", trace_id, status,
                        int((monotonic() - started) * 1000))
