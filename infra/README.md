# infra — INFRA 파트

- `docker/` : 서비스별 Dockerfile (compose가 참조)
- `jenkins/` : Jenkins 서버/파이프라인 구성 (CI는 루트 Jenkinsfile 참고)
- `scripts/` : 운영/배포 스크립트

로컬 인프라 실행: 레포 루트에서 `docker compose up -d postgres kafka`
