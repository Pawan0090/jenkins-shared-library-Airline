def call(Map config = [:]) {
    pipeline {
        agent any

        environment {
            IMAGE_TAG = "v${env.BUILD_ID}"
        }

        tools {
            maven 'Maven-3.9' 
            jdk 'Java-21'
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
                    dir('backend') {
                        sh "docker build -t ${config.backendImage}:${env.IMAGE_TAG} ."
                    }
                        dir('frontend') {
                        sh 'docker build -t dockerpawan09/aeroflight-frontend:v${BUILD_NUMBER} .'
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
                    input message: "Deploy version ${env.IMAGE_TAG}?", ok: "Deploy Now"
                }
            }

            stage('9. Remote Deployment') {
                steps {
                    withCredentials([usernamePassword(credentialsId: config.dockerCredsId, usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')]) {
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
