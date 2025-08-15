@Library('utils') _  // Carga la biblioteca 'utils'

pipeline {
    agent any

    tools {
        jdk 'jdk17'
        maven 'maven3'
    }

    environment {
        NAME_APP = 'discovery-service-cer'
        CONTAINER_PORT = '8760'
        HOST_PORT = '8760'
        NETWORK = 'azure-net-cer'
        LOCAL_CONFIG_SERVER = 'localhost'
        CONFIG_SERVER = "config-server-cer"
        PORT_CONFIG_SERVER = "8887"
        GIT_CREDENTIALS = credentials('github-token')
        GIT_COMMITTER_NAME = 'josephmn'
        GIT_COMMITTER_EMAIL = 'josephcarlos.jcmn@gmail.com'
        NEW_VERSION = ''
    }

    stages {
        stage('Setup Git Configuration') {
            steps {
                script {
                    echo "######################## : ======> CONFIGURANDO GIT..."

                    bat """
                        git config user.email "${GIT_COMMITTER_EMAIL}"
                        git config user.name "${GIT_COMMITTER_NAME}"

                        REM Configurar remote con token para HTTPS
                        git remote set-url origin https://%GIT_CREDENTIALS%@github.com/josephmn/discovery-service.git
                    """

                    // Verificar conexión
                    echo "Verificando conexión con repositorio..."
                    bat 'git remote -v'
                    bat 'git status'

                    // Probar conectividad
                    bat 'git fetch origin --dry-run'
                }
            }
        }

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

        stage('Delete Release Branch') {
            steps {
                echo "######################## : ======> ELIMINANDO RAMA RELEASE..."
                script {
                    // Obtener la versión en Windows usando un archivo temporal
                    bat '''
                        mvn help:evaluate -Dexpression=project.version -q -DforceStdout > version.txt
                    '''
                    def version = readFile('version.txt').trim()
                    // Remover -SNAPSHOT si existe
                    version = version.replaceAll("-SNAPSHOT", "")
                    NEW_VERSION = version

                    echo "======> Eliminando rama release: release/${version}..."

                    // Fetch para actualizar referencias
                    bat 'git fetch --prune origin'

                    // Verificar si la rama local existe
                    def localBranchExists = bat(
                            script: """git show-ref refs/heads/release/${version}""",
                            returnStatus: true
                    ) == 0

                    if (localBranchExists) {
                        echo "=========> La rama existe en local, procediendo a eliminarla..."
                        try {
                            bat "git branch -d release/${version}"
                            echo "=========> Rama local 'release/${version}' eliminada."
                        } catch (Exception e) {
                            echo "=========> La rama local 'release/${version}' no existe."
                        }
                    }

                    // Verificar si la rama remota existe
                    def branchExists = bat(
                            script: "git show-ref --verify --quiet refs/remotes/origin/release/${version}",
                            returnStatus: true
                    ) == 0

                    if (branchExists) {
                        echo "=========> La rama existe en remoto, procediendo a eliminarla..."
                        try {
                            bat "git push origin --delete release/${version}"
                            echo "=========> Rama remota release/${version} eliminada"
                        } catch (Exception e) {
                            echo "=========> Error eliminando rama remota: ${e.getMessage()}"
                        }
                    }
                }
            }
        }

        stage('Create Release Branch') {
            steps {
                timeout(time: 5, unit: 'MINUTES') {
                    echo "######################## : ======> CREANDO RAMA PARA LA RELEASE..."
                    script {
                        bat """
                            git config user.email "${GIT_COMMITTER_EMAIL}"
                            git config user.name "${GIT_COMMITTER_NAME}"
    
                            echo "=========> Creando rama de release..."
                            git checkout -b release/${NEW_VERSION}
                            git push origin release/${NEW_VERSION}
                        """

                        bat """
                            mvn versions:set -DnewVersion=${NEW_VERSION}
                        """

                        bat """
                            mvn versions:commit
                        """

                        bat """
                            git add .
                            git commit -m "RC version ${NEW_VERSION}"
                            git push origin release/${NEW_VERSION}
                        """
                    }
                }
            }
        }

//        stage('Delete Last Tag Release Candidate') {
//            steps {
//                echo "######################## : ======> ELIMINANDO EL ULTIMO TAG QUE CONTIENE LA NUEVA VERSION: '${NEW_VERSION}'..."
//                script {
//                    def lastTag = bat(
//                            script: "@git tag -l \"*${NEW_VERSION}*\" --sort=-v:refname",
//                            returnStdout: true
//                    ).trim()
//
//                    if (lastTag?.trim()) {  // Validamos que lastTag no sea null y no esté vacío
//                        echo "=========> Ultimo tag encontrado: ${lastTag}"
//
//                        try {
//                            // Borrar el tag localmente
//                            bat "git tag -d ${lastTag}"
//                            echo "=========> Tag eliminado localmente"
//
//                            // Borrar el tag en el repositorio remoto
//                            bat "git push origin --delete ${lastTag}"
//                            echo "=========> Tag eliminado del repositorio remoto"
//                        } catch (Exception e) {
//                            error "Error al eliminar el tag: ${e.getMessage()}"
//                        }
//                    } else {
//                        echo "=========> No se encontro ningun tag con version: RC-${NEW_VERSION}-CERT-*"
//                    }
//                }
//            }
//        }

        stage('Create Release Candidate') {
            steps {
                timeout(time: 3, unit: 'MINUTES') {
                    echo "######################## : ======> GENERANDO RELEASE CANDIDATE (RC-${NEW_VERSION}-CERT-*)..."
                    script {
                        bat """
                            git config user.email "${GIT_COMMITTER_EMAIL}"
                            git config user.name "${GIT_COMMITTER_NAME}"
    
                            echo "=========> Verificando cambios pendientes..."
                            git status
                        """

                        bat """
                            echo "=========> Cambiando a rama release/${NEW_VERSION}..."
                            git checkout release/${NEW_VERSION}
                        """

                        bat """
                            @echo off
                            REM Obtener la fecha en formato YYYYMMDD
                            for /f "tokens=2 delims==" %%a in ('wmic os get localdatetime /value ^| find "="') do set datetime=%%a
                            set today=%datetime:~0,8%
                            set time=%datetime:~8,4%
                            set tagName=RC-${NEW_VERSION}-CERT-%today%%time%
    
                            echo "=========> tagName: %tagName%"
                            git tag %tagName%
                            git push origin %tagName%
                        """
                    }
                }
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
                        echo "=========> Construyendo nueva imagen con versión ${NEW_VERSION}..."
                        docker build --build-arg NAME_APP=${name} --build-arg JAR_VERSION=${NEW_VERSION} -t ${NAME_APP}:${NEW_VERSION} .
                    """
                    bat """
                        echo "=========> Desplegando el contenedor: ${NAME_APP}..."
                        docker run -d --name ${NAME_APP} -p ${HOST_PORT}:${CONTAINER_PORT} --network=${NETWORK} ^
                        --env CONFIG_SERVER=http://${CONFIG_SERVER}:${PORT_CONFIG_SERVER} ^
                        --env SPRING_PROFILES_ACTIVE=cert ^
                        ${NAME_APP}:${NEW_VERSION}
                    """
                }
            }
        }

//        stage('Push Docker Image') {
//            steps {
//                script {
//                    def version = readFile('version.txt').trim()
//                    def registry = "docker.io/josephmn"
//
//                    echo "######################## : ======> Subiendo imagen Docker al registry..."
//                    bat """
//                        docker tag ${NAME_APP}:${version} ${registry}/${NAME_APP}:${version}
//                        docker push ${registry}/${NAME_APP}:${version}
//                    """
//                }
//            }
//        }
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
