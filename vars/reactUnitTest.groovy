def call(Map config = [:]) {

    def gitRepo      = config.gitRepo      ?: "https://github.com/OT-MICROSERVICES/frontend.git"
    def gitBranch    = config.gitBranch    ?: "main"
    def slackChannel = config.slackChannel ?: "#ci-operation-notifications"
    def nodeTool     = config.nodeTool     ?: "node-16"

    pipeline {
        agent any

        tools {
            nodejs "${nodeTool}"
        }

        stages {

            stage('Checkout') {
                steps {
                    git branch: "${gitBranch}", url: "${gitRepo}"
                }
            }

            stage('Install Dependencies') {
                steps {
                    sh 'npm --version'
                    sh 'node --version'
                    timeout(time: 10, unit: 'MINUTES') {
                        sh 'npm install --verbose'
                    }
                }
            }

            stage('Install Jest (if not present)') {
                steps {
                    sh '''
                        if ! npm list jest > /dev/null 2>&1; then
                            echo "Installing Jest..."
                            npm install --save-dev jest
                        else
                            echo "Jest already installed"
                        fi
                    '''
                }
            }

            stage('Run Unit Tests (Jest Directly)') {
                steps {
                    sh 'npx jest --coverage --passWithNoTests > file.txt'
                }
            }
        }

        post {
            always {
                script {
                    // Evaluated HERE — after build result is known
                    def color = currentBuild.currentResult == 'SUCCESS' ? 'good' : 'danger'

                    try {
                        // Use slackSend directly, not step([...])
                        slackSend(
                            channel: slackChannel,
                            color: color,
                            message: """\
                            Jest Unit Testing
                            *Job:* ${env.JOB_NAME}
                            *Build:* #${env.BUILD_NUMBER}
                            *Branch:* ${gitBranch}
                            *Status:* ${currentBuild.currentResult}
                            *URL:* ${env.BUILD_URL}"""
                        )
                    } catch (Exception e) {
                        echo "Slack notification failed: ${e.message}"
                    }
                }

                archiveArtifacts artifacts: 'file.txt', fingerprint: true, allowEmptyArchive: true
                cleanWs()
            }
        }
    }
}