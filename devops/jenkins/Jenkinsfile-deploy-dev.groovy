pipeline {
    agent any

    tools {
        jdk 'jdk17'
        maven 'maven3'
    }

    environment {
        SCANNER_HOME = tool 'sonar-scanner'
        // NVD_API_KEY = credentials('NVD_API_KEY') // Carga la API Key desde las credenciales de Jenkins
    }

    stages {
        // stage('Git Checkout') {
        //     steps {
        //         git branch: 'develop', changelog: false, poll: false, url: 'https://github.com/josephmn/discovery-service.git'
        //     }
        // }
        stage('Compile') {
            steps {
                // Usar 'bat' para ejecutar comandos en Windows, para Linux usar 'sh'
                bat 'mvn clean compile'
            }
        }
        stage('SonarQube') {
            steps {
                withSonarQubeEnv('sonar-server') {
                    withCredentials([string(credentialsId: 'sonar-token', variable: 'SONAR_TOKEN')]) {
                        bat """
                            "${SCANNER_HOME}\\bin\\sonar-scanner" ^
                            -Dsonar.url=http://localhost:9000/ ^
                            -Dsonar.login=${SONAR_TOKEN} ^
                            -Dsonar.projectName=discovery-service ^
                            -Dsonar.java.binaries=. ^
                            -Dsonar.projectKey=discovery-service
                        """
                    }
                }
            }
        }
        stage('OWASP Scan') {
            steps {
                // dependencyCheck additionalArguments: '--scan ./ --format HTML', odcInstallation: 'DP'
                // dependencyCheck additionalArguments: "--scan ./ --nvdApiKey=${NVD_API_KEY}", odcInstallation: 'DP' // con API KEY
                dependencyCheck additionalArguments: '--scan ./ ', odcInstallation: 'DP' // usaba este
                // dependencyCheck additionalArguments: '--scan ./ --disableCentral --disableRetired --disableExperimental', odcInstallation: 'DP'
                dependencyCheckPublisher pattern: '**/dependency-check-report.xml'
            }
        }
        stage('Build Application') {
            steps {
                // Usar 'bat' para ejecutar comandos en Windows, para Linux usar 'sh'
                bat 'mvn clean install'
            }
        }
        stage('Docker Build and Run') {
            steps {
                script {
                    // Nombre de la imagen Docker
                    def localhost = "localhost"
                    def containerName = "discovery-service"
                    def imageName = "discovery-service"
                    def configServer = "config-server"
                    def portConfigServer = "8888"

                    // Verificar si el config-server está en ejecución
                    bat """
                    echo "Verificando que el config-server esté en ejecución..."
                    for /L %%i in (1,1,30) do (
                        powershell -Command "(Invoke-WebRequest -Uri http://${localhost}:${portConfigServer}/actuator/health -UseBasicParsing).StatusCode" && exit || timeout 5
                    )
                    """

                    // Eliminar el contenedor existente si ya está en ejecución
                    bat "docker rm -f ${containerName} || true"

                    // Eliminar la imagen existente si ya existe
                    bat "docker rmi -f ${imageName} || true"

                    // Construir la imagen Docker usando el Dockerfile en la raíz del proyecto
                    bat "docker build -t ${imageName}:1.0 ."

                    // Ejecutar el contenedor de la imagen recién creada
                    bat "docker run -d --name ${containerName} -p 8761:8761 --network=azure-net --env CONFIG_SERVER=http://${configServer}:${portConfigServer} ${imageName}:1.0"
                }
            }
        }

    }
}
