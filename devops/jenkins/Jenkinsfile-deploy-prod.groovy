@Library('utils') _  // Carga la biblioteca 'utils'

pipeline {
    agent any

    tools {
        jdk 'jdk17'
        maven 'maven3'
    }

    environment {
        NAME_APP = 'discovery-service-pro'
        CONTAINER_PORT = '8761'
        HOST_PORT = '8761'
        NETWORK = 'azure-net'
        LOCAL_CONFIG_SERVER = 'localhost'
        CONFIG_SERVER = "config-server-pro"
        PORT_CONFIG_SERVER = "8888"
        GIT_CREDENTIALS = credentials('github-token')
        GIT_COMMITTER_NAME = 'josephmn'
        GIT_COMMITTER_EMAIL = 'josephcarlos.jcmn@gmail.com'
        NEW_VERSION = ''
    }

    stages {
        stage('Compile Repository') {
            steps {
                script {
                    if (params.NOTIFICATION) {
                        notifyJob('JOB Jenkins', params.CORREO)
                    }
                    echo "######################## : ======> EJECUTANDO COMPILE..."
                    // Usar 'bat' para ejecutar comandos en Windows, para Linux usar 'sh'
                    bat """
                        mvn clean compile \
                        -DCONFIG_SERVER=http://${LOCAL_CONFIG_SERVER}:${PORT_CONFIG_SERVER}
                    """
                }
            }
        }

        stage('Get Version') {
            steps {
                echo "######################## : ======> OBTENER ARCHIVO TXT VERSION..."
                script {
                    // Obtener la versión en Windows usando un archivo temporal
                    bat '''
                        mvn help:evaluate -Dexpression=project.version -q -DforceStdout > version.txt
                    '''
                    def version = readFile('version.txt').trim()
                    // Remover -SNAPSHOT si existe
                    version = version.replaceAll("-SNAPSHOT", "")
                    NEW_VERSION = version
                }
            }
        }

        stage('Merge RC into Main') {
            when {
                expression { params.MERGE_MAIN }
            }
            steps {
                echo "######################## : ======> CREANDO MERGE A RAMA MASTER..."
                script {
                    echo "=========> Git config..."
                    bat """
                        git config user.email "${GIT_COMMITTER_EMAIL}"
                        git config user.name "${GIT_COMMITTER_NAME}"
                    """

                    echo "=========> Pull a rama master..."
                    bat """
                        git checkout main
                        git pull origin main
                    """

                    echo "=========> Merge de rama RC a rama main con sobrescritura......"
                    bat """
                        git merge -X theirs ${RELEASE_TAG_NAME} || (
                            echo "Conflictos detectados. Sobrescribiendo con contenido de ${RELEASE_TAG_NAME}..."
                            git checkout --theirs .
                            git add .
                            git commit -m "Merge ${RELEASE_TAG_NAME} into main con preferencia por theirs"
                        )
                    """

                    echo "=========> Push a rama main..."
                    bat """
                        git push origin main
                    """
                }
            }
        }

        stage('Generate Next Version') {
            when {
                expression { params.NEXT_VERSION }
            }
            steps {
                echo "######################## : ======> GENERANDO NUEVA VERSION SNAPSHOT..."
                script {
                    echo "=========> Git config..."
                    bat """
                        git config user.email "${GIT_COMMITTER_EMAIL}"
                        git config user.name "${GIT_COMMITTER_NAME}"

                        REM Configurar remote con token para HTTPS
                        git remote set-url origin https://%GIT_CREDENTIALS%@github.com/josephmn/config-server.git
                    """

                    echo "=========> Generar siguiente SNAPSHOT..."
                    bat """
                        git checkout develop
                        git pull origin develop
                    """

                    /*echo "=========> Revisar el repositorio antes del prepare..."
                    bat """
                        git status
                        git remote -v
                        git config --list
                    """
                     */

                    echo "=========> Ejecutando Maven Release Plugin: prepare..."
                    bat """
                        mvn release:prepare -B \
                        -Dusername=${GIT_COMMITTER_NAME} \
                        -Dpassword=%GIT_CREDENTIALS% \
                        -DscmCommentPrefix="[maven-release-plugin]"
                    """

                    echo "=========> Ejecutando Maven Release Plugin: perform..."
                    bat """
                        mvn release:perform \
                        -Darguments="-DskipTests" \
                        -DlocalCheckout=false \
                        -B
                    """
                }
            }
        }

        stage('Build Application with Maven') {
            steps {
                echo "######################## : ======> EJECUTANDO BUILD APPLICATION MAVEN..."
                // Usar 'bat' para ejecutar comandos en Windows, para Linux usar 'sh'
                bat """
                    git checkout ${RELEASE_TAG_NAME}
                """

                bat """
                    mvn clean install \
                    -Dspring-boot.run.profiles=prod \
                    -DCONFIG_SERVER=http://${LOCAL_CONFIG_SERVER}:${PORT_CONFIG_SERVER}
                """
            }
        }

        stage('Creating Network for Docker') {
            when {
                expression { params.DOCKER }
            }
            steps {
                echo "######################## : ======> EJECUTANDO CREACION DE RED PARA DOCKER: ${NETWORK}..."
                script {
                    def networkExists = bat(
                            script: "docker network ls | findstr ${NETWORK}",
                            returnStatus: true
                    )
                    if (networkExists != 0) {
                        echo "######################## : ======> La red '${NETWORK}' no existe. Creándola..."
                        bat "docker network create --attachable ${NETWORK}"
                    } else {
                        echo "######################## : ======> La red '${NETWORK}' ya existe. No es necesario crearla."
                    }
                }
            }
        }

        stage('Docker Build and Run') {
            when {
                expression { params.DOCKER }
            }
            steps {
                echo "######################## : ======> EJECUTANDO DOCKER BUILD AND RUN..."
                script {
                    echo "=========> Verificando que el config-server esté en ejecución..."
                    // Verificar si el config-server está en ejecución
                    bat """
                    for /L %%i in (1,1,30) do (
                        powershell -Command "(Invoke-WebRequest -Uri http://${LOCAL_CONFIG_SERVER}:${PORT_CONFIG_SERVER}/actuator/health -UseBasicParsing).StatusCode" && exit || timeout 5
                    )
                    """

                    echo "=========> VERSION RC: ${NEW_VERSION}"
                    echo "=========> APLICATIVO + VERSION: ${NAME_APP}:${NEW_VERSION}"
                    // Usar la versión capturada para los comandos Docker

                    // Verificar si existe el contenedor
                    def containerExists = bat(
                            script: "@docker ps -a --format '{{.Names}}' | findstr /i \"${NAME_APP}\"",
                            returnStatus: true
                    ) == 0

                    // Verificar si existe la imagen
                    def imageExists = bat(
                            script: "@docker images ${NAME_APP}:${NEW_VERSION} --format '{{.Repository}}:{{.Tag}}' | findstr /i \"${NAME_APP}:${NEW_VERSION}\"",
                            returnStatus: true
                    ) == 0

                    if (containerExists || imageExists) {
                        echo "=========> Se encontraron recursos existentes, procediendo a limpiarlos..."

                        if (containerExists) {
                            echo "=========> Eliminando contenedor existente: ${NAME_APP}"
                            bat "docker stop ${NAME_APP}"
                            bat "docker rm ${NAME_APP}"
                        }

                        if (imageExists) {
                            echo "=========> Eliminando imagen existente: ${NAME_APP}"
                            bat "docker rmi ${NAME_APP}:${NEW_VERSION}"
                        }
                    } else {
                        echo "=========> No se encontraron recursos existentes, procediendo con el despliegue..."
                    }

                    echo "=========> VERSION A DESPLEGAR: ${NEW_VERSION}"
                    echo "=========> APLICATIVO + VERSION: ${NAME_APP}:${NEW_VERSION}"

                    def name = NAME_APP.tokenize('-')[0..-2].join('-')
                    bat """
                        echo "=========> Construyendo nueva imagen con version ${NEW_VERSION}..."
                        docker build --build-arg NAME_APP=${NAME_APP} --build-arg JAR_VERSION=${NEW_VERSION} -t ${NAME_APP}:${NEW_VERSION} .
                    """
                    bat """
                        echo "=========> Desplegando el contenedor: ${NAME_APP}..."
                        docker run -d --name ${NAME_APP} -p ${HOST_PORT}:${CONTAINER_PORT} --network=${NETWORK} ^
                        --env CONFIG_SERVER=http://${CONFIG_SERVER}:${PORT_CONFIG_SERVER} ^
                        --env SPRING_PROFILES_ACTIVE=prod ^
                        ${NAME_APP}:${NEW_VERSION}
                    """
                }
            }
        }
    }

    post {
        success {
            script {
                if (params.NOTIFICATION) {
                    notifyByMail('SUCCESS', params.CORREO)
                }
            }
        }
        failure {
            script {
                if (params.NOTIFICATION) {
                    notifyByMail('FAIL', params.CORREO)
                }
            }
        }
    }
}
