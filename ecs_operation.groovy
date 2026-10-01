import groovy.transform.Field

@Field List SERVICES = []   // đọc từ service.yml ở stage 'Load Services'
@Field String ACTION = ''

pipeline {
    agent any

    environment {
        AWS_DEFAULT_REGION = 'ap-southeast-1'
        ECS_CLUSTER        = 'guestbook'
        AWS_ROLE_ARN       = 'arn:aws:iam::004842028030:role/jenkins-ecs-operation'
        AWS_CRED_ID        = 'aws-ecs-jenkins'   // Username = Access Key ID, Password = Secret Access Key
        SERVICE_FILE       = 'service.yml'       // danh sách service được phép thao tác
    }

    // Không dùng block parameters {} vì dropdown SELECTED_SERVICE phải dựng từ service.yml.
    // Parameters được tạo bằng properties() ở stage 'Load Services'; chọn ACTION = RELOAD sau khi sửa service.yml.

    stages {
        stage('Load Services') {
            steps {
                script {
                    SERVICES = loadServices(env.SERVICE_FILE)
                    echo "Service trong ${env.SERVICE_FILE}: ${SERVICES}"
                    properties([parameters([
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
                        ),
                        choice(name: 'SELECTED_SERVICE', choices: SERVICES, description: "SELECTED SERVICE (lấy từ ${env.SERVICE_FILE}, dùng cho RESTART / EXPORT)"),
                        text(name: 'SCALE_LIST', defaultValue: '', description: 'Mỗi dòng: service=desired (SCALING FROM LIST) hoặc service=min:max (HPA FROM LIST). Để trống với SCALING DOWN TO 0 = tất cả service')
                    ])])
                    // Lần chạy đầu (chưa có parameters) coi như RELOAD
                    ACTION = params.ACTION ?: 'RELOAD'
                    if (ACTION == 'RELOAD') echo 'Đã cập nhật parameters. Mở lại "Build with Parameters" để thấy dropdown mới.'
                }
            }
        }

        stage('Validate') {
            when { expression { ACTION != 'RELOAD' } }
            steps {
                script {
                    if (ACTION.startsWith('---')) {
                        error('Vui lòng chọn một ACTION hợp lệ, không chọn dòng phân cách.')
                    }
                    // Dropdown có thể cũ hơn service.yml nếu chưa RELOAD
                    if (ACTION in ['RESTART DEPLOYMENT', 'EXPORT CONFIGURATION'] && !(params.SELECTED_SERVICE in SERVICES)) {
                        error("SELECTED_SERVICE '${params.SELECTED_SERVICE}' không có trong ${env.SERVICE_FILE}. Chạy RELOAD để cập nhật dropdown.")
                    }
                    def unknown = []
                    for (String s : parseList(params.SCALE_LIST).keySet()) { if (!(s in SERVICES)) unknown << s }
                    if (unknown) error("SCALE_LIST có service không nằm trong ${env.SERVICE_FILE}: ${unknown}")
                    echo "ACTION: ${ACTION} | CLUSTER: ${env.ECS_CLUSTER} | SERVICE: ${params.SELECTED_SERVICE}"
                }
            }
        }

        stage('Execute') {
            when { expression { ACTION != 'RELOAD' } }
            steps {
                script {
                    withAssumedRole {
                        powershell 'aws sts get-caller-identity'
                        switch (ACTION) {
                            case 'RESTART DEPLOYMENT':
                                powershell """
                                    aws ecs update-service --cluster ${env.ECS_CLUSTER} --service ${params.SELECTED_SERVICE} --force-new-deployment --query 'service.deployments[0].status'
                                    aws ecs wait services-stable --cluster ${env.ECS_CLUSTER} --services ${params.SELECTED_SERVICE}
                                """
                                break
                            case 'EXPORT CONFIGURATION':
                                powershell """
                                    \$ErrorActionPreference = 'Stop'
                                    \$td = aws ecs describe-services --cluster ${env.ECS_CLUSTER} --services ${params.SELECTED_SERVICE} --query 'services[0].taskDefinition' --output text
                                    aws ecs describe-services --cluster ${env.ECS_CLUSTER} --services ${params.SELECTED_SERVICE} | Out-File -Encoding utf8 service-${params.SELECTED_SERVICE}.json
                                    aws ecs describe-task-definition --task-definition \$td | Out-File -Encoding utf8 taskdef-${params.SELECTED_SERVICE}.json
                                """
                                archiveArtifacts artifacts: '*.json'
                                break
                            case 'GET CURRENT POD NUMBERS':
                                powershell "aws ecs describe-services --cluster ${env.ECS_CLUSTER} --services ${SERVICES.join(' ')} --query 'services[].[serviceName,desiredCount,runningCount,pendingCount]' --output table"
                                break
                            case 'MULTI SCALING DOWN TO 0':
                                def targets = parseList(params.SCALE_LIST).keySet() as List
                                if (!targets) targets = SERVICES
                                input message: "Scale về 0 các service: ${targets}?", ok: 'Xác nhận'
                                targets.each { svc ->
                                    powershell "aws ecs update-service --cluster ${env.ECS_CLUSTER} --service ${svc} --desired-count 0 --query 'service.desiredCount'"
                                }
                                break
                            case 'MULTI SCALING FROM LIST':
                                parseList(params.SCALE_LIST).each { svc, count ->
                                    powershell "aws ecs update-service --cluster ${env.ECS_CLUSTER} --service ${svc} --desired-count ${count} --query 'service.desiredCount'"
                                }
                                break
                            case 'MULTI ADJUSTING HPA FROM LIST':
                                parseList(params.SCALE_LIST).each { svc, range ->
                                    def mm = range.tokenize(':')
                                    if (mm.size() != 2) error("HPA '${svc}' phải có dạng min:max, nhận được '${range}'")
                                    powershell "aws application-autoscaling register-scalable-target --service-namespace ecs --scalable-dimension ecs:service:DesiredCount --resource-id service/${env.ECS_CLUSTER}/${svc} --min-capacity ${mm[0]} --max-capacity ${mm[1]}"
                                }
                                break
                        }
                    }
                }
            }
        }
    }
}

