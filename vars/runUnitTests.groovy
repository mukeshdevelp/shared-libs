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
                    sh 'pwd'
                    sh 'ls -la'
                    script {
                        // Build continues even if tests fail - marked UNSTABLE
                        def result = sh(
                            script: 'mvn clean test -Dmaven.test.failure.ignore=true',
                            returnStatus: true
                        )
                        if (result != 0) {
                            currentBuild.result = 'UNSTABLE'
                            echo "Some tests failed - marking build as UNSTABLE"
                        }
                    }
                    echo "Unit Tests Stage Completed"
                }
            }

            stage('Generate Test Report') {
                steps {
                    sh 'mvn surefire-report:report -Dmaven.test.failure.ignore=true'
                }
            }

            stage('Publish Test Results') {
                steps {
                    junit testResults: 'target/surefire-reports/*.xml',
                          allowEmptyResults: true
                }
            }

            stage('Archive Reports') {
                steps {
                    archiveArtifacts artifacts: 'target/site/**',
                                     allowEmptyArchive: true
                }
            }
        }

        post {
            success {
                echo "All Unit Tests Passed Successfully"
            }
            unstable {
                echo "Some Unit Tests Failed - Build is UNSTABLE"
            }
            failure {
                echo "Pipeline Failed"
            }
            always {
                cleanWs()
            }
        }
    }
}