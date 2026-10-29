# AI device analyst (M6): Python 3.13, dependencies locked by uv, non-root.
FROM python:3.13-slim AS build
COPY --from=ghcr.io/astral-sh/uv:0.13.5 /uv /usr/local/bin/uv
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_PYTHON_DOWNLOADS=never
WORKDIR /app
COPY pyproject.toml uv.lock ./
RUN uv sync --locked --no-dev --no-install-project
COPY README.md ./
COPY src ./src
RUN uv sync --locked --no-dev --no-editable

FROM python:3.13-slim
# pip is not needed at runtime (dependencies come from the build stage's virtual environment); removing it also
# removes the libraries it vendors from the shipped image.
RUN python -m pip uninstall --yes --quiet pip && useradd --uid 10001 --create-home analyst
COPY --from=build --chown=analyst /app/.venv /app/.venv
ENV PATH="/app/.venv/bin:$PATH" PYTHONUNBUFFERED=1
USER 10001
EXPOSE 8080
CMD ["device-analyst"]
