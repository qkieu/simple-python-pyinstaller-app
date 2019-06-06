pipeline {
    agent none
    stages {
        stage('Build') {
            input {
                message "What what?"
                ok "Just go on."
                submitter "juba"
                parameters {
                    string(name: 'DEPLOY_ENV', defaultValue: 'staging', description: '')
                    text(name: 'DEPLOY_TEXT', defaultValue: 'One\nTwo\nThree\n', description: '')
                    booleanParam(name: 'DEBUG_BUILD', defaultValue: true, description: '')
                    choice(name: 'CHOICES', choices: ['one', 'two', 'three'], description: '')
                    file(name: 'FILE', description: 'Some file to upload')
                    password(name: 'PASSWORD', defaultValue: 'SECRET', description: 'A secret password')
                }
            }
            agent {
                docker {
                    image 'python:2-alpine'
                }
            }
            steps {
                sh 'python -m py_compile sources/add2vals.py sources/calc.py'
            }
        }
        stage('Test-Test') {
            agent {
                docker {
                    image 'qnib/pytest'
                }
            }
            steps {
                sh 'py.test -v --junit-xml test-reports/results.xml sources/test_calc.py'
            }
            post {
                always {
                    junit 'test-reports/results.xml'
                }
            }
        }
        stage('Delivery') {
            agent {
                docker {
                    image 'cdrx/pyinstaller-linux:python2'
                }
            }
            steps {
                sh 'pyinstaller --onefile sources/add2vals.py'
            }
            post {
                success {
                    archiveArtifacts 'dist/add2vals'
                }
            }
        }
    }
}