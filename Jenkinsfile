// Thorp CI 파이프라인
// Jenkins 서버 구성은 infra/jenkins/ 참고.
//
// 파트별 스테이지를 설치·검사·테스트·빌드로 나눈 이유는, Jenkins의 Pipeline Overview에서
// 실패한 단계만 눌러 그 구간의 로그를 열어볼 수 있게 하기 위함이다. 한 스테이지에 여러
// 명령을 묶으면 화면에는 "파트가 실패했다"까지만 나타나고, 어느 명령에서 실패했는지
// 확인하려면 콘솔 로그 전체를 읽어야 한다.
// 알림 메시지의 머리말. MR 로 트리거되면 어느 MR 인지가 함께 있어야 쓸모가 있다.
// 그 밖의 경로(수동 실행 등)로 들어오면 값이 없으므로 Job 이름으로 물러선다.
def ciSubject() {
    if (env.gitlabMergeRequestIid) {
        return "MR !${env.gitlabMergeRequestIid} (${env.gitlabSourceBranch} -> ${env.gitlabTargetBranch})"
    }
    return env.JOB_NAME
}

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

        // Merge with dev 스테이지의 git fetch 가 쓸 자격. Job 설정의 Credentials 와 같은 것을
        // 가리킨다. Job 에서 credential 을 바꾸면 이 값도 함께 고쳐야 한다.
        GIT_CREDENTIALS_ID = 'gitlab-deploy-token'

        // 파트 스테이지는 평소 changeset 으로 걸러진다. 바뀌지 않은 파트를 매번 빌드하면
        // 시간만 들기 때문이다. 그런데 CI 자체를 손볼 때는 전체가 도는 모습을 봐야 한다.
        // 그럴 때 커밋 메시지에 [ci-all] 을 넣으면 걸러내기를 건너뛰고 모두 실행한다.
        //   git commit --allow-empty -m "chore: CI 전체 확인 [ci-all]"
        CI_RUN_ALL = sh(returnStdout: true, script: 'git log -1 --pretty=%B | grep -qF "[ci-all]" && echo true || echo false').trim()
    }

    stages {
        // 소스 브랜치만 빌드하면, 각 MR이 자기 기준으로 통과한 뒤 합쳐진 결과를 아무도 검증하지 않는다.
        // 2026-09-11에 서로 다른 두 MR이 각각 V3 마이그레이션을 추가한 채 병합되어 dev가 기동하지
        // 못한 것이 그 경우다. 이후 단계는 dev를 합친 결과 위에서 돈다.
        stage('Merge with dev') {
            steps {
                // checkout 스텝은 GIT_ASKPASS 로 자격을 주입하지만, sh 스텝은 별개 프로세스라
                // 그것을 물려받지 못한다. 자격 없이 fetch 하면 사용자명을 묻다가
                // "could not read Username for ..." 로 끝난다.
                withCredentials([usernamePassword(
                    credentialsId: env.GIT_CREDENTIALS_ID,
                    usernameVariable: 'GIT_USERNAME',
                    passwordVariable: 'GIT_PASSWORD'
                )]) {
                    sh '''
                        set -e

                        # 토큰을 remote URL 에 넣으면 .git/config 와 콘솔 로그에 남는다.
                        # 이 명령에서만 살아 있는 credential helper 로 넘겨 흔적을 남기지 않는다.
                        git -c credential.helper='!f() { echo username=$GIT_USERNAME; echo password=$GIT_PASSWORD; }; f' fetch --no-tags origin dev

                        if git merge-base --is-ancestor origin/dev HEAD; then
                            echo "이미 origin/dev 를 포함한다. 병합을 건너뛴다."
                            exit 0
                        fi

                        # --no-commit: 워크스페이스에 병합 결과만 만들고 커밋은 남기지 않는다.
                        #              post 단계에서 git merge --abort 로 되돌린다.
                        # --no-ff    : fast-forward 로 슬쩍 넘어가지 않고 실제 병합을 수행한다.
                        # -c user.*  : Jenkins 컨테이너에는 git identity 가 없다. merge 는
                        #              --no-commit 이어도 커밋을 만들 수 있는 상태로 들어가므로
                        #              committer 를 요구한다. 전역 설정을 건드리지 않고 이 명령에만 준다.
                        if ! git -c user.email=ci@thorp.local -c user.name=thorp-ci merge --no-commit --no-ff origin/dev; then
                            echo "origin/dev 와 병합할 수 없다. 브랜치에서 먼저 dev 를 반영하고 충돌을 해소한다." >&2
                            exit 1
                        fi
                        echo "origin/dev 를 병합한 결과로 검증한다."
                    '''
                }
            }

            // GitLab MR 화면에 이 파트의 결과를 되돌려준다. 여섯 파트가 각각 한 줄로 나열되어,
            // Jenkins 에 들어가지 않고도 어느 파트가 깨졌는지 MR 에서 바로 보인다.
            // 보고가 쌓이면 GitLab 의 Merge checks 가 빨간불일 때 머지 버튼을 막는다.
            //
            // when 조건에 걸려 건너뛴 파트는 post 가 돌지 않아 아예 표시되지 않는다.
            // 돌지 않은 것을 성공으로 보고하는 것보다 낫다.
            post {
                success { updateGitlabCommitStatus(name: 'Merge with dev', state: 'success') }
                failure { updateGitlabCommitStatus(name: 'Merge with dev', state: 'failed') }
                unstable { updateGitlabCommitStatus(name: 'Merge with dev', state: 'failed') }
            }
        }

        stage('Migration version') {
            steps {
                sh 'bash infra/scripts/check-migration-version.sh origin/dev'
            }

            post {
                success { updateGitlabCommitStatus(name: 'Migration version', state: 'success') }
                failure { updateGitlabCommitStatus(name: 'Migration version', state: 'failed') }
                unstable { updateGitlabCommitStatus(name: 'Migration version', state: 'failed') }
            }
        }

        stage('Frontend') {
            when {
                anyOf {
                    changeset "frontend/**"
                    environment name: 'CI_RUN_ALL', value: 'true'
                }
            }
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

            post {
                success { updateGitlabCommitStatus(name: 'Frontend', state: 'success') }
                failure { updateGitlabCommitStatus(name: 'Frontend', state: 'failed') }
                unstable { updateGitlabCommitStatus(name: 'Frontend', state: 'failed') }
            }
        }

        stage('Backend') {
            when {
                anyOf {
                    changeset "backend/**"
                    environment name: 'CI_RUN_ALL', value: 'true'
                }
            }
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

            post {
                success { updateGitlabCommitStatus(name: 'Backend', state: 'success') }
                failure { updateGitlabCommitStatus(name: 'Backend', state: 'failed') }
                unstable { updateGitlabCommitStatus(name: 'Backend', state: 'failed') }
            }
        }

        stage('Worker') {
            when {
                anyOf {
                    changeset "worker/**"
                    environment name: 'CI_RUN_ALL', value: 'true'
                }
            }
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
                            // pytest 는 dir('worker') 안에서 돌아 worker/build/... 에 XML 을 남기고,
                            // post 블록은 dir() 바깥이라 워크스페이스 루트 기준 경로를 쓴다. 두 경로가
                            // 가리키는 파일은 같다.
                            junit(
                                allowEmptyResults: true,
                                testResults: 'worker/build/test-results/pytest.xml'
                            )
                        }
                    }
                }
            }

            post {
                success { updateGitlabCommitStatus(name: 'Worker', state: 'success') }
                failure { updateGitlabCommitStatus(name: 'Worker', state: 'failed') }
                unstable { updateGitlabCommitStatus(name: 'Worker', state: 'failed') }
            }
        }

        stage('Data') {
            when {
                anyOf {
                    changeset "data/**"
                    environment name: 'CI_RUN_ALL', value: 'true'
                }
            }
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
                            // Worker Test 와 같다. pytest 는 dir('data') 안에서, junit 은 워크스페이스
                            // 루트 기준으로 같은 파일을 가리킨다.
                            junit(
                                allowEmptyResults: true,
                                testResults: 'data/build/test-results/pytest.xml'
                            )
                        }
                    }
                }
            }

            post {
                success { updateGitlabCommitStatus(name: 'Data', state: 'success') }
                failure { updateGitlabCommitStatus(name: 'Data', state: 'failed') }
                unstable { updateGitlabCommitStatus(name: 'Data', state: 'failed') }
            }
        }
    }

    post {
        always {
            // 병합 상태를 되돌려 다음 빌드의 checkout 이 꼬이지 않게 한다.
            // 병합하지 않았으면 abort 할 것이 없어 실패하므로 결과를 무시한다.
            sh 'git merge --abort >/dev/null 2>&1 || true'
        }

        // 채널은 지정하지 않는다. Jenkins 전역 Mattermost 설정의 기본 채널로 가며,
        // 배포 파이프라인(infra/jenkins/Jenkinsfile.deploy)의 알림과 같은 곳에 모인다.
        //
        // 이모지는 배포와 일부러 다른 것을 쓴다. 배포가 ✅⚠️❌ 를 쓰므로 CI 는 🟢🟡🔴 로
        // 두어, 한 채널에 섞여도 어느 파이프라인의 알림인지 한눈에 갈린다.
        //
        // mattermostSend 는 mattermost 플러그인이 제공한다 (infra/jenkins/Dockerfile).
        failure {
            mattermostSend(
                color: 'danger',
                message: "🔴 CI 실패 · ${ciSubject()} #${env.BUILD_NUMBER} (<${env.BUILD_URL}|확인>)"
            )
        }

        unstable {
            mattermostSend(
                color: 'warning',
                message: "🟡 CI 불안정 · ${ciSubject()} #${env.BUILD_NUMBER} (<${env.BUILD_URL}|확인>)"
            )
        }

        success {
            mattermostSend(
                color: 'good',
                message: "🟢 CI 성공 · ${ciSubject()} #${env.BUILD_NUMBER} (<${env.BUILD_URL}|확인>)"
            )
        }
    }
}
