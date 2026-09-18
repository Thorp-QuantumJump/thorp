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

// 이번 빌드가 검사할 MR 소스 커밋을 돌려준다.
//
// 이 파일은 Job 설정에서 dev 로 고정해 읽는다(infra/jenkins/README.md). 그러면 Declarative 의 기본
// 체크아웃도 dev 를 받아, 모든 MR 이 dev 자신을 검사하고 초록불을 받는다. 그래서 기본 체크아웃을 끄고
// 이 커밋을 직접 받는다.
//
// MR 이벤트로 시작한 빌드에는 gitlab-plugin 이 MR 의 마지막 커밋을 gitlabMergeRequestLastCommit 에 넣는다.
// updateGitlabCommitStatus 도 같은 커밋에 상태를 붙이므로, 검사한 커밋과 결과가 붙는 커밋이 같다.
//
// Replay 와 지금 빌드에는 그 값이 없다. Replay 로 Jenkinsfile 변경을 머지 전에 확인할 때는 편집 화면의
// environment 블록에 CI_SOURCE_COMMIT = '<커밋 SHA>' 를 더한다. MR 이벤트의 값을 먼저 보는 이유는,
// 그 줄이 실수로 dev 에 들어가도 MR 빌드가 고정된 커밋을 검사하지 않게 하기 위해서다.
//
// 브랜치 이름은 받지 않는다. 이름이 가리키는 커밋은 빌드 도중에도 바뀔 수 있어, 무엇을 검사했는지가
// 흔들린다. 기준 dev 를 커밋으로 확정하는 것과 같은 이유다.
def sourceCommit() {
    String commit = env.gitlabMergeRequestLastCommit ?: env.CI_SOURCE_COMMIT
    if (!commit || !commit.matches('[0-9a-f]{40}')) {
        error("검사할 커밋을 알 수 없다(받은 값: ${commit}). MR 이벤트로 시작한 빌드만 커밋을 받는다. " +
              "Replay 라면 environment 블록에 CI_SOURCE_COMMIT = '<40자리 커밋 SHA>' 를 더한다.")
    }
    return commit
}

// 이 파트가 이번 변경에 포함되는지 판단한다.
//
// 예전에는 when 의 changeset 지시어를 썼는데, 그것은 Jenkins 가 계산한 changelog 를 본다.
// 이 Job 은 MR 마다 다른 브랜치를 체크아웃해서 직전 빌드와 비교할 대상이 없을 때가 있고,
// 그러면 로그에 "Warning, empty changelog" 가 남으면서 네 파트가 모두 건너뛰어진 채
// 빌드가 SUCCESS 로 끝난다. 실행되지 않은 검증이 통과로 세어진다.
//
// 비교 결과가 비면 참을 돌려준다. 무엇이 바뀌었는지 알아내지 못했을 때는 건너뛰는 쪽이
// 아니라 전부 도는 쪽으로 기운다. 시간은 더 들지만 검증이 빈 채로 초록불이 뜨지는 않는다.
def partChanged(String part) {
    if (env.CI_RUN_ALL == 'true') {
        return true
    }

    String changed = env.CHANGED_FILES
    if (changed == null || changed.trim().isEmpty()) {
        return true
    }

    // core.quotePath=false 로 한글 경로는 그대로 나오지만, 따옴표·역슬래시·제어문자가 든
    // 경로는 git 이 여전히 따옴표로 감싼다. 그러면 그 줄이 part/ 로 시작하지 않아 바뀐 파트를
    // 안 바뀐 것으로 읽는다. 그런 줄이 하나라도 있으면 비교를 믿지 않고 전체를 돌린다.
    if (changed.startsWith('"') || changed.contains('\n"')) {
        return true
    }

    return changed.split('\n').any { it.startsWith(part + '/') }
}

