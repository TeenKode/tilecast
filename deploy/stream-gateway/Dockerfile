FROM node:22-alpine AS hls
RUN mkdir -p /hls && npm install --prefix /hls --no-audit --no-fund hls.js@1.6.0

FROM python:3.12-alpine
RUN apk add --no-cache ffmpeg ca-certificates \
    && pip install --no-cache-dir "cryptography>=44,<47"
WORKDIR /opt/tilecast-stream-gateway
COPY stream_gateway.py player.html ./
COPY --from=hls /hls/node_modules/hls.js/dist/hls.min.js ./hls.min.js
ENV PYTHONUNBUFFERED=1 STREAM_GATEWAY_PORT=8890
EXPOSE 8890
USER nobody
CMD ["python", "stream_gateway.py"]
