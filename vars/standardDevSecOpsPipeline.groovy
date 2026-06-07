Skip to content
Pawan0090
jenkins-shared-library-Airline
Repository navigation
Code
Issues
Pull requests
Agents
Actions
Projects
Wiki
Security and quality
Insights
Settings
Files
Go to file
t
T
vars
standardDevSecOpsPipeline.groovy
jenkins-shared-library-Airline/vars
/
standardDevSecOpsPipeline.groovy
in
main

Edit

Preview
Indent mode

Spaces
Indent size

4
Line wrap mode

No wrap
Editing standardDevSecOpsPipeline.groovy file contents
  1
  2
  3
  4
  5
  6
  7
  8
  9
 10
 11
 12
 13
 14
 15
 16
 17
 18
 19
 20
 21
 22
 23
 24
 25
 26
 27
 28
 29
 30
 31
 32
 33
 34
 35
 36
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
Use Control + Shift + m to toggle the tab key moving focus. Alternatively, use esc then tab to move to the next interactive element on the page.
