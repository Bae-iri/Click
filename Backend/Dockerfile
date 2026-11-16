FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 GOAL_CLASSIFIER_PROVIDER=jev CLICK_COUPANG_BROWSE_ENABLED=1
WORKDIR /srv/Click
COPY Backend/pyproject.toml Backend/pyproject.toml
COPY Backend/app Backend/app
RUN python -m pip install --no-cache-dir ./Backend
COPY Backend Backend
COPY AI-Agent AI-Agent
RUN useradd --create-home click && chown -R click:click /srv/Click
USER click
WORKDIR /srv/Click/Backend
EXPOSE 8000
CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1", "--no-access-log", "--no-proxy-headers", "--log-config", "logging.json"]
