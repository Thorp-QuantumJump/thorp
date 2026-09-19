# 기반 이미지와 uv 를 digest 로 고정한다(spec 004 T027). 태그는 읽는 사람을 위해 남긴 것이고 이미지는
# digest 가 정한다 — 같은 태그도 보안 패치로 다시 올라오면 다른 이미지가 된다. 멀티 아키텍처 index
# digest 라 amd64 서버와 arm64 개발기가 이 한 줄을 같이 쓴다.
FROM python:3.11.16-slim-trixie@sha256:da047cb8f9d1d98e5c070f5300ba9f7274e33b8fc0e5be5ed88740aed1b95ba9

# uv 도 digest 로 고정한다. 설치할 버전은 lock 이 정하므로 uv 버전은 설치 결과를 바꾸지 않는다.
COPY --from=ghcr.io/astral-sh/uv:0.12.0@sha256:606e70c71c852d03f611b1e56a195d08648507018a7057fab82c4974c4eae105 /uv /usr/local/bin/uv

WORKDIR /app

# UV_PYTHON_DOWNLOADS=never: 이미지의 python 을 못 찾으면 받아 오지 않고 실패한다. uv 가 받은 python 으로
#                            가상환경을 만들면 기반 이미지를 고정한 의미가 없다.
# UV_NO_CACHE              : 내려받은 패키지를 레이어에 남기지 않는다(pip 의 --no-cache-dir).
# UV_COMPILE_BYTECODE      : pip 처럼 설치할 때 .pyc 를 만들어 둔다.
ENV UV_PYTHON_DOWNLOADS=never \
    UV_NO_CACHE=1 \
    UV_COMPILE_BYTECODE=1

# 소스보다 먼저 의존성만 설치해 레이어로 굳힌다. 소스만 바뀐 빌드는 이 레이어를 재사용한다.
#   --frozen            uv.lock 을 고치지 않고 그대로 설치한다. lock 이 pyproject.toml 과 어긋났는지는
#                       CI 의 uv sync --locked 가 잡는다.
#   --no-install-project 워커 코드는 설치하지 않고 PYTHONPATH 로 읽는다. 설치하려면 빌드 백엔드
#                       (setuptools)를 받아야 하는데 그 버전은 lock 에 없다.
#   uv pip check        설치된 패키지끼리 의존 조건이 맞는지 본다. 어긋나면 이미지를 만들지 않는다.
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project \
 && uv pip check --python /app/.venv/bin/python

COPY src ./src
ENV PYTHONPATH=/app/src \
    PATH="/app/.venv/bin:$PATH"
CMD ["python", "-m", "worker.main"]
