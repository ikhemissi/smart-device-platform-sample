# Anomaly detector (M7): Python 3.13, dependencies locked by uv, non-root; one detection cycle per run.
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
# pip is not needed at runtime; removing it also removes the libraries it vendors from the shipped image.
RUN python -m pip uninstall --yes --quiet pip && useradd --uid 10001 --create-home detector
COPY --from=build --chown=detector /app/.venv /app/.venv
ENV PATH="/app/.venv/bin:$PATH" PYTHONUNBUFFERED=1
USER 10001
CMD ["anomaly-detector"]
