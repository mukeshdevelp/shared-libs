// vars/licenseScanning.groovy
def call(Map config = [:]) {

    def REPORT_DIR = config.get('reportDir', 'reports')
    def SLACK_CHANNEL = config.get('slackChannel', '#ci-operation-notifications')

    try {

        stage('Checkout Code') {
            // Use existing SCM if available, else fallback
            if (config.repoUrl) {
                git branch: config.get('branch', 'main'),
                    url: config.repoUrl
            } else {
                checkout scm
            }
        }

        stage('Trivy Installation') {
            sh '''
                if ! command -v trivy >/dev/null 2>&1; then
                    echo "Trivy not found. Installing..."
                    
                    sudo apt-get update -y
                    sudo apt-get install -y wget apt-transport-https gnupg lsb-release
                    
                    wget -qO - https://aquasecurity.github.io/trivy-repo/deb/public.key | sudo apt-key add -
                    echo "deb https://aquasecurity.github.io/trivy-repo/deb $(lsb_release -sc) main" | sudo tee /etc/apt/sources.list.d/trivy.list
                    
                    sudo apt-get update -y
                    sudo apt-get install -y trivy
                fi

                echo "Trivy version:"
                trivy --version
            '''
        }

        stage('License Scan') {
            sh """
                mkdir -p ${REPORT_DIR}
                trivy fs . \
                    --scanners license \
                    --format json \
                    --output ${REPORT_DIR}/trivy-license-report.json
            """

            def report = readJSON file: "${REPORT_DIR}/trivy-license-report.json"

            def totalLicenses = report.Results
                ? report.Results.collect { it?.Licenses?.size() ?: 0 }.sum()
                : 0

            echo "Total licenses detected: ${totalLicenses}"

            // Define which categories should FAIL the build
            def FORBIDDEN_CATEGORIES = ['restricted']

            // Define which categories should WARN only
            def WARN_CATEGORIES = ['reciprocal', 'unknown']

            def violations = []
            def warnings   = []

            report.Results?.each { result ->
                result?.Licenses?.each { lic ->
                    def cat = lic.Category?.toLowerCase()
                    def entry = "[${lic.Category}] ${lic.PkgName}: ${lic.Name}"

                    if (FORBIDDEN_CATEGORIES.contains(cat)) {
                        violations << entry
                    } else if (WARN_CATEGORIES.contains(cat)) {
                        warnings << entry
                    }
                }
            }

            if (warnings) {
                echo "License Warnings (${warnings.size()}):"
                warnings.each { echo "   WARN → ${it}" }
            }

            if (violations) {
                echo "Restricted License Violations (${violations.size()}):"
                violations.each { echo "   FAIL → ${it}" }
                error("License scan failed: ${violations.size()} restricted license(s) found.\n${violations.join('\n')}")
            } else {
                echo "License scan passed. No restricted licenses found. (Total: ${totalLicenses})"
            }
        }

        echo "License scan completed successfully."

        slackSend(
            channel: SLACK_CHANNEL,
            color: 'good',
            message: """
            License Scan Successful
            Job: ${env.JOB_NAME}
            Build: #${env.BUILD_NUMBER}
            URL: ${env.BUILD_URL}
            """
        )

    } catch (err) {

        slackSend(
            channel: SLACK_CHANNEL,
            color: 'danger',
            message: """
            License Scan Failed
            Job: ${env.JOB_NAME}
            Build: #${env.BUILD_NUMBER}
            URL: ${env.BUILD_URL}
            """
        )

        error "License scan failed: ${err}"

    } finally {

        archiveArtifacts artifacts: "${REPORT_DIR}/*",
                         fingerprint: true,
                         allowEmptyArchive: true
    }
}