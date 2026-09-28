# data 파이프라인 일회성 배치 이미지 (S15P21E205-446).
# 등록표 등록·경제지표 적재·경제환경 사전 계산을 운영 호스트에서 `docker compose run --rm data-batch ...` 로 돌린다.
# 상주 서비스가 아니다 — compose 의 `batch` 프로필에만 있고 `up` 으로 뜨지 않는다.
#
# 기반 이미지는 worker.Dockerfile 과 같은 digest 로 고정한다(spec 004 T027). 보안 패치를 받으려면 두 파일을 함께 올린다.
ARG PYTHON_IMAGE=python:3.11.16-slim-trixie@sha256:da047cb8f9d1d98e5c070f5300ba9f7274e33b8fc0e5be5ed88740aed1b95ba9

FROM ${PYTHON_IMAGE}
WORKDIR /app

# data 파트는 아직 lock 이 없다(uv.lock 에 의존성이 비어 있다). requirements.txt 의 하한으로 설치하므로
# 이미지를 다시 빌드하면 패키지 버전이 오를 수 있다. 설치 뒤 pip check 로 의존 조건만 확인한다.
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt \
 && pip check

# 스크립트는 parents[1] 로 data 뿌리를 찾는다 — /app 아래에 config·src·scripts 를 같은 모양으로 둔다.
COPY config ./config
COPY src ./src
COPY scripts ./scripts
ENV PYTHONPATH=/app/src:/app/scripts \
    PYTHONUNBUFFERED=1
# 명령을 기본으로 정하지 않는다. 무엇을 돌릴지는 실행하는 사람이 매번 적는다(운영 절차 문서).
CMD ["python", "-c", "print('실행할 스크립트를 지정한다: docker compose run --rm data-batch python scripts/<name>.py ...')"]
