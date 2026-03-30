def call(Map config = [:]) {

    def repoUrl = config.repoUrl ?: "https://github.com/OT-MICROSERVICES/salary-api.git"
    def branch  = config.branch ?: "backend"

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
                    dir('salary/salary-api') {
                        sh 'pwd'
                        sh 'mvn clean test'
                    }
                    echo "Unit Tests Completed"
                }
            }

            stage('Generate Test Report') {
                steps {
                    dir('salary/salary-api') {
                        sh 'mvn surefire-report:report'
                    }
                }
            }

            stage('Archive Reports') {
                steps {
                    archiveArtifacts artifacts: 'salary/salary-api/target/site/**', allowEmptyArchive: true
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