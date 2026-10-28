"""RFC 9457 problem details, the error format of every platform API."""

from fastapi import Request
from fastapi.responses import JSONResponse


class ProblemError(Exception):
    def __init__(self, status: int, title: str, detail: str | None = None, headers: dict[str, str] | None = None):
        super().__init__(detail or title)
        self.status = status
        self.title = title
        self.detail = detail
        self.headers = headers


def problem_response(request: Request, error: ProblemError) -> JSONResponse:
    body = {"type": "about:blank", "title": error.title, "status": error.status, "instance": request.url.path}
    if error.detail:
        body["detail"] = error.detail
    return JSONResponse(body, status_code=error.status, media_type="application/problem+json", headers=error.headers)


def not_found(detail: str) -> ProblemError:
    return ProblemError(404, "Not Found", detail)
