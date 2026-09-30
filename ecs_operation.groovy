pipeline {
    agent any

    environment {
        AWS_DEFAULT_REGION = 'ap-southeast-1'
        ECS_CLUSTER        = 'guestbook'
        AWS_ROLE_ARN       = 'arn:aws:iam::004842028030:role/jenkins-ecs-operation'
        AWS_CRED_ID        = 'aws-ecs-jenkins'   // Username = Access Key ID, Password = Secret Access Key
    }

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
        string(name: 'SELECTED_SERVICE', defaultValue: '', description: 'SELECTED SERVICE (dùng cho RESTART / EXPORT)')
        text(name: 'SCALE_LIST', defaultValue: '', description: 'Mỗi dòng: service=desired (SCALING FROM LIST) hoặc service=min:max (HPA FROM LIST). Để trống với SCALING DOWN TO 0 = tất cả service')
    }

    stages {
        stage('Validate') {
            steps {
                script {
                    if (params.ACTION.startsWith('---')) {
                        error('Vui lòng chọn một ACTION hợp lệ, không chọn dòng phân cách.')
                    }
                    if (params.ACTION in ['RESTART DEPLOYMENT', 'EXPORT CONFIGURATION'] && !params.SELECTED_SERVICE?.trim()) {
                        error("ACTION '${params.ACTION}' cần nhập SELECTED_SERVICE.")
                    }
                    echo "ACTION: ${params.ACTION} | CLUSTER: ${env.ECS_CLUSTER} | SERVICE: ${params.SELECTED_SERVICE}"
                }
            }
        }

        stage('Execute') {
            when { expression { params.ACTION != 'RELOAD' } }
            steps {
                script {
                    withAssumedRole {
                        powershell 'aws sts get-caller-identity'
                        switch (params.ACTION) {
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
                                powershell """
                                    \$svcs = (aws ecs list-services --cluster ${env.ECS_CLUSTER} --query 'serviceArns' --output text) -split '\\s+' | ? { \$_ }
                                    aws ecs describe-services --cluster ${env.ECS_CLUSTER} --services \$svcs --query 'services[].[serviceName,desiredCount,runningCount,pendingCount]' --output table
                                """
                                break
                            case 'MULTI SCALING DOWN TO 0':
                                def targets = parseList(params.SCALE_LIST).keySet() as List
                                if (!targets) {
                                    targets = powershell(script: "aws ecs list-services --cluster ${env.ECS_CLUSTER} --query 'serviceArns[]' --output text", returnStdout: true)
                                        .trim().split(/\s+/).collect { it.tokenize('/').last() }
                                }
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
                                    def (min, max) = range.tokenize(':')
                                    powershell "aws application-autoscaling register-scalable-target --service-namespace ecs --scalable-dimension ecs:service:DesiredCount --resource-id service/${env.ECS_CLUSTER}/${svc} --min-capacity ${min} --max-capacity ${max}"
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

// "svc=value" mỗi dòng -> [svc: value]
def parseList(String text) {
    def result = [:]
    (text ?: '').readLines()*.trim().findAll { it && !it.startsWith('#') }.each { line ->
        def parts = line.split('=', 2)
        if (parts.size() != 2) error("Dòng sai định dạng: '${line}'")
        result[parts[0].trim()] = parts[1].trim()
    }
    return result
}
