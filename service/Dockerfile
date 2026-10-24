FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 MEBOT_DATABASE_PATH=/data/mebot.sqlite3
WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt \
    && useradd --uid 10001 --create-home mebot \
    && mkdir /data && chown mebot:mebot /data
COPY mebot_service ./mebot_service
USER 10001:10001
EXPOSE 8787
CMD ["uvicorn", "mebot_service.app:create_app", "--factory", "--host", "0.0.0.0", "--port", "8787", "--workers", "1", "--no-access-log"]
