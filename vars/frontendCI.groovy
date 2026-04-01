def call(Map config = [:]) {

    def gitRepo       = config.gitRepo ?: "https://github.com/OT-MICROSERVICES/frontend.git"
    def gitBranch     = config.gitBranch ?: "main"
    def nodeTool      = config.nodeTool ?: "node-16"
    def sonarProject  = config.sonarProject ?: "frontend-app"
    def sonarName     = config.sonarName ?: "Frontend App"

    pipeline {
        agent any

        tools {
            nodejs "${nodeTool}"
        }

        environment {
            SONARQUBE_ENV = "sonarqube-server"
        }

        stages {

            stage('Checkout Code') {
                steps {
                    git branch: "${gitBranch}", url: "${gitRepo}"
                }
            }

            stage('Install Dependencies') {
                steps {
                    sh 'npm install'
                }
            }

            stage('Code Compilation (Webpack Build)') {
                steps {
                    sh 'npm run build'
                }
                post {
                    success {
                        archiveArtifacts artifacts: 'dist/**', fingerprint: true
                    }
                }
            }

            stage('Unit Testing (Jest)') {
                steps {
                    sh 'npm test -- --coverage'
                }
                post {
                    always {
                        junit 'coverage/junit.xml' // if configured
                        archiveArtifacts artifacts: 'coverage/**', fingerprint: true
                    }
                }
            }

            stage('SonarQube Analysis (Bugs + SAST)') {
                steps {
                    withSonarQubeEnv("${SONARQUBE_ENV}") {
                        sh """
                        sonar-scanner \
                          -Dsonar.projectKey=${sonarProject} \
                          -Dsonar.projectName='${sonarName}' \
                          -Dsonar.sources=. \
                          -Dsonar.exclusions=**/node_modules/** \
                          -Dsonar.javascript.lcov.reportPaths=coverage/lcov.info
                        """
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

            stage('Dependency Scanning (Trivy)') {
                steps {
                    sh '''
                        # Run Trivy filesystem scan (JSON report)
                        trivy fs . \
                            --skip-dirs node_modules \
                            --format json \
                            --output trivy-report.json \
                            --exit-code 1 \
                            --severity HIGH,CRITICAL

                        # Generate human-readable report
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
                    failure {
                        echo "Trivy found HIGH/CRITICAL vulnerabilities"
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
                archiveArtifacts artifacts: '*.log', allowEmptyArchive: true
                cleanWs()
            }
        }
    }
}