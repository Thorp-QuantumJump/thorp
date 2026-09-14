// Thorp CI 파이프라인
// Jenkins 서버 구성은 infra/jenkins/ 참고.
//
// 파트별 스테이지를 설치·검사·테스트·빌드로 나눈 이유는, Jenkins의 Pipeline Overview에서
// 실패한 단계만 눌러 그 구간의 로그를 열어볼 수 있게 하기 위함이다. 한 스테이지에 여러
// 명령을 묶으면 화면에는 "파트가 실패했다"까지만 나타나고, 어느 명령에서 실패했는지
// 확인하려면 콘솔 로그 전체를 읽어야 한다.
pipeline {
    agent any

    options {
        timestamps()
        disableConcurrentBuilds()
        // gradle·ruff·pytest 출력의 ANSI 색상을 콘솔에 그대로 표시한다.
        // ansicolor 플러그인이 설치되어 있어야 하며, 없으면 파이프라인 자체가 기동에
        // 실패한다. 플러그인 목록은 infra/jenkins/Dockerfile 에 있다.
        ansiColor('xterm')
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
            stages {
                stage('FE Install') {
                    steps {
                        dir('frontend') {
                            sh 'npm ci'
                        }
                    }
                }

                stage('FE Lint') {
                    steps {
                        dir('frontend') {
                            sh 'npm run lint'
                        }
                    }
                }

                stage('FE Test') {
                    steps {
                        dir('frontend') {
                            // npm run test 는 vitest(test:dom)와 node --test(test:legacy)를
                            // 차례로 돌린다. 둘 다 콘솔에 결과를 출력하면서 junit 리포터로 XML 도
                            // 함께 남기므로, 실패 로그를 스테이지에서 바로 읽으면서 아래 post
                            // 블록에서 실패한 테스트만 따로 모을 수 있다.
                            sh 'npm run test'
                        }
                    }
                    post {
                        always {
                            // post 블록은 dir('frontend') 바깥이므로 워크스페이스 루트
                            // 기준 경로를 쓴다.
                            // vitest.xml(test:dom)과 node-test.xml(test:legacy)을 함께 모은다.
                            junit(
                                allowEmptyResults: true,
                                testResults: 'frontend/build/test-results/*.xml'
                            )
                        }
                    }
                }

                stage('FE Build') {
                    steps {
                        dir('frontend') {
                            sh 'npm run build'
                        }
                    }
                }
            }
        }

        stage('Backend') {
            when { changeset "backend/**" }
            stages {
                stage('BE Format') {
                    steps {
                        dir('backend') {
                            // clean 은 백엔드 단계 전체에서 여기 한 번만 수행한다.
                            // 뒤따르는 스테이지가 같은 build 디렉토리를 이어서 쓴다.
                            sh './gradlew clean spotlessCheck'
                        }
                    }
                }

                stage('BE Test') {
                    steps {
                        dir('backend') {
                            sh './gradlew test'
                        }
                    }
                    post {
                        always {
                            // 실패한 테스트만 따로 확인할 수 있도록 결과를 수집한다.
                            // post 블록은 dir('backend') 바깥이므로 워크스페이스 루트
                            // 기준 경로를 쓴다.
                            junit(
                                allowEmptyResults: true,
                                testResults: 'backend/build/test-results/test/*.xml'
                            )
                        }
                    }
                }

                stage('BE Build') {
                    steps {
                        dir('backend') {
                            // test 와 spotlessCheck 는 앞 단계에서 이미 수행했다.
                            // 빼지 않으면 같은 검사가 이 스테이지에서 한 번 더 돌아,
                            // 실패 지점이 어느 스테이지인지 다시 모호해진다.
                            sh './gradlew build -x test -x spotlessCheck'
                        }
                    }
                }
            }
        }

        stage('Worker') {
            when { changeset "worker/**" }
            stages {
                stage('Worker Setup') {
                    steps {
                        dir('worker') {
                            sh 'python3 -m venv .venv'
                            sh '. .venv/bin/activate && pip install -r requirements.txt -r requirements-dev.txt'
                        }
                    }
                }

                stage('Worker Lint') {
                    steps {
                        dir('worker') {
                            sh '. .venv/bin/activate && ruff check src tests'
                        }
                    }
                }

                stage('Worker Test') {
                    steps {
                        dir('worker') {
                            sh '. .venv/bin/activate && pytest --junitxml=build/test-results/pytest.xml'
                        }
                    }
                    post {
                        always {
                            junit(
                                allowEmptyResults: true,
                                testResults: 'worker/build/test-results/pytest.xml'
                            )
                        }
                    }
                }
            }
        }

        stage('Data') {
            when { changeset "data/**" }
            stages {
                stage('Data Setup') {
                    steps {
                        dir('data') {
                            sh 'python3 -m venv .venv'
                            sh '. .venv/bin/activate && pip install -r requirements.txt -r requirements-dev.txt'
                        }
                    }
                }

                stage('Data Lint') {
                    steps {
                        dir('data') {
                            sh '. .venv/bin/activate && ruff check src scripts tests'
                        }
                    }
                }

                stage('Data Test') {
                    steps {
                        dir('data') {
sh '. .venv/bin/activate && pytest --junitxml=build/test-results/pytest.xml'
                        }
                    }
                    post {
                        always {
                            junit(
                                allowEmptyResults: true,
                                testResults: 'data/build/test-results/pytest.xml'
                            )
                        }
                    }
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
