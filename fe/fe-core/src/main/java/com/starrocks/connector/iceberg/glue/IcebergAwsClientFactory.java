// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.connector.iceberg.glue;

import com.google.common.base.Preconditions;
import org.apache.iceberg.aws.AwsClientFactory;
import org.apache.iceberg.aws.AwsProperties;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.GlueClientBuilder;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static com.starrocks.credential.CloudConfigurationConstants.AWS_GLUE_ACCESS_KEY;
import static com.starrocks.credential.CloudConfigurationConstants.AWS_GLUE_ENDPOINT;
import static com.starrocks.credential.CloudConfigurationConstants.AWS_GLUE_EXTERNAL_ID;
import static com.starrocks.credential.CloudConfigurationConstants.AWS_GLUE_IAM_ROLE_ARN;
import static com.starrocks.credential.CloudConfigurationConstants.AWS_GLUE_REGION;
import static com.starrocks.credential.CloudConfigurationConstants.AWS_GLUE_SECRET_KEY;
import static com.starrocks.credential.CloudConfigurationConstants.AWS_GLUE_USE_AWS_SDK_DEFAULT_BEHAVIOR;
import static com.starrocks.credential.CloudConfigurationConstants.AWS_GLUE_USE_INSTANCE_PROFILE;
import static com.starrocks.credential.CloudConfigurationConstants.AWS_S3_ACCESS_KEY;
import static com.starrocks.credential.CloudConfigurationConstants.AWS_S3_ENDPOINT;
import static com.starrocks.credential.CloudConfigurationConstants.AWS_S3_EXTERNAL_ID;
import static com.starrocks.credential.CloudConfigurationConstants.AWS_S3_IAM_ROLE_ARN;
import static com.starrocks.credential.CloudConfigurationConstants.AWS_S3_REGION;
import static com.starrocks.credential.CloudConfigurationConstants.AWS_S3_SECRET_KEY;
import static com.starrocks.credential.CloudConfigurationConstants.AWS_S3_USE_AWS_SDK_DEFAULT_BEHAVIOR;
import static com.starrocks.credential.CloudConfigurationConstants.AWS_S3_USE_INSTANCE_PROFILE;

public class IcebergAwsClientFactory implements AwsClientFactory {
    public IcebergAwsClientFactory() {
    }

    private AwsProperties awsProperties;

    private boolean s3UseAWSSDKDefaultBehavior;
    private boolean s3UseInstanceProfile;
    private String s3AccessKey;
    private String s3SecretKey;
    private String s3IamRoleArn;
    private String s3ExternalId;
    private String s3Region;
    private String s3Endpoint;

    private boolean glueUseAWSSDKDefaultBehavior;
    private boolean glueUseInstanceProfile;
    private String glueAccessKey;
    private String glueSecretKey;
    private String glueIamRoleArn;
    private String glueExternalId;
    private String glueRegion;
    private String glueEndpoint;


    @Override
    public void initialize(Map<String, String> properties) {
        this.awsProperties = new AwsProperties(properties);

        s3UseAWSSDKDefaultBehavior = Boolean.parseBoolean(properties.getOrDefault(AWS_S3_USE_AWS_SDK_DEFAULT_BEHAVIOR, "false"));
        s3UseInstanceProfile = Boolean.parseBoolean(properties.getOrDefault(AWS_S3_USE_INSTANCE_PROFILE, "false"));
        s3AccessKey = properties.getOrDefault(AWS_S3_ACCESS_KEY, "");
        s3SecretKey = properties.getOrDefault(AWS_S3_SECRET_KEY, "");
        s3IamRoleArn = properties.getOrDefault(AWS_S3_IAM_ROLE_ARN, "");
        s3ExternalId = properties.getOrDefault(AWS_S3_EXTERNAL_ID, "");
        s3Region = properties.getOrDefault(AWS_S3_REGION, "");
        s3Endpoint = properties.getOrDefault(AWS_S3_ENDPOINT, "");

        glueUseAWSSDKDefaultBehavior = Boolean.parseBoolean(
                properties.getOrDefault(AWS_GLUE_USE_AWS_SDK_DEFAULT_BEHAVIOR, "false"));
        glueUseInstanceProfile = Boolean.parseBoolean(properties.getOrDefault(AWS_GLUE_USE_INSTANCE_PROFILE, "false"));
        glueAccessKey = properties.getOrDefault(AWS_GLUE_ACCESS_KEY, "");
        glueSecretKey = properties.getOrDefault(AWS_GLUE_SECRET_KEY, "");
        glueIamRoleArn = properties.getOrDefault(AWS_GLUE_IAM_ROLE_ARN, "");
        glueExternalId = properties.getOrDefault(AWS_GLUE_EXTERNAL_ID, "");
        glueRegion = properties.getOrDefault(AWS_GLUE_REGION, "");
        glueEndpoint = properties.getOrDefault(AWS_GLUE_ENDPOINT, "");
    }

