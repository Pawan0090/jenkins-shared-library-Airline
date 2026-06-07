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
                    echo 'Pulling source code from Git...'
                    checkout scm
                }
            }

            stage('2. Backend: Build & Test') {
                steps {
                    echo 'Compiling Java classes and executing JUnit unit tests...'
                    dir('backend') {
                        sh 'mvn clean package'
                    }
                }
            }

            stage('3. Backend: SonarQube Scan') {
                steps {
                    echo 'Scanning backend source code for bugs and quality gates...'
                    dir('backend') {
                        withSonarQubeEnv("${config.sonarServerId}") {
                            sh "mvn sonar:sonar -Dsonar.projectKey=${config.sonarProjectKey}"
                        }
                    }
                }
            }

            stage('4. Backend: Quality Gate') {
                steps {
                    echo 'Checking if backend meets SonarQube standards...'
                    timeout(time: 5, unit: 'MINUTES') {
                        waitForQualityGate abortPipeline: true
                    }
                }
            }

            stage('5. Dependency Scan') {
                steps {
                    echo 'Scanning backend project dependencies using Trivy...'
                    dir('backend') {
                        sh 'trivy fs --severity HIGH,CRITICAL --exit-code 1 .'
                    }
                }
            }

            stage('6. Docker: Build Images') {
                steps {
                    echo 'Building Backend Docker Image...'
                    dir('backend') {
                        sh "docker build -t ${config.backendImage}:${env.IMAGE_TAG} ."
                    }
                    
                    echo 'Building Frontend Docker Image...'
                    dir('frontend') {
                        sh "docker build -t ${config.frontendImage}:${env.IMAGE_TAG} ."
                    }
                }
            }

            stage('7. Docker: Trivy Image Scan') {
                steps {
                    echo 'Scanning Backend Docker image for vulnerabilities...'
                    sh "trivy image --severity HIGH,CRITICAL --exit-code 1 ${config.backendImage}:${env.IMAGE_TAG}"
                    
                    echo 'Scanning Frontend Docker image for vulnerabilities...'
                    sh "trivy image --severity HIGH,CRITICAL --exit-code 1 ${config.frontendImage}:${env.IMAGE_TAG}"
                }
            }

            stage('8. Gate: Manual Approval') {
                steps {
                    echo 'Halting execution. Waiting for operator sign-off...'
                    input message: "Approve deployment of backend & frontend version ${env.IMAGE_TAG}?", ok: "Deploy Containers"
                }
            }

            stage('9. Release & Deploy') {
                steps {
                    echo 'Logging into Docker Hub and pushing secure images...'
                    withCredentials([usernamePassword(credentialsId: config.dockerCredsId, passwordVariable: 'DOCKER_PASS', usernameVariable: 'DOCKER_USER')]) {
                        sh "echo \$DOCKER_PASS | docker login -u \$DOCKER_USER --password-stdin"
                        sh "docker push ${config.backendImage}:${env.IMAGE_TAG}"
                        sh "docker push ${config.frontendImage}:${env.IMAGE_TAG}"
                    }
                    
                    echo 'Deploying containers locally onto runtime environment...'
                    // Deploying Backend
                    sh "docker stop aeroflight-backend || true"
                    sh "docker rm aeroflight-backend || true"
                    sh "docker run -d -p 8080:8080 --name aeroflight-backend ${config.backendImage}:${env.IMAGE_TAG}"
                    
                    // Deploying Frontend
                    sh "docker stop aeroflight-frontend || true"
                    sh "docker rm aeroflight-frontend || true"
                    sh "docker run -d -p 80:80 --name aeroflight-frontend ${config.frontendImage}:${env.IMAGE_TAG}"
                }
            }
        }

        post {
            always {
                echo 'Cleaning up locally compiled Docker images and workspace...'
                sh "docker rmi ${config.backendImage}:${env.IMAGE_TAG} || true"
                sh "docker rmi ${config.frontendImage}:${env.IMAGE_TAG} || true"
                cleanWs()
            }
            success {
                echo 'SUCCESS: Distributed application infrastructure updated seamlessly!'
            }
            failure {
                echo 'FAILURE: Pipeline execution halted due to quality check or security breach.'
            }
        }
    }
}
