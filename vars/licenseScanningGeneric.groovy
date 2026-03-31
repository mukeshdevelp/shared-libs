// vars/licenseScanning.groovy
def call(Map config = [:]) {
    node {
        // Environment variables
        def REPORT_DIR = config.get('reportDir', 'reports')
        
        // Note -  link to main  repo - It is a demo repo not a repo of OT-MICROSERVICES.
        def GITHUB_REPO = 'https://github.com/Saarthi-P17/declarative-pipeline-poc.git'  // hardcoded repo
        def GITHUB_BRANCH = 'main'  // hardcoded branch

        try {
            stage('checking trivy version') {
                echo "Installing required tools..."
                sh '''
            

                echo "Installed versions:"
                
                trivy --version
                '''
            }

            

            stage('License Scan') {
                sh """
                mkdir -p ${REPORT_DIR}

                trivy fs \
                --scanners license \
                --format sarif \
                --output ${REPORT_DIR}/trivy-license-report.sarif \
                .
                """
            }

            echo "Build completed successfully."

            // Slack notification for success
            slackSend(
                channel: config.get('slackChannel', '#ci-operation-notifications'),
                color: 'good',
                message: """
                Build Successful

                Job: ${env.JOB_NAME}
                Build: #${env.BUILD_NUMBER}
                URL: ${env.BUILD_URL}
                """
            )

        } catch (err) {
            // Slack notification for failure
            slackSend(
                channel: config.get('slackChannel', '#ci-operation-notifications'),
                color: 'danger',
                message: """
                Build Failed

                Job: ${env.JOB_NAME}
                Build: #${env.BUILD_NUMBER}
                URL: ${env.BUILD_URL}
                """
            )
            error "Pipeline failed: ${err}"
        } finally {
            archiveArtifacts artifacts: "${REPORT_DIR}/*", fingerprint: true
        }
    }
}