    @Override
    public S3Client s3() {
        if (s3UseAWSSDKDefaultBehavior) {
            AwsCredentialsProvider provider = DefaultCredentialsProvider.builder().build();
            return S3Client.builder().credentialsProvider(provider).build();
        }

        AwsCredentialsProvider baseAWSCredentialsProvider =
                getBaseAWSCredentialsProvider(s3UseInstanceProfile, s3AccessKey, s3SecretKey);
        S3ClientBuilder s3ClientBuilder = S3Client.builder();
        if (!s3IamRoleArn.isEmpty()) {
            StsClient stsClient = StsClient.builder().credentialsProvider(baseAWSCredentialsProvider).build();
            String sessionName = UUID.randomUUID().toString();
            AssumeRoleRequest.Builder assumeRoleBuilder = AssumeRoleRequest.builder();
            assumeRoleBuilder.roleArn(s3IamRoleArn);
            if (!s3ExternalId.isEmpty()) {
                assumeRoleBuilder.externalId(s3ExternalId);
            }
            assumeRoleBuilder.roleSessionName(sessionName);
            StsAssumeRoleCredentialsProvider.Builder builder = StsAssumeRoleCredentialsProvider.builder()
                    .stsClient(stsClient)
                    .refreshRequest(AssumeRoleRequest.builder()
                            .build());
            s3ClientBuilder.credentialsProvider(builder.build());
        } else {
            s3ClientBuilder.credentialsProvider(baseAWSCredentialsProvider);
        }

        if (!s3Region.isEmpty()) {
            s3ClientBuilder.region(Region.of(s3Region));
        }

        if (!s3Endpoint.isEmpty()) {
            s3ClientBuilder.endpointOverride(URI.create(s3Endpoint));
        }

        s3ClientBuilder.applyMutation(awsProperties::applyHttpClientConfigurations);

        return s3ClientBuilder.build();
    }

    @Override
    public GlueClient glue() {
        if (glueUseAWSSDKDefaultBehavior) {
            AwsCredentialsProvider provider = DefaultCredentialsProvider.builder().build();
            return GlueClient.builder().credentialsProvider(provider).build();
        }

        AwsCredentialsProvider baseAWSCredentialsProvider =
                getBaseAWSCredentialsProvider(glueUseInstanceProfile, glueAccessKey, glueSecretKey);
        GlueClientBuilder glueClientBuilder = GlueClient.builder();
        if (!glueIamRoleArn.isEmpty()) {
            StsClient stsClient = StsClient.builder().credentialsProvider(baseAWSCredentialsProvider).build();
            String sessionName = UUID.randomUUID().toString();
            AssumeRoleRequest.Builder assumeRoleBuilder = AssumeRoleRequest.builder();
            assumeRoleBuilder.roleArn(glueIamRoleArn);
            if (!glueExternalId.isEmpty()) {
                assumeRoleBuilder.externalId(glueExternalId);
            }
            assumeRoleBuilder.roleSessionName(sessionName);
            StsAssumeRoleCredentialsProvider.Builder builder =
                    StsAssumeRoleCredentialsProvider.builder()
                            .stsClient(stsClient)
                            .refreshRequest(AssumeRoleRequest.builder().build());
            glueClientBuilder.credentialsProvider(builder.build());
        } else {
            glueClientBuilder.credentialsProvider(baseAWSCredentialsProvider);
        }

        if (!glueRegion.isEmpty()) {
            glueClientBuilder.region(Region.of(glueRegion));
        }

        if (!glueEndpoint.isEmpty()) {
            glueClientBuilder.endpointOverride(URI.create(glueEndpoint));
        }

        glueClientBuilder.applyMutation(awsProperties::applyHttpClientConfigurations);
        return glueClientBuilder.build();
    }

    private AwsCredentialsProvider getBaseAWSCredentialsProvider(boolean useInstanceProfile, String accessKey, String secretKey) {
        if (useInstanceProfile) {
            return InstanceProfileCredentialsProvider.builder().build();
        } else if (!accessKey.isEmpty() && !secretKey.isEmpty()) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        } else {
            Preconditions.checkArgument(false, "Unreachable");
            return AnonymousCredentialsProvider.create();
        }
    }

    @Override
    public KmsClient kms() {
        return null;
    }

    @Override
    public DynamoDbClient dynamo() {
        return null;
    }
}