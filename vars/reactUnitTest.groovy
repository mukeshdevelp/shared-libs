def call(Map config = [:]) {

    def gitRepo       = config.gitRepo ?: "https://github.com/OT-MICROSERVICES/frontend.git"
    def gitBranch     = config.gitBranch ?: "main"
    def slackChannel  = config.slackChannel ?: "#ci-operation-notifications"
    def slackCredId   = config.slackCredId ?: "Shreyas-Slack"
    def nodeTool      = config.nodeTool ?: "node-16"

    pipeline {
        agent any

        tools {
            nodejs "${nodeTool}"
        }

        stages {

            stage('Checkout & Install') {
                steps {
                    git branch: "${gitBranch}", url: "${gitRepo}"
                    sh 'npm install'
                }
            }

            stage('Unit Testing (Jest)') {
                steps {
                    sh 'npm test -- --coverage --passWithNoTests || true'
                }
            }
        }

        post {
            always {
                script {
                    def status = currentBuild.currentResult

                    def colorMap = [
                        "SUCCESS" : "good",
                        "UNSTABLE": "warning",
                        "FAILURE" : "danger"
                    ]

                    def emojiMap = [
                        "SUCCESS" : "",
                        "UNSTABLE": "",
                        "FAILURE" : ""
                    ]

                    def emoji = emojiMap.get(status, "")
                    def color = colorMap.get(status, "danger")

                    slackSend(
                        
                        channel: slackChannel,
                        color: color,
                        message: """\
                        ${emoji} *${status}* - React Unit Testing
                        *Job:* ${env.JOB_NAME}
                        *Build:* #${env.BUILD_NUMBER}
                        *Branch:* ${gitBranch}
                        *URL:* ${env.BUILD_URL}""".stripIndent()
                    )
                }

                cleanWs()
            }
        }
    }
}