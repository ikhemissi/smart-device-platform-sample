# AI device analyst (M6): Python 3.12, dependencies locked by uv, non-root.
# Built on Azure Linux and run on its distroless Python image: no shell, no package manager, and the same interpreter
# (/usr/bin/python3) in both stages, so the virtual environment built here runs as is.
FROM mcr.microsoft.com/azurelinux/base/python:3.12 AS build
COPY --from=ghcr.io/astral-sh/uv:0.13.5 /uv /usr/local/bin/uv
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_PYTHON_DOWNLOADS=never UV_PYTHON=/usr/bin/python3
WORKDIR /app
COPY pyproject.toml uv.lock ./
RUN uv sync --locked --no-dev --no-install-project
COPY README.md ./
COPY src ./src
RUN uv sync --locked --no-dev --no-editable

# The nonroot variant runs as uid 65532.
FROM mcr.microsoft.com/azurelinux/distroless/python:3.12-nonroot
COPY --from=build /app/.venv /app/.venv
ENV PATH="/app/.venv/bin:$PATH" PYTHONUNBUFFERED=1
EXPOSE 8080
CMD ["device-analyst"]
