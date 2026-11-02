pipeline {
    agent any

    stages {
        stage('Build analyzer image') {
            steps {
                sh 'docker build -t mainframe-analyzer:$BUILD_NUMBER .'
            }
        }

        stage('Run batch tests') {
            steps {
                sh 'docker run --rm mainframe-analyzer:$BUILD_NUMBER bash test.sh'
            }
        }
    }
}