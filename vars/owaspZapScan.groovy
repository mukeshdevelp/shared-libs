def call(Map config = [:]) {

    def targetIp     = config.targetIp     ?: "3.235.197.134"
    def targetPort   = config.targetPort   ?: "8081"
    def targetUrl    = config.targetUrl    ?: "http://${targetIp}:${targetPort}"
    def zapPort      = config.zapPort      ?: "9000"
    def zapApiKey    = config.zapApiKey    ?: "attendancekey"
    def reportDir    = config.reportDir    ?: "zap-reports"
    def slackChannel = config.slackChannel ?: "#ci-operation-notifications"

    // ZAP installed at known path
    def zapPath      = "/usr/local/bin/zap.sh"

    node {

        def javaHome = tool name: 'jdk17', type: 'jdk'
        env.JAVA_HOME = javaHome
        env.PATH      = "${javaHome}/bin:${env.PATH}"

        try {

        
            // SETUP
           

            stage('Clean Workspace') {
                echo "Cleaning workspace..."
                cleanWs()
            }

            stage('Verify Java') {
                sh '''
                    echo "JAVA_HOME: $JAVA_HOME"
                    java -version
                '''
            }

            stage('Check ZAP Installation') {
                echo "Verifying OWASP ZAP at ${zapPath}..."
                sh """
                    if [ ! -f "${zapPath}" ]; then
                        echo "ERROR: zap.sh not found at ${zapPath}"
                        exit 1
                    fi

                    echo "ZAP found at: ${zapPath}"
                    echo "-----------------------------------"
                    ls -lh ${zapPath}
                    echo "ZAP Version:"
                    ${zapPath} -version 2>/dev/null || echo "Version flag not supported, ZAP is present"
                    echo "-----------------------------------"
                """
            }

           
            // ZAP DAEMON
          

            stage('Start ZAP') {
                echo "Starting ZAP daemon..."
                sh """
                    echo "Using JAVA_HOME : \$JAVA_HOME"
                    echo "Using ZAP       : ${zapPath}"
                    java -version

                    export JAVA_OPTS="-Xmx1024m"

                    nohup ${zapPath} -daemon \\
                        -port ${zapPort} \\
                        -host 127.0.0.1 \\
                        -config api.key=${zapApiKey} \\
                        > zap.log 2>&1 &

                    echo "Waiting for ZAP to become ready..."
                    MAX_WAIT=120
                    WAITED=0
                    until curl -s "http://127.0.0.1:${zapPort}/JSON/core/view/version/?apikey=${zapApiKey}" | grep -q 'version'; do
                        if [ \$WAITED -ge \$MAX_WAIT ]; then
                            echo "ERROR: ZAP did not start within \${MAX_WAIT}s"
                            echo "---- ZAP LOG ----"
                            cat zap.log || true
                            exit 1
                        fi
                        echo "ZAP not ready yet... waiting 10s (waited \${WAITED}s so far)"
                        sleep 10
                        WAITED=\$((WAITED + 10))
                    done

                    echo "ZAP is ready after \${WAITED}s"
                    curl -s "http://127.0.0.1:${zapPort}/JSON/core/view/version/?apikey=${zapApiKey}"
                """
            }

            stage('Configure ZAP Timeout') {
                echo "Increasing ZAP connection and read timeouts..."
                sh """
                    curl -sf "http://127.0.0.1:${zapPort}/JSON/core/action/setOptionTimeoutInSecs/?Integer=60&apikey=${zapApiKey}"
                    echo "Connection timeout set to 60s"

                    curl -sf "http://127.0.0.1:${zapPort}/JSON/network/action/setConnectionTimeout/?timeout=120&apikey=${zapApiKey}" || true
                    curl -sf "http://127.0.0.1:${zapPort}/JSON/network/action/setReadTimeout/?timeout=120&apikey=${zapApiKey}" || true
                    echo "Read timeout set to 120s"

                    curl -sf "http://127.0.0.1:${zapPort}/JSON/core/view/optionTimeoutInSecs/?apikey=${zapApiKey}" || true
                """
            }

   
            // SCANNING
       

            stage('Run Spider Scan') {
                echo "Running Spider Scan on ${targetUrl}..."
                sh """
                    RESPONSE=\$(curl -sf "http://127.0.0.1:${zapPort}/JSON/spider/action/scan/?url=${targetUrl}&apikey=${zapApiKey}")
                    echo "Spider scan response: \$RESPONSE"

                    SCAN_ID=\$(echo \$RESPONSE | grep -o '"scan":"[^"]*"' | grep -o '[0-9]*')
                    echo "Spider Scan ID: \$SCAN_ID"

                    MAX_WAIT=180
                    WAITED=0
                    while true; do
                        PROGRESS=\$(curl -sf "http://127.0.0.1:${zapPort}/JSON/spider/view/status/?scanId=\$SCAN_ID&apikey=${zapApiKey}" | grep -o '"status":"[^"]*"' | grep -o '[0-9]*')
                        echo "Spider scan progress: \${PROGRESS}%"
                        if [ "\$PROGRESS" = "100" ]; then
                            echo "Spider scan completed!"
                            break
                        fi
                        if [ \$WAITED -ge \$MAX_WAIT ]; then
                            echo "WARNING: Spider scan timed out after \${MAX_WAIT}s, proceeding..."
                            break
                        fi
                        sleep 10
                        WAITED=\$((WAITED + 10))
                    done
                """
            }

            stage('Access Target Endpoints') {
                echo "Registering API endpoints with ZAP proxy..."
                sh """
                    curl -s -x http://127.0.0.1:${zapPort} ${targetUrl}/api/v1/attendance || true
                    curl -s -x http://127.0.0.1:${zapPort} ${targetUrl}/api/v1/attendance/search/all || true
                    curl -s -x http://127.0.0.1:${zapPort} "${targetUrl}/api/v1/attendance/search?id=456" || true
                    curl -s -x http://127.0.0.1:${zapPort} "${targetUrl}/api/v1/attendance/search?id=786" || true

                    curl -s -X POST -x http://127.0.0.1:${zapPort} ${targetUrl}/api/v1/attendance/create \\
                        -H "Content-Type: application/json" \\
                        -d '{"id":10002,"status":"Present","date":"2026-01-11","name":"Mukesh Modi"}' || true

                    curl -s -x http://127.0.0.1:${zapPort} ${targetUrl}/api/v1/attendance/health || true
                    curl -s -x http://127.0.0.1:${zapPort} ${targetUrl}/api/v1/attendance/health/detail || true

                    echo "All endpoints registered with ZAP."
                """
            }

            stage('Run Active Scan') {
                echo "Running Active Scan on ${targetUrl}..."
                sh """
                    RESPONSE=\$(curl -sf "http://127.0.0.1:${zapPort}/JSON/ascan/action/scan/?url=${targetUrl}/api/v1/attendance/search/all&apikey=${zapApiKey}")
                    echo "Active scan response: \$RESPONSE"

                    SCAN_ID=\$(echo \$RESPONSE | grep -o '"scan":"[^"]*"' | grep -o '[0-9]*')
                    echo "Active Scan ID: \$SCAN_ID"

                    MAX_WAIT=300
                    WAITED=0
                    while true; do
                        PROGRESS=\$(curl -sf "http://127.0.0.1:${zapPort}/JSON/ascan/view/status/?scanId=\$SCAN_ID&apikey=${zapApiKey}" | grep -o '"status":"[^"]*"' | grep -o '[0-9]*')
                        echo "Active scan progress: \${PROGRESS}%"
                        if [ "\$PROGRESS" = "100" ]; then
                            echo "Active scan completed!"
                            break
                        fi
                        if [ \$WAITED -ge \$MAX_WAIT ]; then
                            echo "WARNING: Active scan timed out after \${MAX_WAIT}s, proceeding..."
                            break
                        fi
                        sleep 15
                        WAITED=\$((WAITED + 15))
                    done
                """
            }

            
            // REPORT
            

            stage('Generate Report') {
                echo "Generating ZAP HTML report..."
                sh """
                    mkdir -p ${reportDir}
                    curl -sf "http://127.0.0.1:${zapPort}/OTHER/core/other/htmlreport/?apikey=${zapApiKey}" \\
                        -o ${reportDir}/zap_attendance_report.html
                    echo "Report saved to ${reportDir}/zap_attendance_report.html"
                    ls -lh ${reportDir}/
                """
            }

            stage('Archive Report') {
                echo "Archiving ZAP report..."
                archiveArtifacts artifacts: "${reportDir}/*.html",
                                 fingerprint: true,
                                 allowEmptyArchive: true
            }

        } catch (Exception err) {

            currentBuild.result = 'FAILURE'
            echo "Pipeline failed: ${err}"

            try {
                slackSend(
                    channel: slackChannel,
                    color: 'danger',
                    message: """
                    FAILED - OWASP ZAP Scan
                    Job:    ${env.JOB_NAME}
                    Build:  #${env.BUILD_NUMBER}
                    URL:    ${env.BUILD_URL}
                    Target: ${targetUrl}
                    """.stripIndent()
                )
            } catch (MissingMethodException | NoSuchMethodError ignored) {
                echo "slackSend not available. Skipping failure notification."
            }

            throw err

        } finally {

            stage('Post Actions') {
                echo "Stopping ZAP and cleaning up..."
                sh "pkill -f zap.sh || true"

                if (currentBuild.result != 'FAILURE') {
                    try {
                        slackSend(
                            channel: slackChannel,
                            color: 'good',
                            message: """
                            SUCCESS - OWASP ZAP Scan
                            Job:    ${env.JOB_NAME}
                            Build:  #${env.BUILD_NUMBER}
                            URL:    ${env.BUILD_URL}
                            Target: ${targetUrl}
                            """.stripIndent()
                        )
                    } catch (MissingMethodException | NoSuchMethodError ignored) {
                        echo "slackSend not available. Skipping success notification."
                    }
                }

                cleanWs()
            }
        }
    }
}