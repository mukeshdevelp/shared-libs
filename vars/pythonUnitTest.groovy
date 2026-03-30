def call(Map config = [:]) {

    def repoUrl         = config.repoUrl         ?: "https://github.com/mukeshdevelp/ot-microservice-sarthi.git"
    def branch          = config.branch          ?: "backend"
    def reportDir       = config.reportDir       ?: "reports"
    def attendanceDir   = config.attendanceDir   ?: "attendance/attendance_api"
    def notificationDir = config.notificationDir ?: "notification/notification-worker"
    def slackChannel    = config.slackChannel    ?: "#ci-operation-notifications"

    node {

        try {

            
            // SETUP
        

            stage('Clean Workspace') {
                deleteDir()
            }

            stage('Checkout Code') {
                git url: repoUrl, branch: branch
            }

            stage('Setup Python') {
                sh '''
                    echo "Checking Python versions..."
                    python3 --version
                    python3.11 --version || true
                    python3 -m pip --version
                '''
            }

           
            // ATTENDANCE API
            

            stage('Install Attendance Dependencies') {
                dir(attendanceDir) {
                    sh '''
                        pwd
                        ls -al
                        if ! command -v poetry &> /dev/null; then
                            echo "Poetry not found. Installing..."
                            curl -sSL https://install.python-poetry.org | python3 -
                        else
                            echo "Poetry already installed."
                        fi
                    '''
                }
            }

            stage('Install Attendance Poetry Dependencies') {
                dir(attendanceDir) {
                    sh '''
                        export PATH=$HOME/.local/bin:$PATH
                        ls -al
                        poetry config virtualenvs.create true
                        poetry lock --regenerate
                        poetry install --no-root --no-interaction --no-ansi
                    '''
                }
            }

            stage('Lint Attendance Code') {
                dir(attendanceDir) {
                    sh '''
                        echo "Linting Attendance API with Pylint..."
                        export PATH=$HOME/.local/bin:$PATH
                        poetry run pylint router/ client/ models/ utils/ app.py || true
                        echo "Linting complete."
                    '''
                }
            }

            stage('Run Attendance Tests + Coverage') {
                dir(attendanceDir) {
                    sh """
                        echo "Running Attendance API tests..."
                        export PATH=\$HOME/.local/bin:\$PATH
                        export PYTHONPATH=\$(pwd)

                        mkdir -p ../${reportDir}/attendance

                        export COVERAGE_FILE=.coverage

                        poetry run pytest . -v \\
                            --cov=. \\
                            --cov-report=html:../${reportDir}/attendance/htmlcov \\
                            --ignore=client/tests/test_postgres_conn.py \\
                            --ignore=client/tests/test_redis_conn.py || true

                        echo "Attendance tests complete."
                    """
                }
            }

            // ─────────────────────────────────────────
            // NOTIFICATION WORKER
            // ─────────────────────────────────────────

            stage('Install Notification Dependencies') {
                dir(notificationDir) {
                    sh '''
                        echo "Setting up virtual environment for Notification service..."
                        python3 -m venv venv
                        venv/bin/python -m pip install --upgrade pip
                        venv/bin/pip install -r requirements.txt
                        venv/bin/pip install pytest pytest-cov
                        echo "Notification dependencies installed."
                    '''
                }
            }

            stage('Lint Notification Code') {
                dir(notificationDir) {
                    sh '''
                        echo "Linting Notification Worker with Pylint..."
                        venv/bin/pip install pylint || true
                        venv/bin/python -m pylint . --ignore=venv || true
                        echo "Notification linting complete."
                    '''
                }
            }

            stage('Run Notification Tests + Coverage') {
                dir(notificationDir) {
                    sh """
                        echo "Running Notification Worker tests..."

                        REPORT_PATH=\${WORKSPACE}/${reportDir}/notification
                        mkdir -p \$REPORT_PATH

                        venv/bin/python -m pytest tests/ -v \\
                            --cov=. \\
                            --cov-report=html:\$REPORT_PATH/htmlcov || true

                        echo "Listing generated reports..."
                        ls -R \$REPORT_PATH || true
                    """
                }
            }

            // ─────────────────────────────────────────
            // ARCHIVE
            // ─────────────────────────────────────────

            stage('Archive Reports') {
                archiveArtifacts artifacts: "${reportDir}/**",
                                 fingerprint: true,
                                 allowEmptyArchive: true
            }

        } catch (err) {

            // ── Slack failure notification ────────────
            try {
                slackSend(
                    channel: slackChannel,
                    color: 'danger',
                    message: """
Build Failed 

Job:   ${env.JOB_NAME}
Build: #${env.BUILD_NUMBER}
URL:   ${env.BUILD_URL}
                    """.stripIndent()
                )
            } catch (MissingMethodException | NoSuchMethodError ignored) {
                echo "slackSend not available. Skipping failure notification."
            }

            error "Pipeline failed: ${err}"

        } finally {

            // ── Slack success notification ────────────
            if (currentBuild.currentResult == 'SUCCESS') {
                try {
                    slackSend(
                        channel: slackChannel,
                        color: 'good',
                        message: """
Build Successful 

Job:   ${env.JOB_NAME}
Build: #${env.BUILD_NUMBER}
URL:   ${env.BUILD_URL}
                        """.stripIndent()
                    )
                } catch (MissingMethodException | NoSuchMethodError ignored) {
                    echo "slackSend not available. Skipping success notification."
                }
            }
        }
    }
}