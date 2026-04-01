def call(Map config = [:]) {

    def gitRepo       = config.gitRepo ?: "https://github.com/OT-MICROSERVICES/frontend.git"
    def gitBranch     = config.gitBranch ?: "main"
    def nodeTool      = config.nodeTool ?: "node-16"
    def sonarProject  = config.sonarProject ?: "frontend-ci-checks"
    def sonarName     = config.sonarName ?: "frontend-ci-checks"
    def projectKey     = config.projectKey ?: "frontend-ci-checks"
    def scannerHome = tool 'sonar-scanner'  // Jenkins Global Tool Config
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
                    sh 'npm install --verbose'
                }
            }

            stage('Code Compilation (Webpack Build)') {
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
                    sh 'npx jest --coverage --passWithNoTests > file.txt'
                }
                post {
                    always {
                        archiveArtifacts artifacts: 'file.txt', fingerprint: true
                    }
                }
            }

            stage('SonarQube Analysis (Bugs + SAST)') {
                steps {
                        script {
                            withEnv(["PATH+SONAR=${scannerHome}/bin"]) {
                            sh '''
                                sonar-scanner \
                                -Dsonar.projectKey=${projectKey} \
                                -Dsonar.sources=. \
                                -Dsonar.javascript.lcov.reportPaths=coverage/lcov.info
                            '''
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