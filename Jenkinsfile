// Thorp CI 파이프라인
// Jenkins 서버 구성은 infra/jenkins/ 참고.
pipeline {
    agent any

    options {
        timestamps()
        disableConcurrentBuilds()
    }

    stages {
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

    
}
