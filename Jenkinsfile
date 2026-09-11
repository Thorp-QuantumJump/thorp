// Thorp CI 파이프라인
// Jenkins 서버 구성은 infra/jenkins/ 참고.
pipeline {
    agent any

    options {
        timestamps()
        disableConcurrentBuilds()
    }

    environment {
        // Testcontainers가 Docker에 붙지 못하면 DB·메시징 테스트가 실패가 아니라 '스킵'이 되고
        // 빌드는 초록색으로 끝난다. 마이그레이션과 Entity 매핑을 맞춰 보는 검증이 통째로 비어도
        // 알아챌 수 없다. CI에서는 그 상태를 실패로 본다 (backend RequiredDockerTest).
        THORP_REQUIRE_DOCKER = 'true'
    }

    stages {
        // 소스 브랜치만 빌드하면, 각 MR이 자기 기준으로 통과한 뒤 합쳐진 결과를 아무도 검증하지 않는다.
        // 2026-09-11에 서로 다른 두 MR이 각각 V3 마이그레이션을 추가한 채 병합되어 dev가 기동하지
        // 못한 것이 그 경우다. 이후 단계는 dev를 합친 결과 위에서 돈다.
        stage('Merge with dev') {
            steps {
                sh '''
                    set -e
                    git fetch --no-tags origin dev

                    if git merge-base --is-ancestor origin/dev HEAD; then
                        echo "이미 origin/dev 를 포함한다. 병합을 건너뛴다."
                        exit 0
                    fi

                    # --no-commit: 워크스페이스에 병합 결과만 만들고 커밋은 남기지 않는다.
                    #              post 단계에서 git merge --abort 로 되돌린다.
                    # --no-ff    : fast-forward 로 슬쩍 넘어가지 않고 실제 병합을 수행한다.
                    if ! git merge --no-commit --no-ff origin/dev; then
                        echo "origin/dev 와 병합할 수 없다. 브랜치에서 먼저 dev 를 반영하고 충돌을 해소한다." >&2
                        exit 1
                    fi
                    echo "origin/dev 를 병합한 결과로 검증한다."
                '''
            }
        }

        // 병합 결과의 마이그레이션 버전을 본다. 빈 DB에 전체 적용해 보는 테스트로는
        // 기준 브랜치보다 낮은 버전(out-of-order)을 잡지 못하므로 여기서 따로 검사한다.
        stage('Migration version') {
            steps {
                sh 'bash infra/scripts/check-migration-version.sh origin/dev'
            }
        }

        stage('Frontend') {
            when { changeset "frontend/**" }
            steps {
                dir('frontend') {
                    sh 'npm ci'
                    sh 'npm run lint'
                    sh 'npm run build'
                }
            }
        }

        stage('Backend') {
            when { changeset "backend/**" }
            steps {
                dir('backend') {
                    sh './gradlew clean test build'
                }
            }
        }

        stage('Worker') {
            when { changeset "worker/**" }
            steps {
                dir('worker') {
                    sh 'python3 -m venv .venv && . .venv/bin/activate && pip install -r requirements.txt -r requirements-dev.txt'
                    sh '. .venv/bin/activate && ruff check src tests && pytest'
                }
            }
        }

        stage('Data') {
            when { changeset "data/**" }
            steps {
                dir('data') {
                    sh 'python3 -m venv .venv && . .venv/bin/activate && pip install -r requirements.txt -r requirements-dev.txt'
                    sh '. .venv/bin/activate && ruff check src scripts && pytest'
                }
            }
        }
    }

    post {
        always {
            // 병합 상태를 되돌려 다음 빌드의 checkout 이 꼬이지 않게 한다.
            // 병합하지 않았으면 abort 할 것이 없어 실패하므로 결과를 무시한다.
            sh 'git merge --abort >/dev/null 2>&1 || true'
        }
    }
}
