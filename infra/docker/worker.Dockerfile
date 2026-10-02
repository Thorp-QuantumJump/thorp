# 기반 이미지와 uv 를 digest 로 고정한다(spec 004 T027). 태그는 읽는 사람을 위해 남긴 것이고 이미지는
# digest 가 정한다 — 같은 태그도 보안 패치로 다시 올라오면 다른 이미지가 된다. 멀티 아키텍처 index
# digest 라 amd64 서버와 arm64 개발기가 이 한 줄을 같이 쓴다. 자동으로 갱신되지 않으므로 보안 패치를
# 받으려면 사람이 올린다(절차는 worker/README.md "실행환경 잠금").
ARG PYTHON_IMAGE=python:3.11.16-slim-trixie@sha256:da047cb8f9d1d98e5c070f5300ba9f7274e33b8fc0e5be5ed88740aed1b95ba9

# --- build stage ---
FROM ${PYTHON_IMAGE} AS build

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
#   --frozen             uv.lock 을 고치지 않고 그대로 설치한다. pyproject.toml 의 고정 버전과 lock 이
#                        어긋나면 tests/test_dependency_pins.py 가 실패한다.
#   --no-install-project 워커 코드는 설치하지 않고 PYTHONPATH 로 읽는다. 설치하려면 빌드 백엔드
#                        (setuptools)를 받아야 하는데 그 버전은 lock 에 없다.
#   uv pip check         설치된 패키지끼리 의존 조건이 맞는지 본다. 어긋나면 이미지를 만들지 않는다.
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project \
 && uv pip check --python /app/.venv/bin/python

# 실행에 쓰지 않는 파일을 걷어 내 이미지를 줄인다. 실행 단계는 이 가상환경을 그대로 복사한다.
#   tests/test 폴더        패키지 자체의 테스트(pandas·scipy·pyarrow 등, 약 95MB)
#   .pyx .pxd .pyi .c .h 등  컴파일과 타입 검사에만 쓰는 소스·헤더·스텁
#   공유 라이브러리 디버그 정보  strip --strip-debug 로 지운다. *.libs 폴더는 auditwheel 이 patchelf 로 고친
#                           라이브러리라 strip 이 깨뜨릴 수 있어 건드리지 않는다
# 지운 뒤 실제로 쓰는 패키지를 import 해 본다. 라이브러리가 깨졌으면 여기서 이미지를 만들지 않는다.
RUN apt-get update \
 && apt-get install -y --no-install-recommends binutils \
 && find /app/.venv -depth -type d \( -name tests -o -name test \) -exec rm -rf {} + \
 && find /app/.venv -type f \( -name '*.pyx' -o -name '*.pxd' -o -name '*.pyi' -o -name '*.c' -o -name '*.cpp' \
        -o -name '*.h' -o -name '*.hpp' \) -delete \
 && find /app/.venv -type f -name '*.so*' -not -path '*.libs/*' -exec strip --strip-debug {} + \
 && /app/.venv/bin/python -c "import numpy, scipy, pandas, pyarrow.parquet, psycopg, nautilus_trader.backtest.engine"

# --- runtime stage ---
# 설치를 마친 가상환경만 옮기고 uv 는 싣지 않는다. 가상환경의 python 은 기반 이미지의
# /usr/local/bin/python3.11 을 가리키므로 두 단계는 같은 기반 이미지여야 한다.
# uv 를 RUN --mount 로 빌드에만 붙이는 방법은 BuildKit 이 필요한데, 배포(Jenkins 의 docker compose)는
# buildx 가 없어 옛 빌더로 빌드한다.
FROM ${PYTHON_IMAGE}
WORKDIR /app
COPY --from=build /app/.venv /app/.venv
COPY src ./src
ENV PYTHONPATH=/app/src \
    PATH="/app/.venv/bin:$PATH"
CMD ["python", "-m", "worker.main"]