// 아직 결과가 나오지 않은 검사를 GitLab 에 미리 알린다.
//
// GitLab 은 커밋에 달린 상태들을 모아 MR 화면의 파이프라인 하나로 집계하는데, 그 집계는
// 그때까지 보고된 것만 센다. 보고하지 않은 검사는 '대기 중'이 아니라 아예 없는 것으로 친다.
// 그래서 결과가 확정된 뒤에만 보고하면, 앞선 두 스테이지가 통과한 순간 파이프라인 전체가
// 초록불이 되어 머지 버튼이 열린다. 파트 검증은 그때부터 시작인데도 그렇다.
//
// 돌 검사를 미리 올려 두면 GitLab 은 그것이 끝날 때까지 파이프라인을 진행 중으로 보고
// 머지를 막는다. state 는 아직 시작하지 않았으면 pending, 지금 도는 중이면 running 을 준다.
// 같은 검사를 다시 불러도 미결 목록에는 한 번만 남는다.
//
// 미결 목록을 env 에 문자열로 두는 이유는, 파이프라인 최상위에 선언한 변수가 CPS 변환을 거친
// 스테이지 안에서는 보이지 않을 수 있기 때문이다. env 는 스테이지 경계를 넘어 살아남는다.
//
// 목록은 쉼표로 이어 붙인다. 그래서 이름에 쉼표가 있으면 한 이름이 둘로 쪼개져 finishCheck 가
// 그것을 지우지 못하고, 끝난 검사가 미결로 남아 아래 post 에서 취소로 덮인다. 통과한 빌드가
// MR 에서 취소로 보이는 셈이다. 이름은 코드에 박힌 상수이므로 그런 이름을 만나면 빌드를 세운다.
// 조용히 어긋나는 것보다 그 이름을 넣은 사람이 개발 중에 걸리는 편이 낫다.
//
// 이 목록은 읽고 고쳐 쓰는 과정이 원자적이지 않다. 지금은 모든 스테이지가 순차로 돌아 문제가
// 없다. 파트를 parallel 로 묶는다면 동시에 끝난 둘이 서로의 갱신을 지우므로, 그때는 이 방식을
// 그대로 두면 안 된다.
def openCheck(String name, String state) {
    if (name.contains(',')) {
        error("검사 이름에는 쉼표를 쓸 수 없다: ${name}")
    }
    updateGitlabCommitStatus(name: name, state: state)
    env.PENDING_CHECKS = ((env.PENDING_CHECKS ?: '').tokenize(',') + name).unique().join(',')
}

// 확정된 결과를 보고하고 미결 목록에서 뺀다. 스테이지의 post 블록은 이 함수만 부른다.
def finishCheck(String name, String state) {
    updateGitlabCommitStatus(name: name, state: state)
    env.PENDING_CHECKS = ((env.PENDING_CHECKS ?: '').tokenize(',') - name).join(',')
}

