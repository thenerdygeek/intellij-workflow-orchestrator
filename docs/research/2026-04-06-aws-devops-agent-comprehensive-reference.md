# AWS DevOps Best Practices & Tooling -- Agent Reference Guide

**Date:** 2026-04-06
**Purpose:** Comprehensive reference for building a DevOps engineer AI agent that works inside IntelliJ IDEA, covering AWS CI/CD, IaC, containers, deployments, security, Terraform, Docker, monitoring, GitHub Actions, and all config file types.

---

## Table of Contents

1. [AWS CI/CD Tooling](#1-aws-cicd-tooling)
2. [AWS Infrastructure-as-Code](#2-aws-infrastructure-as-code)
3. [AWS Container Services](#3-aws-container-services)
4. [AWS Deployment Patterns](#4-aws-deployment-patterns)
5. [AWS Security in DevOps](#5-aws-security-in-devops)
6. [Terraform for AWS](#6-terraform-for-aws)
7. [Docker Best Practices for AWS](#7-docker-best-practices-for-aws)
8. [Monitoring & Observability Config](#8-monitoring--observability-config)
9. [GitHub Actions + AWS](#9-github-actions--aws)
10. [Complete DevOps File Type Reference](#10-complete-devops-file-type-reference)

---

## 1. AWS CI/CD Tooling

### 1.1 CodeBuild -- buildspec.yml

**Location:** Root of source code directory (default: `buildspec.yml`), or custom path specified in project config.

**Complete Structure:**

```yaml
version: 0.2

run-as: linux-user-name  # Optional, Linux only

env:
  shell: bash | /bin/sh  # Default shell
  variables:
    VARIABLE_NAME: "value"  # Plaintext env vars
  parameter-store:
    PARAM_VAR: /path/to/parameter  # SSM Parameter Store
  secrets-manager:
    SECRET_VAR: secret-id:json-key:version-stage:version-id
  exported-variables:
    - VARIABLE_NAME  # Available to downstream actions
  git-credential-helper: yes | no

proxy:
  upload-artifacts: yes | no
  logs: yes | no

batch:
  fast-fail: true | false
  build-graph: []
  build-list: []
  build-matrix:
    static: {}
    dynamic: {}

phases:
  install:
    run-as: linux-user-name
    on-failure: ABORT | CONTINUE
    runtime-versions:
      java: corretto17
      nodejs: 18
      python: 3.11
      docker: 20
    commands:
      - echo "Installing dependencies..."
      - pip install -r requirements.txt
    finally:
      - echo "Install phase cleanup"

  pre_build:
    on-failure: ABORT | CONTINUE
    commands:
      - echo "Logging in to ECR..."
      - aws ecr get-login-password --region $AWS_DEFAULT_REGION | docker login --username AWS --password-stdin $AWS_ACCOUNT_ID.dkr.ecr.$AWS_DEFAULT_REGION.amazonaws.com
      - COMMIT_HASH=$(echo $CODEBUILD_RESOLVED_SOURCE_VERSION | cut -c 1-7)
      - IMAGE_TAG=${COMMIT_HASH:=latest}

  build:
    on-failure: ABORT | CONTINUE
    commands:
      - echo "Build started on $(date)"
      - docker build -t $IMAGE_REPO_NAME:$IMAGE_TAG .
      - docker tag $IMAGE_REPO_NAME:$IMAGE_TAG $AWS_ACCOUNT_ID.dkr.ecr.$AWS_DEFAULT_REGION.amazonaws.com/$IMAGE_REPO_NAME:$IMAGE_TAG

  post_build:
    on-failure: ABORT | CONTINUE
    commands:
      - echo "Pushing Docker image..."
      - docker push $AWS_ACCOUNT_ID.dkr.ecr.$AWS_DEFAULT_REGION.amazonaws.com/$IMAGE_REPO_NAME:$IMAGE_TAG
      - printf '[{"name":"container-name","imageUri":"%s"}]' $AWS_ACCOUNT_ID.dkr.ecr.$AWS_DEFAULT_REGION.amazonaws.com/$IMAGE_REPO_NAME:$IMAGE_TAG > imagedefinitions.json

reports:
  report-group-name:
    files:
      - "**/*"
    base-directory: build/test-results
    discard-paths: yes
    file-format: JUNITXML | CUCUMBERJSON | TESTNG | VISUALSTUDIOTRX | NUNITXML | NUNIT3XML | COBERTURAXML | CLABORXML | JACOCOXML | SARIFSCA | SIMPLIFIEDSARIFSCA

artifacts:
  files:
    - imagedefinitions.json
    - appspec.yaml
    - taskdef.json
  name: build-output
  discard-paths: yes
  base-directory: build/output
  secondary-artifacts:
    artifact1:
      files: []
      base-directory: dir1
    artifact2:
      files: []
      base-directory: dir2

cache:
  paths:
    - '/root/.m2/**/*'
    - '/root/.gradle/caches/**/*'
    - '/root/.npm/**/*'
    - 'node_modules/**/*'
```

**Key Agent Knowledge:**
- Phase names are fixed: `install`, `pre_build`, `build`, `post_build` -- cannot be changed or added
- `on-failure: CONTINUE` allows subsequent phases to run even if current fails
- `finally` blocks always run regardless of phase success/failure
- Environment variable types: `PLAINTEXT`, `PARAMETER_STORE`, `SECRETS_MANAGER`
- Secrets Manager reference pattern: `secret-id:json-key:version-stage:version-id`
- `exported-variables` makes vars available to CodePipeline downstream actions
- `runtime-versions` only works with Amazon-managed images
- Cache paths use glob patterns; common paths: `.m2`, `.gradle/caches`, `node_modules`, `.npm`
- Reports support JUnit XML, Cobertura, JaCoCo, SARIF for security findings
- Batch builds: `build-graph` (dependency DAG), `build-list` (independent parallel), `build-matrix` (parameterized)

### 1.2 CodeDeploy -- appspec.yml

**Three compute platform variants:**

#### EC2/On-Premises (YAML only, must be named `appspec.yml` in root)

```yaml
version: 0.0
os: linux | windows

files:
  - source: /src
    destination: /var/www/html
  - source: /config/app.conf
    destination: /etc/myapp/

permissions:
  - object: /var/www/html
    pattern: "**"
    owner: www-data
    group: www-data
    mode: 755
    type:
      - directory
  - object: /var/www/html
    pattern: "**"
    owner: www-data
    group: www-data
    mode: 644
    type:
      - file

hooks:
  BeforeInstall:
    - location: scripts/before_install.sh
      timeout: 300
      runas: root
  AfterInstall:
    - location: scripts/after_install.sh
      timeout: 300
      runas: root
  ApplicationStart:
    - location: scripts/start_server.sh
      timeout: 300
      runas: root
  ApplicationStop:
    - location: scripts/stop_server.sh
      timeout: 300
      runas: root
  ValidateService:
    - location: scripts/validate.sh
      timeout: 300
      runas: root
```

**EC2 hook order:** `ApplicationStop` > `DownloadBundle` > `BeforeInstall` > `Install` > `AfterInstall` > `ApplicationStart` > `ValidateService`

#### Amazon ECS (YAML or JSON)

```yaml
version: 0.0
Resources:
  - TargetService:
      Type: AWS::ECS::Service
      Properties:
        TaskDefinition: "arn:aws:ecs:us-east-1:111222333:task-definition/my-task:3"
        LoadBalancerInfo:
          ContainerName: "my-container"
          ContainerPort: 8080
        PlatformVersion: "LATEST"
        NetworkConfiguration:
          AwsvpcConfiguration:
            Subnets: ["subnet-1234abcd", "subnet-5678efgh"]
            SecurityGroups: ["sg-12345678"]
            AssignPublicIp: "DISABLED"

Hooks:
  - BeforeInstall: "LambdaFunctionToValidateBeforeInstall"
  - AfterInstall: "LambdaFunctionToValidateAfterInstall"
  - AfterAllowTestTraffic: "LambdaFunctionToValidateAfterTestTraffic"
  - BeforeAllowTraffic: "LambdaFunctionToValidateBeforeTraffic"
  - AfterAllowTraffic: "LambdaFunctionToValidateAfterTraffic"
```

**ECS hook order:** `BeforeInstall` > `Install` > `AfterInstall` > `AllowTestTraffic` > `AfterAllowTestTraffic` > `BeforeAllowTraffic` > `AllowTraffic` > `AfterAllowTraffic`

#### AWS Lambda (YAML or JSON)

```yaml
version: 0.0
Resources:
  - MyFunction:
      Type: AWS::Lambda::Function
      Properties:
        Name: "my-function"
        Alias: "live"
        CurrentVersion: "1"
        TargetVersion: "2"

Hooks:
  - BeforeAllowTraffic: "LambdaFunctionToValidateBeforeTrafficShift"
  - AfterAllowTraffic: "LambdaFunctionToValidateAfterTrafficShift"
```

### 1.3 CodePipeline (V2)

**Pipeline JSON structure:**

```json
{
  "pipeline": {
    "name": "my-pipeline",
    "pipelineType": "V2",
    "roleArn": "arn:aws:iam::111222333:role/CodePipelineRole",
    "artifactStore": {
      "type": "S3",
      "location": "my-pipeline-artifacts"
    },
    "stages": [
      {
        "name": "Source",
        "actions": [
          {
            "name": "SourceAction",
            "actionTypeId": {
              "category": "Source",
              "owner": "AWS",
              "provider": "CodeStarSourceConnection",
              "version": "1"
            },
            "configuration": {
              "ConnectionArn": "arn:aws:codestar-connections:...",
              "FullRepositoryId": "owner/repo",
              "BranchName": "main",
              "DetectChanges": "true"
            },
            "outputArtifacts": [{ "name": "SourceOutput" }],
            "runOrder": 1
          }
        ]
      },
      {
        "name": "Build",
        "actions": [
          {
            "name": "BuildAction",
            "actionTypeId": {
              "category": "Build",
              "owner": "AWS",
              "provider": "CodeBuild",
              "version": "1"
            },
            "configuration": {
              "ProjectName": "my-build-project"
            },
            "inputArtifacts": [{ "name": "SourceOutput" }],
            "outputArtifacts": [{ "name": "BuildOutput" }],
            "runOrder": 1
          }
        ]
      },
      {
        "name": "Deploy",
        "actions": [
          {
            "name": "DeployAction",
            "actionTypeId": {
              "category": "Deploy",
              "owner": "AWS",
              "provider": "ECS",
              "version": "1"
            },
            "configuration": {
              "ClusterName": "my-cluster",
              "ServiceName": "my-service",
              "FileName": "imagedefinitions.json"
            },
            "inputArtifacts": [{ "name": "BuildOutput" }],
            "runOrder": 1
          }
        ]
      }
    ],
    "triggers": [
      {
        "providerType": "CodeStarSourceConnection",
        "gitConfiguration": {
          "sourceActionName": "SourceAction",
          "push": [
            {
              "branches": { "includes": ["main"], "excludes": ["feature/*"] },
              "tags": { "includes": ["v*"] }
            }
          ],
          "pullRequest": [
            {
              "branches": { "includes": ["main"] },
              "events": ["OPEN", "UPDATED"]
            }
          ]
        }
      }
    ],
    "variables": [
      {
        "name": "DEPLOY_ENV",
        "defaultValue": "staging",
        "description": "Target deployment environment"
      }
    ]
  }
}
```

**Key V2 features over V1:** Git tag triggers, pull request triggers, pipeline-level variables, parallel stages.

**Action categories:** `Source`, `Build`, `Test`, `Deploy`, `Approval`, `Invoke`
**Providers:** `CodeStarSourceConnection` (GitHub/Bitbucket/GitLab), `CodeBuild`, `CodeDeploy`, `ECS`, `S3`, `CloudFormation`, `Lambda`, `Manual` (approval)

### 1.4 CodeArtifact

**Maven settings.xml configuration:**

```xml
<settings>
  <servers>
    <server>
      <id>codeartifact</id>
      <username>aws</username>
      <password>${env.CODEARTIFACT_AUTH_TOKEN}</password>
    </server>
  </servers>
  <profiles>
    <profile>
      <id>codeartifact</id>
      <repositories>
        <repository>
          <id>codeartifact</id>
          <url>https://my_domain-111222333.d.codeartifact.us-east-1.amazonaws.com/maven/my_repo/</url>
        </repository>
      </repositories>
    </profile>
  </profiles>
  <activeProfiles>
    <activeProfile>codeartifact</activeProfile>
  </activeProfiles>
</settings>
```

**Gradle build.gradle configuration:**

```groovy
repositories {
    maven {
        url 'https://my_domain-111222333.d.codeartifact.us-east-1.amazonaws.com/maven/my_repo/'
        credentials {
            username "aws"
            password System.env.CODEARTIFACT_AUTH_TOKEN
        }
    }
}
```

**npm .npmrc configuration:**

```ini
registry=https://my_domain-111222333.d.codeartifact.us-east-1.amazonaws.com/npm/my_repo/
//my_domain-111222333.d.codeartifact.us-east-1.amazonaws.com/npm/my_repo/:_authToken=${CODEARTIFACT_AUTH_TOKEN}
//my_domain-111222333.d.codeartifact.us-east-1.amazonaws.com/npm/my_repo/:always-auth=true
```

**Auth token generation:** `aws codeartifact get-authorization-token --domain my_domain --domain-owner 111222333 --query authorizationToken --output text`

**Supported package formats:** Maven, Gradle, npm, yarn, pip, twine, NuGet, Cargo, Ruby, Swift, generic.

---

## 2. AWS Infrastructure-as-Code

### 2.1 CloudFormation Templates

**Complete template anatomy (YAML):**

```yaml
AWSTemplateFormatVersion: "2010-09-09"
Description: "Stack description (max 1024 bytes)"

Metadata:
  AWS::CloudFormation::Interface:
    ParameterGroups:
      - Label: { default: "Network Configuration" }
        Parameters: [VpcCIDR, SubnetCIDR]
    ParameterLabels:
      VpcCIDR: { default: "VPC CIDR Block" }

Parameters:
  EnvironmentType:
    Type: String
    Default: dev
    AllowedValues: [dev, staging, prod]
    Description: "Environment type"
    ConstraintDescription: "Must be dev, staging, or prod"
  VpcCIDR:
    Type: String
    Default: "10.0.0.0/16"
    AllowedPattern: '(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})/(\d{1,2})'
  InstanceType:
    Type: String
    Default: t3.micro
  LatestAmiId:
    Type: 'AWS::SSM::Parameter::Value<AWS::EC2::Image::Id>'
    Default: '/aws/service/ami-amazon-linux-latest/amzn2-ami-hvm-x86_64-gp2'

Mappings:
  RegionMap:
    us-east-1:
      AMI: ami-0123456789abcdef0
    us-west-2:
      AMI: ami-0fedcba9876543210

Conditions:
  IsProduction: !Equals [!Ref EnvironmentType, prod]
  CreateBastionHost: !And
    - !Condition IsProduction
    - !Equals [!Ref "AWS::Region", "us-east-1"]

Rules:
  ProdInstanceType:
    RuleCondition: !Equals [!Ref EnvironmentType, prod]
    Assertions:
      - Assert: !Contains [["t3.large", "t3.xlarge", "m5.large"], !Ref InstanceType]
        AssertDescription: "Production must use at least t3.large"

Transform:
  - AWS::Serverless-2016-10-31

Resources:
  MyVPC:
    Type: AWS::EC2::VPC
    Properties:
      CidrBlock: !Ref VpcCIDR
      EnableDnsSupport: true
      EnableDnsHostnames: true
      Tags:
        - Key: Name
          Value: !Sub "${AWS::StackName}-vpc"
        - Key: Environment
          Value: !Ref EnvironmentType

  MySecurityGroup:
    Type: AWS::EC2::SecurityGroup
    Properties:
      GroupDescription: "Security group for application"
      VpcId: !Ref MyVPC
      SecurityGroupIngress:
        - IpProtocol: tcp
          FromPort: 443
          ToPort: 443
          CidrIp: 0.0.0.0/0

Outputs:
  VpcId:
    Description: "VPC ID"
    Value: !Ref MyVPC
    Export:
      Name: !Sub "${AWS::StackName}-VpcId"
  SecurityGroupId:
    Value: !GetAtt MySecurityGroup.GroupId
    Export:
      Name: !Sub "${AWS::StackName}-SgId"
```

**Key intrinsic functions:**
- `!Ref` -- parameter/resource reference
- `!Sub` -- string substitution with variables
- `!GetAtt` -- resource attribute
- `!Join` -- concatenate with delimiter
- `!Select` -- pick from list
- `!Split` -- split string to list
- `!ImportValue` -- cross-stack reference
- `!FindInMap` -- lookup from Mappings
- `!If` -- conditional value
- `!Equals`, `!And`, `!Or`, `!Not` -- condition functions
- `!Base64` -- encode to base64
- `!Cidr` -- CIDR allocation
- `!GetAZs` -- availability zones

**Pseudo-parameters:** `AWS::AccountId`, `AWS::Region`, `AWS::StackName`, `AWS::StackId`, `AWS::URLSuffix`, `AWS::NoValue`, `AWS::Partition`

**Best practices the agent must enforce:**
1. Never hardcode account IDs, regions, or AMI IDs -- use parameters, mappings, or SSM Parameter types
2. Always use `DeletionPolicy: Retain` on stateful resources (RDS, S3, DynamoDB)
3. Always use `UpdateReplacePolicy: Retain` alongside DeletionPolicy for stateful resources
4. Use `!Sub` over `!Join` for readability
5. Export outputs for cross-stack references
6. Group related resources into nested stacks for large templates (>200 resources limit per stack)
7. Use `AWS::CloudFormation::Interface` metadata for console-friendly parameter grouping
8. Tag all resources with at minimum: Name, Environment, Project, Owner
9. Use `Conditions` to avoid separate templates per environment
10. Use change sets before updating production stacks

**Common mistakes to catch:**
- Circular dependencies between resources
- Missing `DependsOn` for resources with implicit ordering
- Using `!Ref` when `!GetAtt` is needed (e.g., `!Ref` on a security group returns the ID, but subnets need specific attributes)
- Forgetting `DeletionPolicy` on databases and S3 buckets
- Hardcoded availability zones (use `!GetAZs` or parameter)
- Exceeding 60 parameters, 200 resources, 60 outputs per stack
- Missing `Export` on outputs used by other stacks

### 2.2 AWS CDK (TypeScript focus)

**Project structure:**

```
my-cdk-app/
  bin/
    my-app.ts              # Entry point, instantiate App and Stacks
  lib/
    stacks/
      network-stack.ts     # VPC, subnets, NAT
      compute-stack.ts     # ECS, Lambda, EC2
      database-stack.ts    # RDS, DynamoDB
      pipeline-stack.ts    # CDK Pipeline (self-mutating)
    constructs/
      ecs-service.ts       # Reusable L3 construct
      rds-cluster.ts       # Reusable L3 construct
    config/
      environments.ts      # Environment-specific config
  test/
    stacks/
      network-stack.test.ts
  cdk.json                 # CDK configuration
  tsconfig.json
  package.json
```

**cdk.json key fields:**

```json
{
  "app": "npx ts-node --prefer-ts-exts bin/my-app.ts",
  "context": {
    "@aws-cdk/aws-ecs:enableCircuitBreaker": true,
    "@aws-cdk/core:stackRelativeExports": true,
    "@aws-cdk/aws-lambda:recognizeVersionProps": true,
    "@aws-cdk/aws-ecs:arnFormatIncludesClusterName": true
  },
  "watch": {
    "include": ["**"],
    "exclude": ["README.md", "cdk*.js", "jest.config.js", "**/*.d.ts", "**/*.js", "node_modules"]
  }
}
```

**CDK Pipelines (self-mutating) pattern:**

```typescript
import { CodePipeline, CodePipelineSource, ShellStep } from 'aws-cdk-lib/pipelines';

const pipeline = new CodePipeline(this, 'Pipeline', {
  pipelineName: 'MyPipeline',
  selfMutation: true,  // Pipeline updates itself
  synth: new ShellStep('Synth', {
    input: CodePipelineSource.gitHub('owner/repo', 'main', {
      authentication: SecretValue.secretsManager('github-token'),
    }),
    commands: [
      'npm ci',
      'npm run build',
      'npx cdk synth',
    ],
  }),
});

// Add deployment stages
pipeline.addStage(new StagingStage(this, 'Staging', {
  env: { account: '111222333', region: 'us-east-1' },
}));

pipeline.addStage(new ProductionStage(this, 'Production', {
  env: { account: '444555666', region: 'us-east-1' },
}), {
  pre: [
    new ManualApprovalStep('PromoteToProduction'),
  ],
  post: [
    new ShellStep('IntegrationTest', {
      commands: ['npm run test:integration'],
    }),
  ],
});
```

**Construct levels:**
- **L1 (Cfn*):** Direct CloudFormation mapping, e.g., `CfnBucket` -- all properties, no defaults
- **L2:** Opinionated defaults, e.g., `Bucket` -- encryption enabled, versioning, lifecycle
- **L3 (Patterns):** Multi-resource, e.g., `ApplicationLoadBalancedFargateService` -- VPC + ALB + ECS + security groups

**CDK best practices for agent:**
1. Stacks accept a properties interface for all config -- never read from env vars in constructs
2. Separate stacks by lifecycle/ownership (network, compute, data)
3. Use `cdk diff` before `cdk deploy` in production
4. Use aspects for cross-cutting concerns (tagging, compliance)
5. Snapshot testing with `Template.fromStack()` and `toJSON()`
6. Use `RemovalPolicy.RETAIN` on stateful resources

### 2.3 AWS SAM Templates

**Complete SAM template structure:**

```yaml
AWSTemplateFormatVersion: '2010-09-09'
Transform: AWS::Serverless-2016-10-31

Description: "SAM Application"

Globals:
  Function:
    Runtime: python3.12
    Timeout: 30
    MemorySize: 256
    Tracing: Active
    Environment:
      Variables:
        TABLE_NAME: !Ref DynamoTable
        STAGE: !Ref Stage
    Architectures:
      - arm64
    Layers:
      - !Ref SharedLayer
  Api:
    Auth:
      DefaultAuthorizer: CognitoAuthorizer
      Authorizers:
        CognitoAuthorizer:
          UserPoolArn: !GetAtt UserPool.Arn
    Cors:
      AllowMethods: "'GET,POST,PUT,DELETE,OPTIONS'"
      AllowHeaders: "'Content-Type,Authorization'"
      AllowOrigin: "'*'"
    TracingEnabled: true

Parameters:
  Stage:
    Type: String
    Default: dev
    AllowedValues: [dev, staging, prod]

Resources:
  GetItemFunction:
    Type: AWS::Serverless::Function
    Properties:
      Handler: app.get_handler
      CodeUri: src/get_item/
      Description: "Get item by ID"
      Policies:
        - DynamoDBReadPolicy:
            TableName: !Ref DynamoTable
      Events:
        GetItem:
          Type: Api
          Properties:
            Path: /items/{id}
            Method: get

  CreateItemFunction:
    Type: AWS::Serverless::Function
    Properties:
      Handler: app.create_handler
      CodeUri: src/create_item/
      Policies:
        - DynamoDBCrudPolicy:
            TableName: !Ref DynamoTable
      Events:
        CreateItem:
          Type: Api
          Properties:
            Path: /items
            Method: post
        SQSEvent:
          Type: SQS
          Properties:
            Queue: !GetAtt ItemQueue.Arn
            BatchSize: 10

  SharedLayer:
    Type: AWS::Serverless::LayerVersion
    Properties:
      LayerName: shared-dependencies
      ContentUri: layers/shared/
      CompatibleRuntimes:
        - python3.12

  DynamoTable:
    Type: AWS::Serverless::SimpleTable
    Properties:
      PrimaryKey:
        Name: id
        Type: String
      ProvisionedThroughput:
        ReadCapacityUnits: 5
        WriteCapacityUnits: 5

  ItemQueue:
    Type: AWS::SQS::Queue
    Properties:
      QueueName: !Sub "${Stage}-item-queue"

  StateMachine:
    Type: AWS::Serverless::StateMachine
    Properties:
      DefinitionUri: statemachine/definition.asl.json
      Policies:
        - LambdaInvokePolicy:
            FunctionName: !Ref GetItemFunction

Outputs:
  ApiEndpoint:
    Description: "API Gateway endpoint URL"
    Value: !Sub "https://${ServerlessRestApi}.execute-api.${AWS::Region}.amazonaws.com/${Stage}/"
```

**SAM resource types:**
- `AWS::Serverless::Function` -- Lambda function + event sources + IAM
- `AWS::Serverless::Api` -- API Gateway REST API
- `AWS::Serverless::HttpApi` -- API Gateway HTTP API (v2, cheaper)
- `AWS::Serverless::SimpleTable` -- DynamoDB single-key table
- `AWS::Serverless::LayerVersion` -- Lambda layer
- `AWS::Serverless::Application` -- Nested SAM app from SAR
- `AWS::Serverless::StateMachine` -- Step Functions
- `AWS::Serverless::Connector` -- Simplified resource permissions

**SAM policy templates (shorthand):** `DynamoDBCrudPolicy`, `DynamoDBReadPolicy`, `S3ReadPolicy`, `S3CrudPolicy`, `SQSSendMessagePolicy`, `SQSPollerPolicy`, `SNSPublishMessagePolicy`, `KinesisStreamReadPolicy`, `LambdaInvokePolicy`, `StepFunctionsExecutionPolicy`

**SAM CLI commands the agent should know:**
- `sam init` -- scaffold new project
- `sam build` -- build Lambda packages
- `sam local start-api` -- local API Gateway
- `sam local invoke` -- invoke single function locally
- `sam deploy --guided` -- interactive first deploy
- `sam sync --watch` -- hot-reload for development
- `sam validate` -- validate template syntax
- `sam logs -n FunctionName --tail` -- tail CloudWatch logs

---

## 3. AWS Container Services

### 3.1 ECS Task Definition (Fargate)

```json
{
  "family": "my-app",
  "requiresCompatibilities": ["FARGATE"],
  "networkMode": "awsvpc",
  "cpu": "512",
  "memory": "1024",
  "runtimePlatform": {
    "cpuArchitecture": "ARM64",
    "operatingSystemFamily": "LINUX"
  },
  "executionRoleArn": "arn:aws:iam::111222333:role/ecsTaskExecutionRole",
  "taskRoleArn": "arn:aws:iam::111222333:role/ecsTaskRole",
  "containerDefinitions": [
    {
      "name": "app",
      "image": "111222333.dkr.ecr.us-east-1.amazonaws.com/my-app:latest",
      "essential": true,
      "portMappings": [
        {
          "containerPort": 8080,
          "protocol": "tcp",
          "appProtocol": "http",
          "name": "app-http"
        }
      ],
      "environment": [
        { "name": "SPRING_PROFILES_ACTIVE", "value": "production" }
      ],
      "secrets": [
        {
          "name": "DB_PASSWORD",
          "valueFrom": "arn:aws:secretsmanager:us-east-1:111222333:secret:db-password-AbCdEf"
        },
        {
          "name": "API_KEY",
          "valueFrom": "arn:aws:ssm:us-east-1:111222333:parameter/app/api-key"
        }
      ],
      "logConfiguration": {
        "logDriver": "awslogs",
        "options": {
          "awslogs-group": "/ecs/my-app",
          "awslogs-region": "us-east-1",
          "awslogs-stream-prefix": "ecs",
          "mode": "non-blocking",
          "max-buffer-size": "25m"
        }
      },
      "healthCheck": {
        "command": ["CMD-SHELL", "curl -f http://localhost:8080/health || exit 1"],
        "interval": 30,
        "timeout": 5,
        "retries": 3,
        "startPeriod": 60
      },
      "ulimits": [
        { "name": "nofile", "softLimit": 65536, "hardLimit": 65536 }
      ],
      "linuxParameters": {
        "initProcessEnabled": true
      }
    },
    {
      "name": "xray-daemon",
      "image": "public.ecr.aws/xray/aws-xray-daemon:latest",
      "essential": false,
      "portMappings": [
        { "containerPort": 2000, "protocol": "udp" }
      ],
      "logConfiguration": {
        "logDriver": "awslogs",
        "options": {
          "awslogs-group": "/ecs/my-app",
          "awslogs-region": "us-east-1",
          "awslogs-stream-prefix": "xray"
        }
      }
    }
  ],
  "volumes": [
    {
      "name": "data",
      "efsVolumeConfiguration": {
        "fileSystemId": "fs-1234567890",
        "rootDirectory": "/app/data",
        "transitEncryption": "ENABLED",
        "authorizationConfig": {
          "accessPointId": "fsap-1234567890",
          "iam": "ENABLED"
        }
      }
    }
  ],
  "ephemeralStorage": {
    "sizeInGiB": 30
  },
  "tags": [
    { "key": "Environment", "value": "production" },
    { "key": "Service", "value": "my-app" }
  ]
}
```

**Fargate CPU/memory combinations (valid pairs):**

| CPU (units) | Memory (MiB) options |
|---|---|
| 256 | 512, 1024, 2048 |
| 512 | 1024-4096 (1GB increments) |
| 1024 | 2048-8192 (1GB increments) |
| 2048 | 4096-16384 (1GB increments) |
| 4096 | 8192-30720 (1GB increments) |
| 8192 | 16384-61440 (4GB increments) |
| 16384 | 32768-122880 (8GB increments) |

**Key agent knowledge:**
- `executionRoleArn` = ECS agent permissions (pull images, write logs)
- `taskRoleArn` = application code permissions (access AWS services)
- `networkMode` must be `awsvpc` for Fargate
- Secrets can reference both Secrets Manager ARNs and SSM Parameter Store ARNs
- `initProcessEnabled: true` prevents zombie processes (always enable for production)
- `mode: non-blocking` log driver mode (default since June 2025) prevents task hangs on log backpressure
- `ephemeralStorage` can be increased up to 200 GiB for Fargate (default 20 GiB)
- Fargate now supports ARM64 (`cpuArchitecture: ARM64`) for cost savings (~20% cheaper)

### 3.2 ECS Service Definition

```json
{
  "serviceName": "my-service",
  "cluster": "my-cluster",
  "taskDefinition": "my-app:5",
  "desiredCount": 3,
  "launchType": "FARGATE",
  "deploymentConfiguration": {
    "deploymentCircuitBreaker": {
      "enable": true,
      "rollback": true
    },
    "maximumPercent": 200,
    "minimumHealthyPercent": 100
  },
  "networkConfiguration": {
    "awsvpcConfiguration": {
      "subnets": ["subnet-abc123", "subnet-def456"],
      "securityGroups": ["sg-12345678"],
      "assignPublicIp": "DISABLED"
    }
  },
  "loadBalancers": [
    {
      "targetGroupArn": "arn:aws:elasticloadbalancing:...:targetgroup/...",
      "containerName": "app",
      "containerPort": 8080
    }
  ],
  "serviceConnectConfiguration": {
    "enabled": true,
    "namespace": "my-namespace",
    "services": [
      {
        "portName": "app-http",
        "discoveryName": "my-app",
        "clientAliases": [{ "port": 8080, "dnsName": "my-app" }]
      }
    ]
  },
  "serviceRegistries": [
    {
      "registryArn": "arn:aws:servicediscovery:...:service/...",
      "containerName": "app",
      "containerPort": 8080
    }
  ],
  "platformVersion": "LATEST",
  "enableExecuteCommand": true,
  "propagateTags": "TASK_DEFINITION",
  "tags": [
    { "key": "Environment", "value": "production" }
  ]
}
```

### 3.3 EKS / Kubernetes Manifests

**Deployment:**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: my-app
  namespace: production
  labels:
    app.kubernetes.io/name: my-app
    app.kubernetes.io/version: "1.2.3"
    app.kubernetes.io/component: backend
    app.kubernetes.io/managed-by: helm
spec:
  replicas: 3
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0
  selector:
    matchLabels:
      app.kubernetes.io/name: my-app
  template:
    metadata:
      labels:
        app.kubernetes.io/name: my-app
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "8080"
        prometheus.io/path: "/metrics"
    spec:
      serviceAccountName: my-app-sa
      securityContext:
        runAsNonRoot: true
        runAsUser: 1000
        fsGroup: 1000
      containers:
        - name: app
          image: 111222333.dkr.ecr.us-east-1.amazonaws.com/my-app:1.2.3
          ports:
            - containerPort: 8080
              name: http
              protocol: TCP
          env:
            - name: DB_HOST
              valueFrom:
                configMapKeyRef:
                  name: my-app-config
                  key: db-host
            - name: DB_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: my-app-secrets
                  key: db-password
          resources:
            requests:
              cpu: 250m
              memory: 512Mi
            limits:
              cpu: 500m
              memory: 1Gi
          livenessProbe:
            httpGet:
              path: /health/live
              port: http
            initialDelaySeconds: 30
            periodSeconds: 10
            failureThreshold: 3
          readinessProbe:
            httpGet:
              path: /health/ready
              port: http
            initialDelaySeconds: 5
            periodSeconds: 5
            failureThreshold: 3
          startupProbe:
            httpGet:
              path: /health/live
              port: http
            failureThreshold: 30
            periodSeconds: 10
          volumeMounts:
            - name: config-volume
              mountPath: /etc/config
              readOnly: true
      volumes:
        - name: config-volume
          configMap:
            name: my-app-config
      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: topology.kubernetes.io/zone
          whenUnsatisfiable: DoNotSchedule
          labelSelector:
            matchLabels:
              app.kubernetes.io/name: my-app
      terminationGracePeriodSeconds: 60
```

**Service:**

```yaml
apiVersion: v1
kind: Service
metadata:
  name: my-app
  namespace: production
  annotations:
    service.beta.kubernetes.io/aws-load-balancer-type: "nlb"
    service.beta.kubernetes.io/aws-load-balancer-scheme: "internal"
    service.beta.kubernetes.io/aws-load-balancer-cross-zone-load-balancing-enabled: "true"
spec:
  type: ClusterIP  # or LoadBalancer, NodePort
  selector:
    app.kubernetes.io/name: my-app
  ports:
    - port: 80
      targetPort: http
      protocol: TCP
      name: http
```

**Ingress (AWS ALB Ingress Controller):**

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: my-app
  namespace: production
  annotations:
    kubernetes.io/ingress.class: alb
    alb.ingress.kubernetes.io/scheme: internet-facing
    alb.ingress.kubernetes.io/target-type: ip
    alb.ingress.kubernetes.io/certificate-arn: arn:aws:acm:us-east-1:111222333:certificate/...
    alb.ingress.kubernetes.io/listen-ports: '[{"HTTPS":443}]'
    alb.ingress.kubernetes.io/ssl-redirect: "443"
    alb.ingress.kubernetes.io/healthcheck-path: /health
    alb.ingress.kubernetes.io/group.name: shared-alb
    alb.ingress.kubernetes.io/group.order: "10"
spec:
  rules:
    - host: my-app.example.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: my-app
                port:
                  number: 80
```

**HorizontalPodAutoscaler:**

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: my-app
  namespace: production
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: my-app
  minReplicas: 3
  maxReplicas: 20
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: 80
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300
      policies:
        - type: Percent
          value: 10
          periodSeconds: 60
    scaleUp:
      stabilizationWindowSeconds: 0
      policies:
        - type: Percent
          value: 100
          periodSeconds: 15
```

### 3.4 Helm Chart Structure

```
my-chart/
  Chart.yaml              # Chart metadata and dependencies
  Chart.lock              # Locked dependency versions
  values.yaml             # Default configuration values
  values.schema.json      # JSON Schema for values validation
  charts/                 # Subcharts/dependencies
  crds/                   # Custom Resource Definitions
  templates/
    NOTES.txt             # Post-install notes shown to user
    _helpers.tpl          # Template partials and helper functions
    deployment.yaml
    service.yaml
    ingress.yaml
    configmap.yaml
    secret.yaml
    hpa.yaml
    serviceaccount.yaml
    pdb.yaml              # PodDisruptionBudget
    networkpolicy.yaml
    tests/
      test-connection.yaml  # Helm test pod
```

**Chart.yaml:**

```yaml
apiVersion: v2
name: my-app
description: A Helm chart for My Application
type: application  # or library
version: 1.2.3  # Chart version (semver)
appVersion: "2.0.0"  # Application version
kubeVersion: ">=1.27.0-0"
dependencies:
  - name: postgresql
    version: "13.x.x"
    repository: "https://charts.bitnami.com/bitnami"
    condition: postgresql.enabled
  - name: redis
    version: "18.x.x"
    repository: "https://charts.bitnami.com/bitnami"
    condition: redis.enabled
maintainers:
  - name: team-name
    email: team@example.com
```

**values.yaml:**

```yaml
replicaCount: 3

image:
  repository: 111222333.dkr.ecr.us-east-1.amazonaws.com/my-app
  tag: ""  # Overridden by CI/CD
  pullPolicy: IfNotPresent

serviceAccount:
  create: true
  annotations:
    eks.amazonaws.com/role-arn: arn:aws:iam::111222333:role/my-app-role

service:
  type: ClusterIP
  port: 80

ingress:
  enabled: true
  className: alb
  annotations:
    alb.ingress.kubernetes.io/scheme: internet-facing
  hosts:
    - host: my-app.example.com
      paths:
        - path: /
          pathType: Prefix

resources:
  requests:
    cpu: 250m
    memory: 512Mi
  limits:
    cpu: 500m
    memory: 1Gi

autoscaling:
  enabled: true
  minReplicas: 3
  maxReplicas: 20
  targetCPUUtilizationPercentage: 70

postgresql:
  enabled: true
  auth:
    existingSecret: my-app-db-credentials

redis:
  enabled: false
```

**_helpers.tpl common patterns:**

```
{{/* Chart name */}}
{{- define "my-chart.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/* Fullname */}}
{{- define "my-chart.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}

{{/* Common labels */}}
{{- define "my-chart.labels" -}}
helm.sh/chart: {{ include "my-chart.chart" . }}
{{ include "my-chart.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/* Selector labels */}}
{{- define "my-chart.selectorLabels" -}}
app.kubernetes.io/name: {{ include "my-chart.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
```

---

## 4. AWS Deployment Patterns

### 4.1 Blue/Green with CodeDeploy (ECS)

**Deployment config options:**
- `CodeDeployDefault.ECSAllAtOnce` -- shift all traffic immediately
- `CodeDeployDefault.ECSLinear10PercentEvery1Minutes` -- 10% every minute
- `CodeDeployDefault.ECSLinear10PercentEvery3Minutes` -- 10% every 3 minutes
- `CodeDeployDefault.ECSCanary10Percent5Minutes` -- 10% first, remaining after 5 minutes
- `CodeDeployDefault.ECSCanary10Percent15Minutes` -- 10% first, remaining after 15 minutes

**Required resources:**
1. ECS Service with `CODE_DEPLOY` deployment controller
2. ALB with two target groups (blue and green)
3. ALB listener with production (port 443) and test (port 8443) rules
4. CodeDeploy application + deployment group
5. `appspec.yaml` specifying task definition and container/port
6. `taskdef.json` with `<IMAGE1_NAME>` placeholder for image URI

**imagedefinitions.json (for ECS standard deploy):**

```json
[
  {
    "name": "container-name",
    "imageUri": "111222333.dkr.ecr.us-east-1.amazonaws.com/my-app:abc1234"
  }
]
```

**imageDetail.json (for CodeDeploy blue/green):**

```json
{
  "ImageURI": "111222333.dkr.ecr.us-east-1.amazonaws.com/my-app:abc1234"
}
```

### 4.2 Rolling Update with ECS

```json
{
  "deploymentConfiguration": {
    "deploymentCircuitBreaker": {
      "enable": true,
      "rollback": true
    },
    "maximumPercent": 200,
    "minimumHealthyPercent": 100
  }
}
```

- `maximumPercent: 200` + `minimumHealthyPercent: 100` = deploy new tasks first, then drain old (zero-downtime)
- `maximumPercent: 100` + `minimumHealthyPercent: 50` = stop half first, then deploy (saves cost, has brief capacity reduction)
- Circuit breaker auto-rolls back if new tasks fail health checks

### 4.3 Canary with Lambda (SAM)

```yaml
Resources:
  MyFunction:
    Type: AWS::Serverless::Function
    Properties:
      Handler: index.handler
      Runtime: python3.12
      AutoPublishAlias: live
      DeploymentPreference:
        Type: Canary10Percent5Minutes  # or Linear10PercentEvery1Minute, AllAtOnce
        Alarms:
          - !Ref CanaryErrorsAlarm
          - !Ref CanaryLatencyAlarm
        Hooks:
          PreTraffic: !Ref PreTrafficHookFunction
          PostTraffic: !Ref PostTrafficHookFunction
```

**Traffic shifting types for Lambda:**
- `Canary10Percent5Minutes`, `Canary10Percent10Minutes`, `Canary10Percent15Minutes`, `Canary10Percent30Minutes`
- `Linear10PercentEvery1Minute`, `Linear10PercentEvery2Minutes`, `Linear10PercentEvery3Minutes`, `Linear10PercentEvery10Minutes`
- `AllAtOnce`

### 4.4 Traffic Shifting with ALB (weighted target groups)

```json
{
  "Actions": [
    {
      "Type": "forward",
      "ForwardConfig": {
        "TargetGroups": [
          {
            "TargetGroupArn": "arn:...blue-tg",
            "Weight": 90
          },
          {
            "TargetGroupArn": "arn:...green-tg",
            "Weight": 10
          }
        ],
        "TargetGroupStickinessConfig": {
          "Enabled": true,
          "DurationSeconds": 3600
        }
      }
    }
  ]
}
```

---

## 5. AWS Security in DevOps

### 5.1 IAM Roles for CI/CD

**CodeBuild service role (least privilege example):**

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "CloudWatchLogs",
      "Effect": "Allow",
      "Action": [
        "logs:CreateLogGroup",
        "logs:CreateLogStream",
        "logs:PutLogEvents"
      ],
      "Resource": "arn:aws:logs:us-east-1:111222333:log-group:/aws/codebuild/*"
    },
    {
      "Sid": "ECRPush",
      "Effect": "Allow",
      "Action": [
        "ecr:GetAuthorizationToken",
        "ecr:BatchCheckLayerAvailability",
        "ecr:GetDownloadUrlForLayer",
        "ecr:BatchGetImage",
        "ecr:PutImage",
        "ecr:InitiateLayerUpload",
        "ecr:UploadLayerPart",
        "ecr:CompleteLayerUpload"
      ],
      "Resource": "arn:aws:ecr:us-east-1:111222333:repository/my-app"
    },
    {
      "Sid": "ECRAuth",
      "Effect": "Allow",
      "Action": "ecr:GetAuthorizationToken",
      "Resource": "*"
    },
    {
      "Sid": "S3Artifacts",
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:PutObject",
        "s3:GetBucketAcl",
        "s3:GetBucketLocation"
      ],
      "Resource": [
        "arn:aws:s3:::my-pipeline-artifacts",
        "arn:aws:s3:::my-pipeline-artifacts/*"
      ]
    },
    {
      "Sid": "SecretsAccess",
      "Effect": "Allow",
      "Action": [
        "secretsmanager:GetSecretValue"
      ],
      "Resource": "arn:aws:secretsmanager:us-east-1:111222333:secret:build/*"
    },
    {
      "Sid": "SSMParameters",
      "Effect": "Allow",
      "Action": [
        "ssm:GetParameters",
        "ssm:GetParameter"
      ],
      "Resource": "arn:aws:ssm:us-east-1:111222333:parameter/build/*"
    }
  ]
}
```

**ECS task execution role:**

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "ecr:GetAuthorizationToken",
        "ecr:BatchCheckLayerAvailability",
        "ecr:GetDownloadUrlForLayer",
        "ecr:BatchGetImage",
        "logs:CreateLogStream",
        "logs:PutLogEvents"
      ],
      "Resource": "*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "secretsmanager:GetSecretValue"
      ],
      "Resource": "arn:aws:secretsmanager:us-east-1:111222333:secret:app/*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "ssm:GetParameters"
      ],
      "Resource": "arn:aws:ssm:us-east-1:111222333:parameter/app/*"
    }
  ]
}
```

**Key principles:**
1. Use IAM Access Analyzer to generate least-privilege policies from CloudTrail activity
2. Separate roles: CodeBuild role, CodeDeploy role, ECS task execution role, ECS task role
3. Use OIDC for GitHub Actions (no long-lived credentials)
4. Use resource-level ARNs, never `Resource: "*"` for data access
5. Use conditions: `aws:SourceArn`, `aws:SourceAccount` for service-to-service trust
6. Never build from untrusted PRs without review (prevents credential exfiltration)
7. Use AWS managed policies only as starting point, create custom policies for production

### 5.2 Secrets Management

**Secrets Manager in buildspec.yml:**

```yaml
env:
  secrets-manager:
    DB_PASSWORD: "prod/database:password"
    API_KEY: "prod/api-keys:main-api-key:AWSCURRENT"
```

**Reference format:** `secret-id:json-key:version-stage:version-id`
- `secret-id` -- name or ARN of the secret
- `json-key` -- key within JSON secret value (required for JSON secrets)
- `version-stage` -- `AWSCURRENT` (default) or `AWSPREVIOUS`
- `version-id` -- specific version UUID (rarely needed)

**Parameter Store in buildspec.yml:**

```yaml
env:
  parameter-store:
    DB_HOST: "/app/prod/db-host"
    FEATURE_FLAGS: "/app/prod/feature-flags"
```

**ECS task definition secrets:**

```json
"secrets": [
  {
    "name": "DB_PASSWORD",
    "valueFrom": "arn:aws:secretsmanager:us-east-1:111222333:secret:prod/database-AbCdEf:password::"
  },
  {
    "name": "CONFIG_VALUE",
    "valueFrom": "arn:aws:ssm:us-east-1:111222333:parameter/app/config"
  }
]
```

### 5.3 ECR Image Scanning

**ECR repository with scanning (CloudFormation):**

```yaml
Resources:
  MyECRRepo:
    Type: AWS::ECR::Repository
    Properties:
      RepositoryName: my-app
      ImageScanningConfiguration:
        ScanOnPush: true
      ImageTagMutability: IMMUTABLE
      EncryptionConfiguration:
        EncryptionType: KMS
        KmsKey: !GetAtt ECRKey.Arn
```

**Enhanced scanning with Inspector:**
- Scans OS packages AND programming language packages (npm, pip, Maven, etc.)
- Continuous scanning (re-scans when new CVE is published)
- Findings sent to Security Hub and EventBridge
- Supports severity filtering: CRITICAL, HIGH, MEDIUM, LOW, INFORMATIONAL

### 5.4 Security Hub Integration

**EventBridge rule to fail pipeline on critical findings:**

```yaml
Resources:
  SecurityHubRule:
    Type: AWS::Events::Rule
    Properties:
      EventPattern:
        source: ["aws.securityhub"]
        detail-type: ["Security Hub Findings - Imported"]
        detail:
          findings:
            Severity:
              Label: ["CRITICAL", "HIGH"]
            ProductName: ["Inspector"]
            ResourceType: ["AwsEcrContainerImage"]
      Targets:
        - Arn: !GetAtt RemediationLambda.Arn
          Id: SecurityHubRemediation
```

---

## 6. Terraform for AWS

### 6.1 Standard Project Structure

```
terraform/
  environments/
    dev/
      main.tf
      terraform.tfvars
      backend.tf
    staging/
      main.tf
      terraform.tfvars
      backend.tf
    prod/
      main.tf
      terraform.tfvars
      backend.tf
  modules/
    vpc/
      main.tf
      variables.tf
      outputs.tf
      versions.tf
    ecs/
      main.tf
      variables.tf
      outputs.tf
    rds/
      main.tf
      variables.tf
      outputs.tf
    alb/
      main.tf
      variables.tf
      outputs.tf
  global/
    iam/
      main.tf
    dns/
      main.tf
```

### 6.2 Backend Configuration (S3)

```hcl
# backend.tf
terraform {
  backend "s3" {
    bucket         = "my-terraform-state-111222333"
    key            = "environments/prod/terraform.tfstate"
    region         = "us-east-1"
    encrypt        = true
    dynamodb_table = "terraform-locks"  # For state locking
    use_lockfile   = true               # S3-native locking (newer alternative)
  }
}
```

**State locking options (pick one):**
- DynamoDB table (classic approach): `dynamodb_table = "terraform-locks"`
- S3-native lockfile (newer, simpler): `use_lockfile = true`

### 6.3 Provider Configuration

```hcl
# versions.tf
terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

# providers.tf
provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Environment = var.environment
      Project     = var.project_name
      ManagedBy   = "terraform"
      Owner       = var.team_name
    }
  }

  # Assume role for cross-account
  assume_role {
    role_arn = "arn:aws:iam::${var.target_account_id}:role/TerraformRole"
  }
}

# Secondary provider for multi-region
provider "aws" {
  alias  = "us_west_2"
  region = "us-west-2"
}
```

### 6.4 variables.tf Pattern

```hcl
variable "environment" {
  description = "Deployment environment (dev, staging, prod)"
  type        = string
  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "Environment must be dev, staging, or prod."
  }
}

variable "vpc_cidr" {
  description = "CIDR block for the VPC"
  type        = string
  default     = "10.0.0.0/16"
  validation {
    condition     = can(cidrhost(var.vpc_cidr, 0))
    error_message = "Must be a valid CIDR block."
  }
}

variable "instance_type" {
  description = "EC2 instance type"
  type        = string
  default     = "t3.micro"
}

variable "db_password" {
  description = "Database password"
  type        = string
  sensitive   = true
}

variable "tags" {
  description = "Additional tags"
  type        = map(string)
  default     = {}
}

variable "availability_zones" {
  description = "List of AZs"
  type        = list(string)
  default     = ["us-east-1a", "us-east-1b", "us-east-1c"]
}

variable "enable_nat_gateway" {
  description = "Enable NAT Gateway"
  type        = bool
  default     = true
}
```

### 6.5 outputs.tf Pattern

```hcl
output "vpc_id" {
  description = "The ID of the VPC"
  value       = module.vpc.vpc_id
}

output "private_subnet_ids" {
  description = "List of private subnet IDs"
  value       = module.vpc.private_subnet_ids
}

output "alb_dns_name" {
  description = "ALB DNS name"
  value       = module.alb.dns_name
}

output "ecr_repository_url" {
  description = "ECR repository URL"
  value       = aws_ecr_repository.app.repository_url
}

output "db_endpoint" {
  description = "RDS endpoint"
  value       = module.rds.endpoint
  sensitive   = true
}
```

### 6.6 Common AWS Module Patterns

**VPC Module:**

```hcl
module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 5.0"

  name = "${var.project}-${var.environment}"
  cidr = var.vpc_cidr

  azs             = var.availability_zones
  private_subnets = ["10.0.1.0/24", "10.0.2.0/24", "10.0.3.0/24"]
  public_subnets  = ["10.0.101.0/24", "10.0.102.0/24", "10.0.103.0/24"]

  enable_nat_gateway   = true
  single_nat_gateway   = var.environment != "prod"  # Cost optimization for non-prod
  enable_dns_hostnames = true
  enable_dns_support   = true

  # VPC Flow Logs
  enable_flow_log                      = true
  create_flow_log_cloudwatch_log_group = true
  create_flow_log_iam_role             = true

  tags = var.tags
}
```

**ECS Fargate + ALB Pattern:**

```hcl
resource "aws_ecs_cluster" "main" {
  name = "${var.project}-${var.environment}"

  setting {
    name  = "containerInsights"
    value = "enabled"
  }

  configuration {
    execute_command_configuration {
      logging = "OVERRIDE"
      log_configuration {
        cloud_watch_log_group_name = aws_cloudwatch_log_group.ecs_exec.name
      }
    }
  }
}

resource "aws_ecs_service" "app" {
  name            = var.service_name
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.app.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [aws_security_group.ecs.id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.app.arn
    container_name   = var.container_name
    container_port   = var.container_port
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  lifecycle {
    ignore_changes = [task_definition]  # Managed by CI/CD
  }
}

resource "aws_lb" "main" {
  name               = "${var.project}-${var.environment}-alb"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = var.public_subnet_ids

  enable_deletion_protection = var.environment == "prod"

  access_logs {
    bucket  = var.alb_logs_bucket
    prefix  = "${var.project}-${var.environment}"
    enabled = true
  }
}
```

**RDS Pattern:**

```hcl
module "rds" {
  source  = "terraform-aws-modules/rds/aws"
  version = "~> 6.0"

  identifier = "${var.project}-${var.environment}"

  engine               = "postgres"
  engine_version       = "16.3"
  family               = "postgres16"
  major_engine_version = "16"
  instance_class       = var.environment == "prod" ? "db.r6g.large" : "db.t4g.micro"

  allocated_storage     = 20
  max_allocated_storage = 100

  db_name  = var.db_name
  username = var.db_username
  port     = 5432

  multi_az               = var.environment == "prod"
  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.rds.id]

  backup_retention_period = var.environment == "prod" ? 30 : 7
  deletion_protection     = var.environment == "prod"
  skip_final_snapshot     = var.environment != "prod"

  performance_insights_enabled = true
  monitoring_interval          = 60
  monitoring_role_arn          = aws_iam_role.rds_monitoring.arn

  parameters = [
    { name = "log_connections", value = "1" },
    { name = "log_disconnections", value = "1" }
  ]
}
```

### 6.7 Terraform Best Practices for Agent

1. **State management:** Always use remote backend with locking. Never commit `.tfstate` files
2. **Sensitive variables:** Mark with `sensitive = true`, use `TF_VAR_` environment variables or `.tfvars` files
3. **Provider pinning:** Use `~>` for minor version flexibility, never leave unpinned
4. **Module versioning:** Pin public module versions exactly in production
5. **`lifecycle` blocks:** Use `ignore_changes` for CI/CD-managed attributes (task definitions, image tags)
6. **`prevent_destroy`:** Add to critical resources (databases, S3 buckets with data)
7. **Data sources over hardcoding:** Use `data "aws_ami"`, `data "aws_vpc"`, `data "aws_caller_identity"` instead of hardcoded values
8. **Workspaces vs directories:** Prefer directory-per-environment over workspaces for production (clearer state isolation)
9. **`terraform plan` always:** Never apply without reviewing plan output
10. **`default_tags`:** Use provider-level default_tags for consistent tagging

---

## 7. Docker Best Practices for AWS

### 7.1 Optimized Multi-Stage Dockerfile

```dockerfile
# syntax=docker/dockerfile:1

# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /app

# Copy dependency files first (better layer caching)
COPY pom.xml .
COPY .mvn .mvn

# Download dependencies (cached layer unless pom.xml changes)
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build application
RUN --mount=type=cache,target=/root/.m2 \
    mvn package -DskipTests -B

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-alpine AS runtime

# Security: non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# Copy only the built artifact
COPY --from=builder /app/target/*.jar app.jar

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/health || exit 1

# Switch to non-root user
USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
```

**Node.js variant:**

```dockerfile
FROM node:20-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci --only=production && npm cache clean --force
COPY . .
RUN npm run build

FROM node:20-alpine AS runtime
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app
COPY --from=builder /app/dist ./dist
COPY --from=builder /app/node_modules ./node_modules
COPY --from=builder /app/package.json ./
USER appuser
EXPOSE 3000
CMD ["node", "dist/index.js"]
```

### 7.2 ECR Layer Caching in CodeBuild

**buildspec.yml with ECR remote cache:**

```yaml
version: 0.2

env:
  variables:
    ECR_REPO: "111222333.dkr.ecr.us-east-1.amazonaws.com/my-app"
    ECR_CACHE_REPO: "111222333.dkr.ecr.us-east-1.amazonaws.com/my-app-cache"

phases:
  pre_build:
    commands:
      - aws ecr get-login-password | docker login --username AWS --password-stdin $ECR_REPO
      - COMMIT_HASH=$(echo $CODEBUILD_RESOLVED_SOURCE_VERSION | cut -c 1-7)
      - IMAGE_TAG=${COMMIT_HASH:-latest}

  build:
    commands:
      - |
        docker buildx build \
          --cache-from type=registry,ref=$ECR_CACHE_REPO:cache \
          --cache-to type=registry,ref=$ECR_CACHE_REPO:cache,mode=max \
          --tag $ECR_REPO:$IMAGE_TAG \
          --tag $ECR_REPO:latest \
          --push \
          .

  post_build:
    commands:
      - printf '{"ImageURI":"%s"}' $ECR_REPO:$IMAGE_TAG > imageDetail.json
```

### 7.3 ECR Lifecycle Policy

```json
{
  "rules": [
    {
      "rulePriority": 1,
      "description": "Keep last 10 production images",
      "selection": {
        "tagStatus": "tagged",
        "tagPrefixList": ["v", "release"],
        "countType": "imageCountMoreThan",
        "countNumber": 10
      },
      "action": { "type": "expire" }
    },
    {
      "rulePriority": 2,
      "description": "Expire untagged images after 7 days",
      "selection": {
        "tagStatus": "untagged",
        "countType": "sinceImagePushed",
        "countUnit": "days",
        "countNumber": 7
      },
      "action": { "type": "expire" }
    },
    {
      "rulePriority": 3,
      "description": "Keep last 30 dev images",
      "selection": {
        "tagStatus": "tagged",
        "tagPrefixList": ["dev", "feature"],
        "countType": "imageCountMoreThan",
        "countNumber": 30
      },
      "action": { "type": "expire" }
    }
  ]
}
```

### 7.4 Docker Best Practices for AWS Agents

1. **Multi-stage builds:** Always separate build and runtime stages
2. **Layer ordering:** Dependencies before source code (deps change less often)
3. **`.dockerignore`:** Must include `.git`, `node_modules`, `target/`, `build/`, `*.md`, `.env`
4. **Non-root user:** Always `USER appuser` in runtime stage
5. **Alpine base images:** Smaller attack surface, faster pulls from ECR
6. **Pin base image digests in production:** `FROM node:20-alpine@sha256:abc123...`
7. **HEALTHCHECK:** Always define for ECS health monitoring
8. **ECR immutable tags:** Enable `ImageTagMutability: IMMUTABLE` in production
9. **Multi-arch builds:** Use `docker buildx build --platform linux/amd64,linux/arm64` for Graviton support
10. **Layer caching:** Use `--mount=type=cache` for package manager caches
11. **Container support flags:** JVM: `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0`
12. **Scan on push:** Always enable ECR image scanning

---

## 8. Monitoring & Observability Config

### 8.1 CloudWatch Alarms (CloudFormation)

```yaml
Resources:
  HighCPUAlarm:
    Type: AWS::CloudWatch::Alarm
    Properties:
      AlarmName: !Sub "${AWS::StackName}-HighCPU"
      AlarmDescription: "CPU utilization exceeds 80%"
      MetricName: CPUUtilization
      Namespace: AWS/ECS
      Statistic: Average
      Period: 300
      EvaluationPeriods: 3
      DatapointsToAlarm: 2
      Threshold: 80
      ComparisonOperator: GreaterThanOrEqualToThreshold
      Dimensions:
        - Name: ClusterName
          Value: !Ref ECSCluster
        - Name: ServiceName
          Value: !GetAtt ECSService.Name
      AlarmActions:
        - !Ref SNSAlarmTopic
      OKActions:
        - !Ref SNSAlarmTopic
      TreatMissingData: breaching

  HighMemoryAlarm:
    Type: AWS::CloudWatch::Alarm
    Properties:
      AlarmName: !Sub "${AWS::StackName}-HighMemory"
      MetricName: MemoryUtilization
      Namespace: AWS/ECS
      Statistic: Average
      Period: 300
      EvaluationPeriods: 3
      Threshold: 85
      ComparisonOperator: GreaterThanOrEqualToThreshold
      Dimensions:
        - Name: ClusterName
          Value: !Ref ECSCluster
        - Name: ServiceName
          Value: !GetAtt ECSService.Name
      AlarmActions: [!Ref SNSAlarmTopic]

  ALB5xxAlarm:
    Type: AWS::CloudWatch::Alarm
    Properties:
      AlarmName: !Sub "${AWS::StackName}-ALB-5xx"
      MetricName: HTTPCode_Target_5XX_Count
      Namespace: AWS/ApplicationELB
      Statistic: Sum
      Period: 60
      EvaluationPeriods: 5
      Threshold: 10
      ComparisonOperator: GreaterThanOrEqualToThreshold
      Dimensions:
        - Name: LoadBalancer
          Value: !GetAtt ALB.LoadBalancerFullName
      AlarmActions: [!Ref SNSAlarmTopic]
      TreatMissingData: notBreaching

  TargetResponseTimeAlarm:
    Type: AWS::CloudWatch::Alarm
    Properties:
      AlarmName: !Sub "${AWS::StackName}-HighLatency"
      MetricName: TargetResponseTime
      Namespace: AWS/ApplicationELB
      ExtendedStatistic: p99
      Period: 300
      EvaluationPeriods: 3
      Threshold: 2.0
      ComparisonOperator: GreaterThanOrEqualToThreshold
      Dimensions:
        - Name: LoadBalancer
          Value: !GetAtt ALB.LoadBalancerFullName
      AlarmActions: [!Ref SNSAlarmTopic]
```

### 8.2 CloudWatch Alarms (Terraform)

```hcl
resource "aws_cloudwatch_metric_alarm" "ecs_cpu" {
  alarm_name          = "${var.project}-${var.environment}-ecs-cpu"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 3
  metric_name         = "CPUUtilization"
  namespace           = "AWS/ECS"
  period              = 300
  statistic           = "Average"
  threshold           = 80
  alarm_description   = "ECS service CPU > 80%"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]

  dimensions = {
    ClusterName = aws_ecs_cluster.main.name
    ServiceName = aws_ecs_service.app.name
  }

  treat_missing_data = "breaching"
}
```

### 8.3 CloudWatch Log Group Configuration

```yaml
Resources:
  AppLogGroup:
    Type: AWS::Logs::LogGroup
    Properties:
      LogGroupName: /ecs/my-app
      RetentionInDays: 30  # Options: 1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288, 3653, 0 (never expire)
      KmsKeyId: !GetAtt LogEncryptionKey.Arn
      Tags:
        - Key: Environment
          Value: !Ref Environment
```

### 8.4 X-Ray Integration

**ECS task definition with X-Ray sidecar:**

```json
{
  "containerDefinitions": [
    {
      "name": "xray-daemon",
      "image": "public.ecr.aws/xray/aws-xray-daemon:latest",
      "essential": false,
      "cpu": 32,
      "memoryReservation": 256,
      "portMappings": [
        { "containerPort": 2000, "protocol": "udp" }
      ],
      "environment": [
        { "name": "AWS_REGION", "value": "us-east-1" }
      ]
    }
  ]
}
```

**Task role permissions for X-Ray:**

```json
{
  "Effect": "Allow",
  "Action": [
    "xray:PutTraceSegments",
    "xray:PutTelemetryRecords",
    "xray:GetSamplingRules",
    "xray:GetSamplingTargets"
  ],
  "Resource": "*"
}
```

### 8.5 Container Insights (ECS)

**Enable via CLI:** `aws ecs update-cluster-settings --cluster my-cluster --settings name=containerInsights,value=enabled`

**CloudFormation:**

```yaml
Resources:
  ECSCluster:
    Type: AWS::ECS::Cluster
    Properties:
      ClusterName: my-cluster
      ClusterSettings:
        - Name: containerInsights
          Value: enabled
```

**Container Insights metrics:** `CpuUtilized`, `CpuReserved`, `MemoryUtilized`, `MemoryReserved`, `NetworkRxBytes`, `NetworkTxBytes`, `StorageReadBytes`, `StorageWriteBytes`, `RunningTaskCount`, `DesiredTaskCount`

### 8.6 CloudWatch Dashboard (JSON definition)

```json
{
  "widgets": [
    {
      "type": "metric",
      "properties": {
        "metrics": [
          ["AWS/ECS", "CPUUtilization", "ClusterName", "my-cluster", "ServiceName", "my-service"],
          ["AWS/ECS", "MemoryUtilization", "ClusterName", "my-cluster", "ServiceName", "my-service"]
        ],
        "period": 300,
        "stat": "Average",
        "region": "us-east-1",
        "title": "ECS CPU & Memory"
      }
    },
    {
      "type": "metric",
      "properties": {
        "metrics": [
          ["AWS/ApplicationELB", "RequestCount", "LoadBalancer", "app/my-alb/1234567890"],
          ["AWS/ApplicationELB", "HTTPCode_Target_5XX_Count", "LoadBalancer", "app/my-alb/1234567890"]
        ],
        "period": 60,
        "stat": "Sum",
        "title": "ALB Request Count & 5xx Errors"
      }
    },
    {
      "type": "log",
      "properties": {
        "query": "SOURCE '/ecs/my-app' | filter @message like /ERROR/ | stats count(*) as errors by bin(5m)",
        "region": "us-east-1",
        "title": "Application Errors"
      }
    }
  ]
}
```

---

## 9. GitHub Actions + AWS

### 9.1 OIDC Federation Setup

**IAM Trust Policy for GitHub Actions:**

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::111222333:oidc-provider/token.actions.githubusercontent.com"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "token.actions.githubusercontent.com:aud": "sts.amazonaws.com"
        },
        "StringLike": {
          "token.actions.githubusercontent.com:sub": "repo:my-org/my-repo:ref:refs/heads/main"
        }
      }
    }
  ]
}
```

**Subject claim patterns:**
- Specific branch: `repo:org/repo:ref:refs/heads/main`
- Any branch: `repo:org/repo:ref:refs/heads/*`
- Tags: `repo:org/repo:ref:refs/tags/v*`
- Pull requests: `repo:org/repo:pull_request`
- Environment: `repo:org/repo:environment:production`

### 9.2 Complete ECS Deploy Workflow

```yaml
name: Deploy to ECS

on:
  push:
    branches: [main]
  workflow_dispatch:
    inputs:
      environment:
        description: 'Target environment'
        required: true
        type: choice
        options: [staging, production]

permissions:
  id-token: write
  contents: read

env:
  AWS_REGION: us-east-1
  ECR_REPOSITORY: my-app
  ECS_CLUSTER: my-cluster
  ECS_SERVICE: my-service
  TASK_DEFINITION: .aws/taskdef.json
  CONTAINER_NAME: app

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: gradle
      - run: ./gradlew test

  build-and-deploy:
    needs: test
    runs-on: ubuntu-latest
    environment:
      name: ${{ inputs.environment || 'staging' }}
      url: ${{ steps.deploy.outputs.url }}

    steps:
      - uses: actions/checkout@v4

      - name: Configure AWS credentials
        uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: arn:aws:iam::111222333:role/GitHubActionsRole
          aws-region: ${{ env.AWS_REGION }}

      - name: Login to Amazon ECR
        id: login-ecr
        uses: aws-actions/amazon-ecr-login@v2

      - name: Build, tag, and push image to ECR
        id: build-image
        env:
          ECR_REGISTRY: ${{ steps.login-ecr.outputs.registry }}
          IMAGE_TAG: ${{ github.sha }}
        run: |
          docker build -t $ECR_REGISTRY/$ECR_REPOSITORY:$IMAGE_TAG .
          docker push $ECR_REGISTRY/$ECR_REPOSITORY:$IMAGE_TAG
          echo "image=$ECR_REGISTRY/$ECR_REPOSITORY:$IMAGE_TAG" >> $GITHUB_OUTPUT

      - name: Fill in the new image ID in the task definition
        id: task-def
        uses: aws-actions/amazon-ecs-render-task-definition@v1
        with:
          task-definition: ${{ env.TASK_DEFINITION }}
          container-name: ${{ env.CONTAINER_NAME }}
          image: ${{ steps.build-image.outputs.image }}

      - name: Deploy to Amazon ECS
        id: deploy
        uses: aws-actions/amazon-ecs-deploy-task-definition@v2
        with:
          task-definition: ${{ steps.task-def.outputs.task-definition }}
          service: ${{ env.ECS_SERVICE }}
          cluster: ${{ env.ECS_CLUSTER }}
          wait-for-service-stability: true
          wait-for-minutes: 10

      # Alternative: Blue/Green with CodeDeploy
      # - name: Deploy with CodeDeploy
      #   uses: aws-actions/amazon-ecs-deploy-task-definition@v2
      #   with:
      #     task-definition: ${{ steps.task-def.outputs.task-definition }}
      #     service: ${{ env.ECS_SERVICE }}
      #     cluster: ${{ env.ECS_CLUSTER }}
      #     codedeploy-appspec: .aws/appspec.yaml
      #     codedeploy-application: my-app
      #     codedeploy-deployment-group: my-deployment-group
      #     wait-for-service-stability: true
```

### 9.3 Terraform Deploy Workflow

```yaml
name: Terraform

on:
  pull_request:
    paths: ['terraform/**']
  push:
    branches: [main]
    paths: ['terraform/**']

permissions:
  id-token: write
  contents: read
  pull-requests: write

jobs:
  plan:
    runs-on: ubuntu-latest
    if: github.event_name == 'pull_request'
    steps:
      - uses: actions/checkout@v4

      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: ${{ vars.AWS_ROLE_ARN }}
          aws-region: us-east-1

      - uses: hashicorp/setup-terraform@v3
        with:
          terraform_version: "1.7.x"

      - name: Terraform Init
        working-directory: terraform/environments/${{ vars.ENVIRONMENT }}
        run: terraform init

      - name: Terraform Plan
        id: plan
        working-directory: terraform/environments/${{ vars.ENVIRONMENT }}
        run: terraform plan -no-color -out=tfplan
        continue-on-error: true

      - name: Comment PR with plan
        uses: actions/github-script@v7
        with:
          script: |
            const output = `#### Terraform Plan \`${{ steps.plan.outcome }}\`
            <details><summary>Show Plan</summary>

            \`\`\`terraform
            ${{ steps.plan.outputs.stdout }}
            \`\`\`

            </details>`;
            github.rest.issues.createComment({
              issue_number: context.issue.number,
              owner: context.repo.owner,
              repo: context.repo.repo,
              body: output
            })

  apply:
    runs-on: ubuntu-latest
    if: github.ref == 'refs/heads/main' && github.event_name == 'push'
    environment: production
    steps:
      - uses: actions/checkout@v4

      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: ${{ vars.AWS_ROLE_ARN }}
          aws-region: us-east-1

      - uses: hashicorp/setup-terraform@v3

      - name: Terraform Init & Apply
        working-directory: terraform/environments/prod
        run: |
          terraform init
          terraform apply -auto-approve
```

### 9.4 Lambda Deploy Workflow (SAM)

```yaml
name: Deploy Lambda

on:
  push:
    branches: [main]

permissions:
  id-token: write
  contents: read

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-python@v5
        with:
          python-version: '3.12'

      - uses: aws-actions/setup-sam@v2

      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: ${{ vars.AWS_ROLE_ARN }}
          aws-region: us-east-1

      - name: SAM Build
        run: sam build --use-container

      - name: SAM Deploy
        run: |
          sam deploy \
            --no-confirm-changeset \
            --no-fail-on-empty-changeset \
            --stack-name my-app-${{ vars.ENVIRONMENT }} \
            --parameter-overrides Stage=${{ vars.ENVIRONMENT }} \
            --capabilities CAPABILITY_IAM CAPABILITY_AUTO_EXPAND
```

### 9.5 Key GitHub Actions for AWS

| Action | Purpose |
|---|---|
| `aws-actions/configure-aws-credentials@v4` | OIDC authentication with AWS |
| `aws-actions/amazon-ecr-login@v2` | Docker login to ECR |
| `aws-actions/amazon-ecs-render-task-definition@v1` | Update image in task def JSON |
| `aws-actions/amazon-ecs-deploy-task-definition@v2` | Deploy to ECS (rolling or CodeDeploy) |
| `aws-actions/setup-sam@v2` | Install SAM CLI |
| `aws-actions/aws-cloudformation-github-deploy@v1` | Deploy CloudFormation stacks |
| `aws-actions/amazon-ecs-run-task@v1` | Run one-off ECS tasks |
| `aws-actions/aws-secretsmanager-get-secrets@v2` | Fetch secrets into env vars |

---

## 10. Complete DevOps File Type Reference

### 10.1 Master File Type List

The following is every config file type a DevOps AI agent must be able to read, write, validate, and modify:

#### Docker & Containers
| File | Format | Purpose | Key Knowledge |
|---|---|---|---|
| `Dockerfile` | Dockerfile DSL | Container image build | Multi-stage, layer order, non-root, HEALTHCHECK |
| `.dockerignore` | Glob patterns | Exclude from build context | Must include .git, node_modules, target/, .env |
| `docker-compose.yml` | YAML | Multi-container local dev | Services, volumes, networks, depends_on, env_file |
| `docker-compose.override.yml` | YAML | Local dev overrides | Auto-merged with docker-compose.yml |
| `docker-compose.prod.yml` | YAML | Production overrides | Use with `-f` flag |

#### AWS CI/CD
| File | Format | Purpose |
|---|---|---|
| `buildspec.yml` | YAML | CodeBuild build specification |
| `appspec.yml` / `appspec.yaml` | YAML/JSON | CodeDeploy deployment specification |
| `imagedefinitions.json` | JSON | ECS standard deploy image mapping |
| `imageDetail.json` | JSON | CodeDeploy blue/green image URI |
| `taskdef.json` | JSON | ECS task definition |
| `samconfig.toml` | TOML | SAM CLI deployment config |
| `template.yaml` / `template.yml` | YAML | SAM/CloudFormation template |

#### AWS Infrastructure
| File | Format | Purpose |
|---|---|---|
| `*.template.yaml` / `*.template.json` | YAML/JSON | CloudFormation templates |
| `cdk.json` | JSON | CDK app config |
| `cdk.context.json` | JSON | CDK context values (cached) |
| `bin/*.ts` | TypeScript | CDK app entry point |
| `lib/*.ts` | TypeScript | CDK stack/construct definitions |

#### Terraform
| File | Format | Purpose |
|---|---|---|
| `main.tf` | HCL | Primary resource definitions |
| `variables.tf` | HCL | Input variable declarations |
| `outputs.tf` | HCL | Output value declarations |
| `providers.tf` | HCL | Provider configuration |
| `versions.tf` | HCL | Required provider versions |
| `backend.tf` | HCL | State backend configuration |
| `terraform.tfvars` | HCL | Variable values (default) |
| `*.auto.tfvars` | HCL | Auto-loaded variable values |
| `*.tfvars` | HCL | Environment-specific values |
| `.terraform.lock.hcl` | HCL | Provider dependency lock |

#### Kubernetes & Helm
| File | Format | Purpose |
|---|---|---|
| `deployment.yaml` | YAML | K8s Deployment manifest |
| `service.yaml` | YAML | K8s Service manifest |
| `ingress.yaml` | YAML | K8s Ingress manifest |
| `configmap.yaml` | YAML | K8s ConfigMap manifest |
| `secret.yaml` | YAML | K8s Secret manifest |
| `hpa.yaml` | YAML | HorizontalPodAutoscaler |
| `pdb.yaml` | YAML | PodDisruptionBudget |
| `networkpolicy.yaml` | YAML | K8s NetworkPolicy |
| `serviceaccount.yaml` | YAML | K8s ServiceAccount (with IRSA annotation) |
| `namespace.yaml` | YAML | K8s Namespace |
| `kustomization.yaml` | YAML | Kustomize overlay configuration |
| `Chart.yaml` | YAML | Helm chart metadata |
| `values.yaml` | YAML | Helm default values |
| `values-*.yaml` | YAML | Environment-specific Helm values |
| `templates/_helpers.tpl` | Go template | Helm helper functions |
| `templates/*.yaml` | Go template + YAML | Helm resource templates |
| `helmfile.yaml` | YAML | Helmfile declarative config |

#### CI/CD Pipelines
| File | Format | Purpose |
|---|---|---|
| `.github/workflows/*.yml` | YAML | GitHub Actions workflows |
| `Jenkinsfile` | Groovy DSL | Jenkins pipeline definition |
| `.gitlab-ci.yml` | YAML | GitLab CI/CD config |
| `bitbucket-pipelines.yml` | YAML | Bitbucket Pipelines config |
| `.circleci/config.yml` | YAML | CircleCI config |
| `azure-pipelines.yml` | YAML | Azure DevOps pipelines |
| `Makefile` | Make DSL | Build automation targets |

#### Application & Build Config
| File | Format | Purpose |
|---|---|---|
| `package.json` | JSON | Node.js project/deps (scripts section for CI) |
| `pom.xml` | XML | Maven project config |
| `build.gradle` / `build.gradle.kts` | Groovy/Kotlin | Gradle build config |
| `settings.gradle` / `settings.gradle.kts` | Groovy/Kotlin | Multi-module Gradle config |
| `requirements.txt` | Text | Python dependencies |
| `pyproject.toml` | TOML | Python project config |
| `Cargo.toml` | TOML | Rust project config |
| `go.mod` | Go module | Go dependency management |

#### Security & Secrets
| File | Format | Purpose |
|---|---|---|
| `.env` | Key=Value | Environment variables (NEVER commit) |
| `.env.example` | Key=Value | Template for required env vars |
| `trust-policy.json` | JSON | IAM role trust policy |
| `policy.json` | JSON | IAM policy document |
| `sops.yaml` | YAML | SOPS encrypted secrets config |

#### Monitoring & Observability
| File | Format | Purpose |
|---|---|---|
| `cloudwatch-alarms.yaml` | YAML | CloudFormation alarm definitions |
| `dashboard.json` | JSON | CloudWatch dashboard definition |
| `prometheus.yml` | YAML | Prometheus scrape config |
| `grafana-dashboard.json` | JSON | Grafana dashboard definition |
| `alertmanager.yml` | YAML | Alertmanager routing rules |

#### Linting & Quality
| File | Format | Purpose |
|---|---|---|
| `.hadolint.yaml` | YAML | Dockerfile linter config |
| `.tflint.hcl` | HCL | Terraform linter config |
| `.cfn-lint` | YAML | CloudFormation linter config |
| `.yamllint.yml` | YAML | YAML linter config |
| `.eslintrc.json` | JSON | JavaScript/TS linter |
| `sonar-project.properties` | Properties | SonarQube project config |
| `.trivyignore` | Text | Trivy vulnerability ignore list |
| `checkov.yaml` | YAML | Checkov IaC scanning config |

### 10.2 File Detection Heuristics for Agent

The agent should detect project type by looking for these marker files:

```
CloudFormation project:
  - template.yaml with AWSTemplateFormatVersion
  - *.template.yaml with Resources section containing AWS:: resource types

SAM project:
  - template.yaml with Transform: AWS::Serverless-2016-10-31
  - samconfig.toml

CDK project:
  - cdk.json + bin/*.ts + lib/*.ts
  - package.json with @aws-cdk/core or aws-cdk-lib dependency

Terraform project:
  - *.tf files
  - .terraform/ directory
  - .terraform.lock.hcl

ECS project:
  - taskdef.json with containerDefinitions
  - docker-compose.yml + ecs-params.yml

Kubernetes/EKS project:
  - manifests with apiVersion + kind fields
  - Chart.yaml (Helm)
  - kustomization.yaml (Kustomize)

GitHub Actions:
  - .github/workflows/*.yml

Jenkins:
  - Jenkinsfile in root

Docker project:
  - Dockerfile in root or subdirectory
```

### 10.3 Validation Commands the Agent Should Know

```bash
# CloudFormation
aws cloudformation validate-template --template-body file://template.yaml
cfn-lint template.yaml

# SAM
sam validate
sam build  # Also validates

# Terraform
terraform validate
terraform fmt -check
tflint

# Kubernetes manifests
kubectl apply --dry-run=client -f manifest.yaml
kubectl apply --dry-run=server -f manifest.yaml
kubeval manifest.yaml
kubeconform manifest.yaml

# Helm
helm lint ./my-chart
helm template ./my-chart | kubeval
helm template ./my-chart --values values-prod.yaml

# Docker
hadolint Dockerfile
docker build --check .  # BuildKit check mode

# GitHub Actions
actionlint .github/workflows/*.yml

# YAML generic
yamllint -c .yamllint.yml file.yaml

# Security scanning
trivy config .
checkov -d .
tfsec .
```

---

## Sources

- [CodeBuild buildspec.yml reference](https://docs.aws.amazon.com/codebuild/latest/userguide/build-spec-ref.html)
- [CodeDeploy AppSpec file reference](https://docs.aws.amazon.com/codedeploy/latest/userguide/reference-appspec-file.html)
- [CloudFormation template anatomy](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/template-anatomy.html)
- [CloudFormation best practices](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/best-practices.html)
- [ECS task definition parameters for Fargate](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task_definition_parameters.html)
- [ECS task definition template](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task-definition-template.html)
- [SAM template anatomy](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/sam-specification-template-anatomy.html)
- [CDK best practices for TypeScript IaC](https://docs.aws.amazon.com/prescriptive-guidance/latest/best-practices-cdk-typescript-iac/introduction.html)
- [CDK Pipelines](https://docs.aws.amazon.com/cdk/api/v2/docs/aws-cdk-lib.pipelines-readme.html)
- [CodePipeline pipeline structure reference](https://docs.aws.amazon.com/codepipeline/latest/userguide/reference-pipeline-structure.html)
- [GitHub Actions OIDC for AWS](https://docs.github.com/actions/deployment/security-hardening-your-deployments/configuring-openid-connect-in-amazon-web-services)
- [aws-actions/configure-aws-credentials](https://github.com/aws-actions/configure-aws-credentials)
- [aws-actions/amazon-ecs-deploy-task-definition](https://github.com/aws-actions/amazon-ecs-deploy-task-definition)
- [CodeDeploy deployment configurations](https://docs.aws.amazon.com/codedeploy/latest/userguide/deployment-configurations.html)
- [ECS blue/green deployments](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/deployment-type-bluegreen.html)
- [Lambda canary deployments](https://docs.aws.amazon.com/lambda/latest/dg/configuring-alias-routing.html)
- [CodePipeline IAM security](https://docs.aws.amazon.com/codepipeline/latest/userguide/security-iam.html)
- [CodeBuild defense-in-depth security](https://aws.amazon.com/blogs/security/implementing-defense-security-for-aws-codebuild-pipelines/)
- [ECR image scanning](https://docs.aws.amazon.com/AmazonECR/latest/userguide/image-scanning.html)
- [ECR lifecycle policies](https://docs.aws.amazon.com/AmazonECR/latest/userguide/LifecyclePolicies.html)
- [Security Hub container scanning](https://aws.amazon.com/blogs/security/how-to-build-ci-cd-pipeline-container-vulnerability-scanning-trivy-and-aws-security-hub/)
- [ECR remote Docker layer cache](https://aws.amazon.com/blogs/devops/reduce-docker-image-build-time-on-aws-codebuild-using-amazon-ecr-as-a-remote-cache/)
- [Terraform S3 backend](https://developer.hashicorp.com/terraform/language/backend/s3)
- [Terraform module creation](https://developer.hashicorp.com/terraform/tutorials/modules/pattern-module-creation)
- [terraform-aws-modules/security-group](https://registry.terraform.io/modules/terraform-aws-modules/security-group/aws/latest)
- [CloudWatch Container Insights](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/ContainerInsights.html)
- [CloudWatch alarm best practices](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/Best-Practice-Alarms.html)
- [CodeArtifact with Maven](https://docs.aws.amazon.com/codeartifact/latest/ug/using-maven.html)
- [CodeArtifact with Gradle](https://docs.aws.amazon.com/codeartifact/latest/ug/maven-gradle.html)
- [Deploy to EKS with Helm](https://docs.aws.amazon.com/eks/latest/userguide/helm.html)
- [Helm Charts documentation](https://helm.sh/docs/topics/charts/)
- [Kubernetes Configuration Good Practices](https://kubernetes.io/blog/2025/11/25/configuration-good-practices/)
- [Docker Compose Best Practices for Local Development](https://dasroot.net/posts/2026/01/docker-compose-best-practices-local-development/)
- [Jenkins Pipeline AWS Steps](https://www.jenkins.io/doc/pipeline/steps/pipeline-aws/)
