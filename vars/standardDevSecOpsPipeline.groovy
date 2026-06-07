def call(Map config = [:]) {
    pipeline {
        agent any

        environment {
            IMAGE_TAG = "v${env.BUILD_ID}"
        }

        stages {
            stage('1. Checkout Code') {
                steps {
                    checkout scm
                }
            }

            stage('2. Build & Unit Test') {
                steps {
                    dir('backend') {
                        sh 'mvn clean package -DskipTests'
                    }
                }
            }

            stage('3. SonarQube Code Scan') {
                steps {
                    dir('backend') {
                        withSonarQubeEnv("${config.sonarServerId}") {
                            sh "mvn sonar:sonar -Dsonar.projectKey=${config.sonarProjectKey}"
                        }
                    }
                }
            }

            stage('4. Quality Gate') {
                steps {
                    timeout(time: 5, unit: 'MINUTES') {
                        waitForQualityGate abortPipeline: true
                    }
                }
            }

            stage('5. Trivy FS Dependency Scan') {
                steps {
                    dir('backend') {
                        sh 'trivy fs --severity HIGH,CRITICAL --exit-code 1 .'
                    }
                }
            }

            stage('6. Build Docker Images') {
                steps {
                    // Backend Build
                    dir('backend') {
                        sh "docker build -t ${config.backendImage}:${env.IMAGE_TAG} ."
                    }
                    dir('.') { // Go to the absolute root of the project
                    sh 'find . -name "package.json"' // This will find every package.json in the entire rep

                    // Frontend Build with Dynamic Path Detection
                    dir('frontend') {
                        // This command finds where package.json is and builds from that directory
                        sh '''
                            PKG_DIR=$(find . -name "package.json" -exec dirname {} \\;)
                            if [ -z "$PKG_DIR" ]; then
                                echo "ERROR: package.json not found in frontend directory"
                                exit 1
                            fi
                            echo "Found package.json in: $PKG_DIR"
                            cd "$PKG_DIR"
                            docker build -t ${config.frontendImage}:${env.IMAGE_TAG} .
                        '''
                    }
                }
            }

            stage('7. Trivy Image Scan') {
                steps {
                    sh "trivy image --severity HIGH,CRITICAL --exit-code 1 ${config.backendImage}:${env.IMAGE_TAG}"
                    sh "trivy image --severity HIGH,CRITICAL --exit-code 1 ${config.frontendImage}:${env.IMAGE_TAG}"
                }
            }

            stage('8. Gate: Manual Approval') {
                steps {
                    input message: "Images tested and scanned. Deploy version ${env.IMAGE_TAG} to Remote Docker Server?",
                          ok: "Deploy Now"
                }
            }

            stage('9. Remote Deployment to Docker Server') {
                steps {
                    withCredentials([
                        usernamePassword(
                            credentialsId: config.dockerCredsId,
                            usernameVariable: 'DOCKER_USER',
                            passwordVariable: 'DOCKER_PASS'
                        )
                    ]) {
                        sh 'echo "$DOCKER_PASS" | docker login -u "$DOCKER_USER" --password-stdin'
                        sh "docker push ${config.backendImage}:${env.IMAGE_TAG}"
                        sh "docker push ${config.frontendImage}:${env.IMAGE_TAG}"
                    }

                    sshagent(['docker-server-ssh']) {
                        sh """
                            ssh -o StrictHostKeyChecking=no ubuntu@${config.dockerServerIp} '
                                docker pull ${config.backendImage}:${env.IMAGE_TAG}
                                docker pull ${config.frontendImage}:${env.IMAGE_TAG}
                                docker stop aeroflight-backend || true
                                docker rm aeroflight-backend || true
                                docker stop aeroflight-frontend || true
                                docker rm aeroflight-frontend || true
                                docker run -d -p 8080:8080 --name aeroflight-backend ${config.backendImage}:${env.IMAGE_TAG}
                                docker run -d -p 80:80 --name aeroflight-frontend ${config.frontendImage}:${env.IMAGE_TAG}
                            '
                        """
                    }
                }
            }
        }

        post {
            always {
                sh "docker rmi ${config.backendImage}:${env.IMAGE_TAG} || true"
                sh "docker rmi ${config.frontendImage}:${env.IMAGE_TAG} || true"
                cleanWs()
            }
        }
    }
}