pipeline {
    agent any

    options {
        timestamps()

        // 기본 체크아웃은 Job 에 설정된 브랜치(dev)를 받으므로 끈다. 검사할 커밋은 Merge with dev
        // 스테이지가 직접 받는다(sourceCommit 참고).
        skipDefaultCheckout()

        // 실행 슬롯은 CI와 배포가 함께 쓴다(infra/jenkins/init.groovy.d). 끝나지 않는 빌드가
        // 슬롯을 붙잡으면 뒤에 온 빌드가 한 단계도 돌지 못한 채 대기열에 쌓인다
        // (2026-09-17 #152~#154). 제한 시간이 지나면 timeout 은 중단 신호에 응답하지 않는
        // 단계까지 강제로 끝내므로, 사람이 중단 버튼을 누르는 것보다 확실하게 슬롯을 돌려준다.
        //
        // 이 시간은 슬롯을 받은 뒤부터 센다. 최상위 agent 를 잡은 안쪽에서 적용되기 때문이다.
        // 두 빌드가 CPU 를 나눠 쓰면 혼자 돌 때보다 느려지므로 배포(30분)보다 넉넉히 둔다.
        timeout(time: 45, unit: 'MINUTES')

        // disableConcurrentBuilds() 는 두지 않는다. 동시 실행 상한(2개)은 Jenkins 쪽
        // infra/jenkins/init.groovy.d/20-ci-concurrency.groovy 가 건다. options 는 빌드가 시작될 때 Job
        // 속성으로 저장되므로, 이 파일을 MR 소스 브랜치에서 읽던 때는 dev 를 반영하지 않은 브랜치가
        // 빌드될 때마다 상한이 그 브랜치의 값으로 바뀌었다. Job 설정의 브랜치를 되돌리면 같은 일이
        // 다시 생기므로 상한은 계속 Jenkins 쪽에 둔다. 동시에 도는 빌드는 Jenkins 가 워크스페이스를
        // thorp-ci, thorp-ci@2 로 나눠 주고, 병합 상태와 미결 검사 목록(env)도 빌드마다 따로라 서로
        // 섞이지 않는다.

        // gradle·ruff·pytest 출력의 ANSI 색상을 콘솔에 그대로 표시한다.
        // ansicolor 플러그인이 설치되어 있어야 하며, 없으면 파이프라인 자체가 기동에
        // 실패한다. 플러그인 목록은 infra/jenkins/Dockerfile 에 있다.
        ansiColor('xterm')

        // updateGitlabCommitStatus 가 GitLab API 를 부를 때 쓰는 연결이다.
        // Jenkins > Manage Jenkins > System 의 GitLab connections 에 있는 이름과 같아야 한다.
        //
        // 이름이 한 글자라도 다르거나 값이 비면 보고가 오류 없이 조용히 멈추고, 로그에
        // "No GitLab connection configured" 한 줄만 남는다. 빌드는 초록색으로 끝난다.
        //
        // 이 선언은 Job 설정 화면의 GitLab Connection 보다 우선한다(선언을 넣은 첫 빌드만
        // 예외다). 그러므로 이 줄을 지우면 다음 빌드에서 Job 의 연결 속성이 통째로 사라진다.
        // Declarative 는 이전 Jenkinsfile 이 선언했던 속성을 지우기 때문이다 (JENKINS-44621).
        gitLabConnection('ssafy-gitlab')
    }

    environment {
        // Testcontainers가 Docker에 붙지 못하면 DB·메시징 테스트가 실패가 아니라 '스킵'이 되고
        // 빌드는 초록색으로 끝난다. 마이그레이션과 Entity 매핑을 맞춰 보는 검증이 통째로 비어도
        // 알아챌 수 없다. CI에서는 그 상태를 실패로 본다 (backend RequiredDockerTest).
        THORP_REQUIRE_DOCKER = 'true'

        // Merge with dev 스테이지의 git fetch 가 쓸 자격. Job 설정의 Credentials 와 같은 것을
        // 가리킨다. Job 에서 credential 을 바꾸면 이 값도 함께 고쳐야 한다.
        GIT_CREDENTIALS_ID = 'gitlab-deploy-token'
    }

    stages {
        // 소스 브랜치만 빌드하면, 각 MR이 자기 기준으로 통과한 뒤 합쳐진 결과를 아무도 검증하지 않는다.
        // 2026-09-11에 서로 다른 두 MR이 각각 V3 마이그레이션을 추가한 채 병합되어 dev가 기동하지
        // 못한 것이 그 경우다. 이후 단계는 dev를 합친 결과 위에서 돈다.
        stage('Merge with dev') {
            steps {
                // 이 빌드에서 반드시 도는 두 검사를 먼저 등록한다. 파트 스테이지는 무엇이
                // 바뀌었는지 알아낸 뒤에야 등록할 수 있으므로 이 스테이지의 끝에서 올린다.
                script {
                    openCheck('Merge with dev', 'running')
                    openCheck('Migration version', 'pending')
                }

                script {
                    // 검사할 MR 소스 커밋을 받는다. 저장소 주소와 자격은 Job 설정(scm)의 것을 그대로
                    // 쓰고 브랜치 자리에 커밋을 넣는다. 기본 체크아웃이 쓰던 저장소·자격과 같다.
                    checkout scmGit(
                        branches: [[name: sourceCommit()]],
                        userRemoteConfigs: scm.userRemoteConfigs
                    )

                    // 파트 스테이지는 평소 바뀐 것만 걸러서 돈다. 바뀌지 않은 파트를 매번 빌드하면
                    // 시간만 들기 때문이다. 그런데 CI 자체를 손볼 때는 전체가 도는 모습을 봐야 한다.
                    // 그럴 때 커밋 메시지에 [ci-all] 을 넣으면 걸러내기를 건너뛰고 모두 실행한다.
                    //   git commit --allow-empty -m "chore: CI 전체 확인 [ci-all]"
                    //
                    // environment 블록이 아니라 체크아웃 뒤에 읽는다. environment 는 스테이지보다 먼저
                    // 평가되므로, 기본 체크아웃이 없으면 앞선 빌드가 남긴 워크스페이스의 커밋을 읽는다.
                    env.CI_RUN_ALL = sh(returnStdout: true, script: 'git log -1 --pretty=%B | grep -qF "[ci-all]" && echo true || echo false').trim()
                }

                // checkout 스텝은 GIT_ASKPASS 로 자격을 주입하지만, sh 스텝은 별개 프로세스라
                // 그것을 물려받지 못한다. 자격 없이 fetch 하면 사용자명을 묻다가
                // "could not read Username for ..." 로 끝난다.
                withCredentials([usernamePassword(
                    credentialsId: env.GIT_CREDENTIALS_ID,
                    usernameVariable: 'GIT_USERNAME',
                    passwordVariable: 'GIT_PASSWORD'
                )]) {
                    // 기준을 이름이 아니라 커밋으로 확정한다.
                    //
                    // git fetch 가 확정적으로 갱신하는 것은 FETCH_HEAD 뿐이다. refs/remotes/origin/dev
                    // 갱신은 Job 에 설정된 refspec 이 refs/heads/dev 를 덮을 때만 일어나는 부수 효과다.
                    // 이름 origin/dev 를 그대로 쓰면 그 설정에 따라 낡은 커밋을 기준으로 삼게 되고,
                    // 그러면 아래 --is-ancestor 가 참이 되어 병합하지 않은 채 성공으로 끝난다.
                    // 병합 결과를 검증하겠다는 스테이지가 검증 없이 초록불을 주는 셈이다.
                    script {
                        env.DEV_BASE = sh(returnStdout: true, script: '''
                            set -e

                            # 토큰을 remote URL 에 넣으면 .git/config 와 콘솔 로그에 남는다.
                            # 이 명령에서만 살아 있는 credential helper 로 넘겨 흔적을 남기지 않는다.
                            # fetch 의 출력은 stderr 로 보내 반환값을 더럽히지 않는다.
                            git -c credential.helper='!f() { echo username=$GIT_USERNAME; echo password=$GIT_PASSWORD; }; f' fetch --no-tags origin dev >&2

                            git rev-parse FETCH_HEAD
                        ''').trim()
                    }
                }

                echo "기준 dev = ${env.DEV_BASE}"

                sh '''
                    set -e

                    if git merge-base --is-ancestor "$DEV_BASE" HEAD; then
                        echo "[병합] 이 브랜치가 기준 dev 를 이미 포함한다. 병합하지 않고 소스 그대로 검증한다."
                        exit 0
                    fi

                    # --no-commit: 워크스페이스에 병합 결과만 만들고 커밋은 남기지 않는다.
                    #              post 단계에서 git merge --abort 로 되돌린다.
                    # --no-ff    : fast-forward 로 슬쩍 넘어가지 않고 실제 병합을 수행한다.
                    # -c user.*  : Jenkins 컨테이너에는 git identity 가 없다. merge 는
                    #              --no-commit 이어도 커밋을 만들 수 있는 상태로 들어가므로
                    #              committer 를 요구한다. 전역 설정을 건드리지 않고 이 명령에만 준다.
                    if ! git -c user.email=ci@thorp.local -c user.name=thorp-ci merge --no-commit --no-ff "$DEV_BASE"; then
                        echo "[병합] 기준 dev 와 충돌한다. 브랜치에서 먼저 dev 를 반영하고 충돌을 해소한다." >&2
                        exit 1
                    fi
                    echo "[병합] 기준 dev 를 합친 결과로 검증한다."
                '''

                // 어느 파트가 바뀌었는지는 위에서 확정한 기준과 직접 비교해서 정한다. partChanged 가
                // 이 값을 읽는다. 세 점 표기는 갈라진 지점부터 이 브랜치가 바꾼 것만 고르며, 그 사이
                // dev 에 들어온 남의 변경은 세지 않는다.
                //
                // environment 블록이 아니라 여기서 계산하는 이유는 기준을 하나로 두기 위해서다.
                // environment 는 스테이지보다 먼저 평가되어, 검사할 커밋을 받기 전의 워크스페이스를 본다.
                // 스테이지 안에 두면 어느 스테이지에 전용 agent 를 붙여도 값이 흔들리지 않는다.
                //
                // core.quotePath=false: git 은 기본값에서 ASCII 가 아닌 경로를 따옴표로 감싸고
                // 8진수로 이스케이프해 내놓는다. 그러면 한글 이름을 가진 파일의 경로가 data/ 로
                // 시작하지 않게 되어, 바뀐 파트가 안 바뀐 것으로 읽힌다. 틀리는 방향이 건너뛰는
                // 쪽이라 위험하므로 인용을 끈다.
                //
                // 병합은 --no-commit 이라 HEAD 를 움직이지 않는다. 커밋 사이의 비교이므로 이 값은
                // 병합 전후 어디서 계산해도 같다.
                script {
                    env.CHANGED_FILES = sh(returnStdout: true, script: '''
                        changed=$(git -c core.quotePath=false diff --name-only --no-renames "$DEV_BASE...HEAD" 2>/dev/null || true)

                        # 값은 stdout 하나뿐이어야 하므로 사람이 읽을 줄은 stderr 로 보낸다.
                        # 왜 어떤 파트를 건너뛰었는지가 빌드 로그에 남는다.
                        echo "[파트 선별] 기준 대비 바뀐 파일:" >&2
                        echo "$changed" >&2

                        printf '%s' "$changed"
                    ''').trim()
                }

                // 이제 어느 파트가 도는지 확정되었으므로 그 파트들을 미리 등록한다.
                // 건너뛰는 파트는 등록하지 않는다. 등록해 놓고 돌지 않으면 그 상태가 끝내
                // 확정되지 않아 MR 이 영영 머지되지 않는다.
                //
                // 왼쪽은 파트 폴더 이름, 오른쪽은 스테이지 이름이다. 오른쪽은 아래 파트
                // 스테이지의 post 가 보고하는 이름과 글자 하나까지 같아야 한다. 다르면 여기서
                // 올린 상태를 결과가 덮지 못하고 남아 머지가 막힌다.
                script {
                    for (List part : [['frontend', 'Frontend'], ['backend', 'Backend'],
                                      ['worker', 'Worker'], ['data', 'Data']]) {
                        if (partChanged(part[0])) {
                            openCheck(part[1], 'pending')
                        }
                    }
                }
            }

            // GitLab MR 화면에 이 파트의 결과를 되돌려준다. 여섯 파트가 각각 한 줄로 나열되어,
            // Jenkins 에 들어가지 않고도 어느 파트가 깨졌는지 MR 에서 바로 보인다.
            // 빨간불이 하나라도 있으면 GitLab 의 Merge checks 가 머지 버튼을 막고, 위에서 미리
            // 올려 둔 검사가 아직 끝나지 않았어도 막는다.
            //
            // when 조건에 걸려 건너뛴 파트는 post 가 돌지 않아 아예 표시되지 않는다.
            // 돌지 않은 것을 성공으로 보고하는 것보다 낫다. 그런 파트는 위에서 등록도 하지
            // 않으므로 끝나지 않은 검사로 남지도 않는다.
            post {
                success { script { finishCheck('Merge with dev', 'success') } }
                failure { script { finishCheck('Merge with dev', 'failed') } }
                unstable { script { finishCheck('Merge with dev', 'failed') } }
            }
        }

        stage('Migration version') {
            steps {
                script { openCheck('Migration version', 'running') }

                // Merge with dev 가 확정한 기준을 그대로 쓴다. 이름 origin/dev 를 다시 쓰면
                // 검사 기준과 병합 기준이 서로 다른 커밋이 될 수 있다.
                sh 'bash infra/scripts/check-migration-version.sh "$DEV_BASE"'
            }

            post {
                success { script { finishCheck('Migration version', 'success') } }
                failure { script { finishCheck('Migration version', 'failed') } }
                unstable { script { finishCheck('Migration version', 'failed') } }
            }
        }

        stage('Frontend') {
            when {
                expression { partChanged('frontend') }
            }
            stages {
                stage('FE Install') {
                    steps {
                        script { openCheck('Frontend', 'running') }

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
                success { script { finishCheck('Frontend', 'success') } }
                failure { script { finishCheck('Frontend', 'failed') } }
                unstable { script { finishCheck('Frontend', 'failed') } }
            }
        }

        stage('Backend') {
            when {
                expression { partChanged('backend') }
            }
            stages {
                stage('BE Format') {
                    steps {
                        script { openCheck('Backend', 'running') }

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
                success { script { finishCheck('Backend', 'success') } }
                failure { script { finishCheck('Backend', 'failed') } }
                unstable { script { finishCheck('Backend', 'failed') } }
            }
        }

        stage('Worker') {
            when {
                expression { partChanged('worker') }
            }
            stages {
                stage('Worker Setup') {
                    steps {
                        script { openCheck('Worker', 'running') }

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
                success { script { finishCheck('Worker', 'success') } }
                failure { script { finishCheck('Worker', 'failed') } }
                unstable { script { finishCheck('Worker', 'failed') } }
            }
        }

        stage('Data') {
            when {
                expression { partChanged('data') }
            }
            stages {
                stage('Data Setup') {
                    steps {
                        script { openCheck('Data', 'running') }

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
                success { script { finishCheck('Data', 'success') } }
                failure { script { finishCheck('Data', 'failed') } }
                unstable { script { finishCheck('Data', 'failed') } }
            }
        }
    }

    post {
        always {
            // 병합 상태를 되돌려 다음 빌드의 checkout 이 꼬이지 않게 한다.
            // 병합하지 않았으면 abort 할 것이 없어 실패하므로 결과를 무시한다.
            //
            // 워크스페이스 정리를 GitLab 보고보다 먼저 한다. 보고가 어떤 이유로 멈추더라도
            // 다음 빌드가 병합이 걸린 워크스페이스를 물려받지 않게 하기 위함이다.
            //
            // 슬롯을 받기 전에 취소된 빌드는 워크스페이스가 없어 sh 가 실패하고, 그 자리에서
            // always 블록이 끊겨 아래 GitLab 정리까지 건너뛴다. NODE_NAME 은 슬롯을 받은
            // 안쪽에서만 채워지므로 그것으로 가른다. 이 경우엔 되돌릴 병합도 없다.
            script {
                if (env.NODE_NAME) {
                    sh 'git merge --abort >/dev/null 2>&1 || true'
                }
            }

            // 앞 스테이지가 실패해 뒤가 아예 돌지 못하면 그 검사는 미리 올린 상태 그대로
            // 남는다. GitLab 은 끝나지 않은 검사를 계속 기다리므로 MR 이 실패도 성공도 아닌
            // 채로 굳는다. 돌지 못한 것은 취소로 닫는다. 실패한 검사는 이미 failed 로
            // 보고되어 목록에서 빠졌으니 파이프라인 전체 결과는 그대로 실패로 남는다.
            script {
                for (String name : (env.PENDING_CHECKS ?: '').tokenize(',')) {
                    updateGitlabCommitStatus(name: name, state: 'canceled')
                }
                env.PENDING_CHECKS = ''
            }
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
