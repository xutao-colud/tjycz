FROM python:3.11-slim
ARG DEBIAN_MIRROR=http://deb.debian.org
ARG PIP_INDEX_URL=https://pypi.org/simple
ENV PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
RUN sed -i "s|http://deb.debian.org|${DEBIAN_MIRROR}|g" /etc/apt/sources.list.d/debian.sources \
    && apt-get update \
    && apt-get install -y --no-install-recommends libgomp1 libglib2.0-0 libgl1 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt .
RUN python -m pip install -r requirements.txt
COPY server.py grouping.py model-manifest.json ./
ENV OCR_HOST=0.0.0.0 OCR_PORT=18086 OCR_CPU_THREADS=2
EXPOSE 18086
CMD ["python", "-u", "server.py"]
