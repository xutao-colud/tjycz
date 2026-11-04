FROM python:3.11-slim
ENV PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgomp1 libglib2.0-0 libgl1 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt .
RUN python -m pip install -r requirements.txt
COPY server.py grouping.py model-manifest.json ./
ENV OCR_HOST=0.0.0.0 OCR_PORT=18086 OCR_CPU_THREADS=2
EXPOSE 18086
CMD ["python", "-u", "server.py"]
