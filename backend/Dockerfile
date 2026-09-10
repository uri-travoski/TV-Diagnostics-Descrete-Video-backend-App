FROM python:3.11-slim

LABEL org.opencontainers.image.source="https://github.com/uri-travoski/TV-Diagnostics-Descrete-Video-backend-App"
LABEL org.opencontainers.image.description="TV Diagnostics Descrete Video backend+App"
LABEL org.opencontainers.image.version="1.0.1"

# Install ffmpeg and curl for media probe, thumbnail generation, and healthcheck
RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Install python dependencies
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy application code
COPY app ./app

# Prepare storage directories
RUN mkdir -p /app/data/db /app/data/thumbnails /videos

ENV PYTHONUNBUFFERED=1
ENV DATA_DIR=/app/data
ENV VIDEO_DIRS=/videos
ENV PORT=8090
ENV HOST=0.0.0.0

EXPOSE 8090

HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:8090/api/v1/status || exit 1

CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090"]
