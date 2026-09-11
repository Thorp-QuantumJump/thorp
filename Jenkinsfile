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

    stages {
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
                            sh '. .venv/bin/activate && ruff check src scripts'
                        }
                    }
                }

                stage('Data Test') {
                    steps {
                        dir('data') {
                            // data 파트에는 아직 테스트 파일이 없다. pytest 는 수집된
                            // 테스트가 하나도 없으면 종료 코드 5로 끝나므로, 그 경우만
                            // 통과시킨다. 테스트가 추가되면 이 예외 처리를 지운다.
                            sh '''
                                . .venv/bin/activate
                                rc=0
                                pytest --junitxml=build/test-results/pytest.xml || rc=$?
                                [ "$rc" -eq 0 ] || [ "$rc" -eq 5 ]
                            '''
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
}
