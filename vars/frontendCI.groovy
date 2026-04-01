def call(Map config = [:]) {

    def gitRepo    = config.gitRepo ?: "https://github.com/OT-MICROSERVICES/frontend.git"
    def gitBranch  = config.gitBranch ?: "main"
    def nodeTool   = config.nodeTool ?: "node-16"
    def projectKey = config.projectKey ?: "frontend-ci-checks"

    pipeline {
        agent any

        tools {
            nodejs "${nodeTool}"
        }

        stages {

            stage('Checkout Code') {
                steps {
                    git branch: "${gitBranch}", url: "${gitRepo}"
                }
            }

            stage('Install Dependencies') {
                steps {
                    sh 'npm install --verbose'
                }
            }

            stage('Build (Webpack)') {
                steps {
                    sh '''
                        export CI=false
                        npm run build 2>&1 | tee build-output.txt
                    '''
                }
                post {
                    success {
                        archiveArtifacts artifacts: 'build/**', fingerprint: true
                    }
                }
            }

            stage('Unit Testing (Jest)') {
                steps {
                    sh 'npx jest --coverage --passWithNoTests > jest-output.txt'
                }
                post {
                    always {
                        archiveArtifacts artifacts: 'jest-output.txt', fingerprint: true
                    }
                }
            }

            stage('SonarQube Analysis') {
                steps {
                    script {
                        // MUST be inside steps/script (fix for your error)
                        def scannerHome = tool 'sonar-scanner'

                        withSonarQubeEnv('sonarqube-server') {
                            withEnv(["PATH+SONAR=${scannerHome}/bin"]) {

                                // Debug (optional but useful)
                                sh 'which sonar-scanner || echo "SONAR NOT FOUND"'
                                sh 'ls -l coverage/lcov.info || echo "NO COVERAGE FILE"'

                                sh """
                                    sonar-scanner \
                                    -Dsonar.projectKey=${projectKey} \
                                    -Dsonar.sources=. \
                                    -Dsonar.javascript.lcov.reportPaths=coverage/lcov.info
                                """
                            }
                        }
                    }
                }
            }

            stage('Quality Gate') {
                steps {
                    timeout(time: 5, unit: 'MINUTES') {
                        waitForQualityGate abortPipeline: true
                    }
                }
            }

            stage('Dependency Scan (Trivy)') {
                steps {
                    sh '''
                        trivy fs . \
                        --skip-dirs node_modules \
                        --format json \
                        --output trivy-report.json \
                        --severity HIGH,CRITICAL || true

                        trivy fs . \
                        --skip-dirs node_modules \
                        --format table \
                        --output trivy-report.txt
                    '''
                }
                post {
                    always {
                        archiveArtifacts artifacts: 'trivy-report.*', fingerprint: true
                    }
                }
            }
        }

        post {
            success {
                echo "CI Pipeline Passed"
            }
            failure {
                echo "CI Pipeline Failed"
            }
            always {
                cleanWs()
            }
        }
    }
}