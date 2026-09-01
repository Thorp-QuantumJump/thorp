# Jenkins 구성

| 파일 | 용도 |
|------|------|
| `Dockerfile` | 공식 Jenkins 이미지에 docker CLI + compose 플러그인을 추가 (DooD용) |
| `docker-compose.yml` | Jenkins 컨테이너. **앱 스택과 별도 프로젝트**로 띄운다 |
| `Jenkinsfile.deploy` | 배포 파이프라인. 레포 루트 `Jenkinsfile`(빌드·테스트 CI)과 별개 Job |

설치·기동 순서는 [`../README.md`](../README.md)의 10~11단계를 따른다.

## 설계 메모

- **왜 앱 스택과 분리하나** — 같은 compose 파일에 넣으면 Jenkins가 배포하면서
  `docker compose up -d`로 자기 자신을 재시작해 파이프라인이 도중에 끊긴다.
- **왜 `${DEPLOY_DIR}`를 같은 경로로 마운트하나** — Jenkins가 `docker.sock`으로
  도커를 부리면 bind mount 경로는 *호스트 기준*으로 해석된다. Jenkins 워크스페이스
  (`/var/jenkins_home/workspace/...`)에서 compose를 실행하면 `./nginx/templates` 같은
  상대경로가 호스트에 없어서 빈 디렉토리가 마운트된다.
- **왜 포트를 `127.0.0.1`에만 묶나** — 서버는 SSH로만 접근하는 환경이고, Jenkins를
  인터넷에 노출하면 공격 표면이 커진다. UI는 SSH 터널로 본다.

## TODO

- GitLab Webhook 연동 (nginx `/jenkins` 프록시 필요 — README 11단계 참고)
- Jira 연동
- JCasC(Configuration as Code)로 Job 정의 코드화
