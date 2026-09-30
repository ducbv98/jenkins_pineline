pipeline {
    agent any

    parameters {
        choice(
            name: 'ACTION',
            choices: [
                'RESTART DEPLOYMENT',
                '------------------------',
                'EXPORT CONFIGURATION',
                '------------------------',
                'GET CURRENT POD NUMBERS',
                'MULTI SCALING DOWN TO 0',
                'MULTI SCALING FROM LIST',
                'MULTI ADJUSTING HPA FROM LIST',
                '------------------------',
                'RELOAD'
            ],
            description: 'ACTION'
        )
        string(name: 'SELECTED_SERVICE', defaultValue: '', description: 'SELECTED SERVICE')
    }

    stages {
        stage('Validate') {
            steps {
                script {
                    if (params.ACTION.startsWith('---')) {
                        error('Vui lòng chọn một ACTION hợp lệ, không chọn dòng phân cách.')
                    }
                    echo "ACTION: ${params.ACTION}"
                    echo "SELECTED SERVICE: ${params.SELECTED_SERVICE}"
                }
            }
        }

        stage('Execute') {
            steps {
                script {
                    switch (params.ACTION) {
                        case 'RESTART DEPLOYMENT':
                            echo 'TODO: restart deployment'
                            break
                        case 'EXPORT CONFIGURATION':
                            echo 'TODO: export configuration'
                            break
                        case 'GET CURRENT POD NUMBERS':
                            echo 'TODO: get current pod numbers'
                            break
                        case 'MULTI SCALING DOWN TO 0':
                            echo 'TODO: scale down to 0'
                            break
                        case 'MULTI SCALING FROM LIST':
                            echo 'TODO: scale from list'
                            break
                        case 'MULTI ADJUSTING HPA FROM LIST':
                            echo 'TODO: adjust HPA from list'
                            break
                        case 'RELOAD':
                            echo 'Reload parameters xong.'
                            break
                    }
                }
            }
        }
    }
}
