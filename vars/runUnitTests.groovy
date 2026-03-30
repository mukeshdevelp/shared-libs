def call(Map config = [:]) {

    def repoUrl = config.repoUrl ?: "https://github.com/OT-MICROSERVICES/salary-api.git"
    def branch  = config.branch ?: "main"

    pipeline {
        agent any

        tools {
            maven 'maven3'
            jdk 'jdk17'
        }

        stages {

            stage('Checkout Code') {
                steps {
                    git branch: branch, url: repoUrl
                }
            }

            stage('Verify Java & Maven') {
                steps {
                    sh '''
                        echo "Java Version:"
                        java -version
                        echo "Maven Version:"
                        mvn -version
                    '''
                }
            }

            stage('Run Unit Tests') {
                steps {
                    sh 'mvn clean test'
                }
            }

            stage('Generate Test Report') {
                steps {
                    sh 'mvn surefire-report:report'
                }
            }

            stage('Archive Reports') {
                steps {
                    archiveArtifacts artifacts: 'target/site/**', allowEmptyArchive: true
                }
            }
        }

        post {
            success {
                echo "Unit Tests Passed Successfully"
            }
            failure {
                echo "Unit Tests Failed"
            }
            always {
                cleanWs()
            }
        }
    }
}