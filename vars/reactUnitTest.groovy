def call(Map config = [:]) {

    def nodeVersion = config.nodeVersion ?: "18"
    def workingDir = config.workingDir ?: "."
    def installCommand = config.installCommand ?: "npm install"
    def testCommand = config.testCommand ?: "npm test -- --watchAll=false"

    pipeline {
        agent any

        stages {

            stage('Setup Node') {
                steps {
                    script {
                        echo "Using Node Version: ${nodeVersion}"
                    }
                }
            }

            stage('Install Dependencies') {
                steps {
                    dir(workingDir) {
                        sh """
                            echo "Installing dependencies..."
                            ${installCommand}
                        """
                    }
                }
            }

            stage('Run Unit Tests') {
                steps {
                    dir(workingDir) {
                        sh """
                            echo "Running Jest tests..."
                            ${testCommand}
                        """
                    }
                }
            }
        }

        post {
            success {
                echo "Unit tests passed successfully"
            }
            failure {
                echo "Unit tests failed"
            }
            always {
                cleanWs()
            }
        }
    }
}