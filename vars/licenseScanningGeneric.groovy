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

            def FORBIDDEN_CATEGORIES = ['restricted']
            def WARN_CATEGORIES      = ['reciprocal', 'unknown']

            def violations = []
            def warnings   = []

            report.Results?.each { result ->
                result?.Licenses?.each { lic ->
                    def cat   = lic.Category?.toLowerCase()
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
                warnings.each { echo "   WARN -> ${it}" }
            }

            if (violations) {
                echo "Restricted License Violations (${violations.size()}):"
                violations.each { echo "   FAIL -> ${it}" }
            }

            // --- Failure conditions ---
            def failReasons = []

            def VIOLATION_THRESHOLD = 10
            def HARD_FAIL_LICENSES  = ['AGPL-3.0', 'AGPL-3.0-only', 'GPL-3.0-only']
            def SOFT_FAIL_LICENSES  = ['GPL-2.0-only', 'GPL-2.0-with-classpath-exception']

            def hardFailFound = violations.any { entry ->
                HARD_FAIL_LICENSES.any { entry.contains(it) }
            }

            def softFailFound = violations.any { entry ->
                SOFT_FAIL_LICENSES.any { entry.contains(it) }
            }

            if (violations.size() > VIOLATION_THRESHOLD) {
                failReasons << "Restricted license violations (${violations.size()}) exceeded threshold of ${VIOLATION_THRESHOLD}."
            }

            if (hardFailFound) {
                failReasons << "Strictly forbidden licenses detected (AGPL / GPL-3.0)."
            }

            if (failReasons) {
                error("LICENSE SCAN FAILED:\n" + failReasons.join('\n'))
            } else if (softFailFound) {
                echo "WARNING: GPL-2.0 licenses detected but allowed per policy. Review manually."
            } else if (violations) {
                echo "WARNING: ${violations.size()} restricted license(s) within threshold (${VIOLATION_THRESHOLD}). Continuing."
            } else {
                echo "LICENSE SCAN PASSED. No restricted licenses found. (Total: ${totalLicenses})"
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