// Dùng access key của user jenkins để assume role, rồi chạy body với credential tạm của role
def withAssumedRole(Closure body) {
    def creds
    withCredentials([usernamePassword(credentialsId: env.AWS_CRED_ID, usernameVariable: 'AWS_ACCESS_KEY_ID', passwordVariable: 'AWS_SECRET_ACCESS_KEY')]) {
        creds = powershell(returnStdout: true, script: '''
            aws sts assume-role --role-arn $env:AWS_ROLE_ARN --role-session-name "jenkins-$env:BUILD_NUMBER" `
                --query 'Credentials.[AccessKeyId,SecretAccessKey,SessionToken]' --output text
        ''').trim().split(/\s+/)
    }
    if (creds.size() != 3) error('Assume role thất bại.')
    // Credential tạm (hết hạn sau 1h) chỉ nằm trong env, không echo ra log
    withEnv(["AWS_ACCESS_KEY_ID=${creds[0]}", "AWS_SECRET_ACCESS_KEY=${creds[1]}", "AWS_SESSION_TOKEN=${creds[2]}"]) {
        body()
    }
}

// Đọc list service từ YAML dạng "- name" (không cần plugin pipeline-utility-steps)
def loadServices(String file) {
    if (!fileExists(file)) error("Không tìm thấy ${file} trong repo.")
    def result = []
    for (String raw : readFile(file: file, encoding: 'UTF-8').readLines()) {
        def line = raw.replaceAll(/#.*$/, '').trim()
        if (!line.startsWith('- ')) continue   // bỏ qua '---' (đầu document YAML)
        def name = line.substring(1).trim().replaceAll(/^['"]|['"]$/, '')
        if (name) result << name
    }
    if (!result) error("${file} không có service nào.")
    return result
}

// "svc=value" mỗi dòng -> [svc: value]
def parseList(String text) {
    def result = [:]
    for (String raw : (text ?: '').readLines()) {
        def line = raw.trim()
        if (!line || line.startsWith('#')) continue
        def parts = line.split('=', 2)
        if (parts.size() != 2) error("Dòng sai định dạng: '${line}'")
        result[parts[0].trim()] = parts[1].trim()
    }
    return result
}
