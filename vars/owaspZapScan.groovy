def call(Map config = [:]) {

    def targetIp     = config.targetIp     ?: "98.92.250.177"
    def targetPort   = config.targetPort   ?: "8081"
    def targetUrl    = config.targetUrl    ?: "http://${targetIp}:${targetPort}/"
    def zapPort      = config.zapPort      ?: "9000"
    def zapApiKey    = config.zapApiKey    ?: "attendancekey"
    def reportDir    = config.reportDir    ?: "zap-reports"
    def zapDir       = config.zapDir       ?: "zap"
    def slackChannel = config.slackChannel ?: "#ci-operation-notifications"

    node {

        try {

          
            // SETUP
       

            stage('Clean Workspace') {
                echo "Cleaning workspace..."
                cleanWs()
            }

            stage('Setup Java 17') {
                echo "Downloading Java 17..."
                sh '''
                    mkdir -p java
                    cd java
                    wget -q https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.12+7/OpenJDK17U-jdk_x64_linux_hotspot_17.0.12_7.tar.gz
                    tar -xzf OpenJDK17U-jdk_x64_linux_hotspot_17.0.12_7.tar.gz
                    echo "Java 17 extracted successfully"
                    ls -l
                '''
            }

            stage('Download OWASP ZAP') {
                echo "Downloading OWASP ZAP..."
                sh """
                    mkdir -p ${zapDir}
                    cd ${zapDir}
                    wget -q https://github.com/zaproxy/zaproxy/releases/download/v2.17.0/ZAP_2.17.0_Linux.tar.gz
                    tar -xzf ZAP_2.17.0_Linux.tar.gz
                    echo "ZAP extracted successfully"
                    ls -l
                """
            }

      
            // ZAP DAEMON
     

            stage('Start ZAP') {
                echo "Starting ZAP daemon..."
                sh """
                    JAVA_DIR=\$(ls -d java/jdk-17* | head -n 1)
                    export JAVA_HOME=\$PWD/\$JAVA_DIR
                    export PATH=\$JAVA_HOME/bin:\$PATH

                    echo "Using JAVA_HOME=\$JAVA_HOME"
                    java -version

                    ZAP_PATH=\$(find ${zapDir} -name zap.sh | head -n 1)

                    if [ -z "\$ZAP_PATH" ]; then
                        echo "ERROR: zap.sh not found!"
                        ls -R ${zapDir}
                        exit 1
                    fi

                    echo "Using ZAP from: \$ZAP_PATH"
                    export JAVA_OPTS="-Xmx1024m"

                    nohup \$ZAP_PATH -daemon \\
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

           
            // SCANNING
          

            stage('Run Spider Scan') {
                echo "Running Spider Scan on ${targetUrl}..."
                sh """
                    RESPONSE=\$(curl -sf "http://127.0.0.1:${zapPort}/JSON/spider/action/scan/?url=${targetUrl}&apikey=${zapApiKey}")
                    echo "Spider scan response: \$RESPONSE"
                    echo "Spider scan triggered."
                """
            }

            stage('Access Target Endpoints') {
                echo "Registering API endpoints with ZAP proxy..."
                sh """
                    curl -s -x http://127.0.0.1:${zapPort} ${targetUrl} || true

                    curl -s -x http://127.0.0.1:${zapPort} ${targetUrl}attendance/search/all || true
                    curl -s -x http://127.0.0.1:${zapPort} "${targetUrl}attendance/search?id=1" || true
                    curl -s -x http://127.0.0.1:${zapPort} "${targetUrl}attendance/search?id=2" || true

                    curl -s -X POST -x http://127.0.0.1:${zapPort} ${targetUrl}attendance/create \\
                        -H "Content-Type: application/json" \\
                        -d '{"name":"test","status":"present"}' || true

                    curl -s -X PUT -x http://127.0.0.1:${zapPort} ${targetUrl}attendance/update \\
                        -H "Content-Type: application/json" \\
                        -d '{"id":1,"status":"absent"}' || true

                    curl -s -x http://127.0.0.1:${zapPort} ${targetUrl}attendance/test || true

                    echo "All endpoints registered with ZAP."
                """
            }

            stage('Run Active Scan') {
                echo "Running Active Scan on ${targetUrl}..."
                sh """
                    RESPONSE=\$(curl -sf "http://127.0.0.1:${zapPort}/JSON/ascan/action/scan/?url=${targetUrl}attendance/search/all&apikey=${zapApiKey}")
                    echo "Active scan response: \$RESPONSE"
                    echo "Active scan triggered."
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
                sh """
                    pkill -f zap.sh || true
                    rm -rf ${zapDir} || true
                """

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