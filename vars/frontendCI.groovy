def call(Map config = [:]) {

    // 🔧 CONFIGURABLE VARIABLES
    def gitRepo       = config.gitRepo ?: "https://github.com/OT-MICROSERVICES/frontend.git"
    def gitBranch     = config.gitBranch ?: "main"
    def nodeTool      = config.nodeTool ?: "node-16"
    def projectKey    = config.projectKey ?: "frontend-ci-checks"
    def sonarEnv      = config.sonarEnv ?: "sonarqube-server"
    def slackChannel  = config.slackChannel ?: "#ci-operation-notifications"
    def emailTo       = config.emailTo ?: "devopsp491@gmail.com"

    pipeline {
        agent any

        tools {
            nodejs "${nodeTool}"
        }

        environment {
            BUILD_STATUS = "SUCCESS"
        }

        stages {

            stage('Checkout Code') {
                steps {
                    script {
                        try {
                            git branch: gitBranch, url: gitRepo
                        } catch (e) {
                            env.BUILD_STATUS = "FAILED"
                            error "Checkout failed: ${e}"
                        }
                    }
                }
            }

            stage('Install Dependencies') {
                steps {
                    script {
                        try {
                            sh 'npm install --verbose'
                        } catch (e) {
                            env.BUILD_STATUS = "FAILED"
                            error "Dependency installation failed: ${e}"
                        }
                    }
                }
            }

            stage('Build (Webpack)') {
                steps {
                    script {
                        try {
                            sh '''
                                export CI=false
                                npm run build 2>&1 | tee build-output.txt
                            '''
                        } catch (e) {
                            env.BUILD_STATUS = "FAILED"
                            error "Build failed: ${e}"
                        }
                    }
                }
                post {
                    success {
                        archiveArtifacts artifacts: 'build/**', fingerprint: true
                    }
                }
            }

            stage('Unit Testing (Jest)') {
                steps {
                    script {
                        try {
                            sh 'npx jest --coverage --passWithNoTests | tee jest-output.txt'
                        } catch (e) {
                            env.BUILD_STATUS = "FAILED"
                            error "Unit tests failed: ${e}"
                        }
                    }
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
                        try {
                            def scannerHome = tool 'sonar-scanner'

                            withSonarQubeEnv(sonarEnv) {
                                withEnv(["PATH+SONAR=${scannerHome}/bin"]) {

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

                        } catch (e) {
                            env.BUILD_STATUS = "FAILED"
                            error "SonarQube analysis failed: ${e}"
                        }
                    }
                }
            }

            stage('Quality Gate') {
                steps {
                    script {
                        try {
                            timeout(time: 5, unit: 'MINUTES') {
                                waitForQualityGate abortPipeline: false
                            }
                        } catch (e) {
                            env.BUILD_STATUS = "FAILED"
                            error "Quality Gate failed: ${e}"
                        }
                    }
                }
            }

            stage('Dependency Scan (Trivy)') {
                steps {
                    script {
                        try {
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
                        } catch (e) {
                            env.BUILD_STATUS = "FAILED"
                            error "Trivy scan failed: ${e}"
                        }
                    }
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
                script {
                    sendNotification("SUCCESS", slackChannel, emailTo)
                }
            }

            failure {
                script {
                    sendNotification("FAILED", slackChannel, emailTo)
                }
            }

            always {
                cleanWs()
            }
        }
    }
}

// Notification function to send slack and email

def sendNotification(status, slackChannel, emailTo) {

    def color = (status == "SUCCESS") ? "good" : "danger"

    // Slack
    try {
        slackSend(
            channel: slackChannel,
            color: color,
            message: """
            *Frontend CI Pipeline*

            Status: ${status}
            Job: ${env.JOB_NAME}
            Build: #${env.BUILD_NUMBER}
            URL: ${env.BUILD_URL}
            """
        )
    } catch (e) {
        echo "Slack notification failed: ${e}"
    }

    // Email
    try {
        emailext(
            to: emailTo,
            subject: "Build ${status}: ${env.JOB_NAME} #${env.BUILD_NUMBER}",
            body: """
            Build Status: ${status}

            Job: ${env.JOB_NAME}
            Build Number: ${env.BUILD_NUMBER}

            View Details:
            ${env.BUILD_URL}
            """
        )
    } catch (e) {
        echo "Email notification failed: ${e}"
    }
}