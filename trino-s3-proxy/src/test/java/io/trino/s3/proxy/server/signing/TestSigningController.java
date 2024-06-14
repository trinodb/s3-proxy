/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.s3.proxy.server.signing;

import io.airlift.units.Duration;
import io.trino.s3.proxy.server.credentials.CredentialsController;
import io.trino.s3.proxy.server.testing.TestingRemoteS3Facade;
import io.trino.s3.proxy.spi.credentials.Credential;
import io.trino.s3.proxy.spi.credentials.Credentials;
import io.trino.s3.proxy.spi.credentials.CredentialsProvider;
import io.trino.s3.proxy.spi.signing.SigningController;
import io.trino.s3.proxy.spi.signing.SigningMetadata;
import io.trino.s3.proxy.spi.signing.SigningServiceType;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestSigningController
{
    private static final Credentials CREDENTIALS = Credentials.build(new Credential("THIS_IS_AN_ACCESS_KEY", "THIS_IS_A_SECRET_KEY"));

    private final CredentialsProvider credentialsProvider = (emulatedAccessKey, session) -> Optional.of(CREDENTIALS);
    private final CredentialsController credentialsController = new CredentialsController(new TestingRemoteS3Facade(), credentialsProvider);
    private final SigningControllerImpl signingController = new SigningControllerImpl(credentialsController, new SigningControllerConfig().setMaxClockDrift(new Duration(99999, TimeUnit.DAYS)));

    @Test
    public void testRootLs()
    {
        // values discovered from an AWS CLI request sent to a dummy local HTTP server
        MultivaluedMap<String, String> requestHeaders = new MultivaluedHashMap<>();
        requestHeaders.putSingle("X-Amz-Date", "20240516T024511Z");
        requestHeaders.putSingle("X-Amz-Content-SHA256", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        requestHeaders.putSingle("X-Amz-Security-Token", "FwoGZXIvYXdzEP3//////////wEaDG79rlcAjsgKPP9N3SKIAu7/Zvngne5Ov6kGrDcIIPUZYkGpwNbj8zNnbWgOhiqmOCM3hrk4NuH17mP5n3nC7urlXZxaTCywKpAHpO3YsvLXcwjlfaYFA0Au4oejwSbU9ybIlzPzrqz7lVesgCfJOV+rj5F5UAh19d7RpRpA6Vy4nxGBTTlCNIVbkW9fp2Esql2/vsdh77rAG+j+BQegtegDCKBfen4gHMdvEOF6hyc4ne43eLXjpvUKxBgpI9MjOHtNHrDbOOBFXDDyknoESgE9Hsm12nDuVQhwrI/hhA4YB/MSIpl4FTgVs2sQP3K+v65tmyvIlpL6O78S6spMM9Tv/F4JLtksTzb90w46uZk9sxKC/RBkRijisM6tBjIrr/0znxnW3i5ggGAX4H/Z3aWlxSdzNs2UGWtqig9Plp3Xa9gG+zCKcXmDAA==");
        requestHeaders.putSingle("Host", "localhost:10064");
        requestHeaders.putSingle("User-Agent", "aws-cli/2.15.16 Python/3.11.7 Darwin/22.6.0 source/x86_64 prompt/off command/s3.ls");
        requestHeaders.putSingle("Accept-Encoding", "identity");

        String signature = signingController.signRequest(
                new SigningMetadata(SigningServiceType.S3, CREDENTIALS, Optional.empty(), "us-east-1"),
                Credentials::emulated,
                URI.create("http://localhost:10064/"),
                requestHeaders,
                new MultivaluedHashMap<>(),
                "GET",
                Optional.empty());

        assertThat(signature).isEqualTo("AWS4-HMAC-SHA256 Credential=THIS_IS_AN_ACCESS_KEY/20240516/us-east-1/s3/aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date;x-amz-security-token, Signature=9a19c251bf4e1533174e80da59fa57c65b3149b611ec9a4104f6944767c25704");
    }

    @Test
    public void testRootExpiredClock()
    {
        SigningController signingController = new SigningControllerImpl(credentialsController, new SigningControllerConfig().setMaxClockDrift(new Duration(1, TimeUnit.MINUTES)));

        // values discovered from an AWS CLI request sent to a dummy local HTTP server
        MultivaluedMap<String, String> requestHeaders = new MultivaluedHashMap<>();
        requestHeaders.putSingle("X-Amz-Date", "20240516T024511Z");
        requestHeaders.putSingle("X-Amz-Content-SHA256", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        requestHeaders.putSingle("X-Amz-Security-Token", "FwoGZXIvYXdzEP3//////////wEaDG79rlcAjsgKPP9N3SKIAu7/Zvngne5Ov6kGrDcIIPUZYkGpwNbj8zNnbWgOhiqmOCM3hrk4NuH17mP5n3nC7urlXZxaTCywKpAHpO3YsvLXcwjlfaYFA0Au4oejwSbU9ybIlzPzrqz7lVesgCfJOV+rj5F5UAh19d7RpRpA6Vy4nxGBTTlCNIVbkW9fp2Esql2/vsdh77rAG+j+BQegtegDCKBfen4gHMdvEOF6hyc4ne43eLXjpvUKxBgpI9MjOHtNHrDbOOBFXDDyknoESgE9Hsm12nDuVQhwrI/hhA4YB/MSIpl4FTgVs2sQP3K+v65tmyvIlpL6O78S6spMM9Tv/F4JLtksTzb90w46uZk9sxKC/RBkRijisM6tBjIrr/0znxnW3i5ggGAX4H/Z3aWlxSdzNs2UGWtqig9Plp3Xa9gG+zCKcXmDAA==");
        requestHeaders.putSingle("Host", "localhost:10064");
        requestHeaders.putSingle("User-Agent", "aws-cli/2.15.16 Python/3.11.7 Darwin/22.6.0 source/x86_64 prompt/off command/s3.ls");
        requestHeaders.putSingle("Accept-Encoding", "identity");

        assertThatThrownBy(() -> signingController.signRequest(
                new SigningMetadata(SigningServiceType.S3, CREDENTIALS, Optional.empty(), "us-east-1"),
                Credentials::emulated,
                URI.create("http://localhost:10064/"),
                requestHeaders,
                new MultivaluedHashMap<>(),
                "GET",
                Optional.empty())).isInstanceOf(WebApplicationException.class);
    }

    @Test
    public void testBucketLs()
    {
        // values discovered from an AWS CLI request sent to a dummy local HTTP server
        MultivaluedMap<String, String> requestHeaders = new MultivaluedHashMap<>();
        requestHeaders.putSingle("X-Amz-Date", "20240516T034003Z");
        requestHeaders.putSingle("X-Amz-Content-SHA256", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        requestHeaders.putSingle("X-Amz-Security-Token", "FwoGZXIvYXdzEP3//////////wEaDG79rlcAjsgKPP9N3SKIAu7/Zvngne5Ov6kGrDcIIPUZYkGpwNbj8zNnbWgOhiqmOCM3hrk4NuH17mP5n3nC7urlXZxaTCywKpAHpO3YsvLXcwjlfaYFA0Au4oejwSbU9ybIlzPzrqz7lVesgCfJOV+rj5F5UAh19d7RpRpA6Vy4nxGBTTlCNIVbkW9fp2Esql2/vsdh77rAG+j+BQegtegDCKBfen4gHMdvEOF6hyc4ne43eLXjpvUKxBgpI9MjOHtNHrDbOOBFXDDyknoESgE9Hsm12nDuVQhwrI/hhA4YB/MSIpl4FTgVs2sQP3K+v65tmyvIlpL6O78S6spMM9Tv/F4JLtksTzb90w46uZk9sxKC/RBkRijisM6tBjIrr/0znxnW3i5ggGAX4H/Z3aWlxSdzNs2UGWtqig9Plp3Xa9gG+zCKcXmDAA==");
        requestHeaders.putSingle("Host", "localhost:10064");
        requestHeaders.putSingle("User-Agent", "aws-cli/2.15.16 Python/3.11.7 Darwin/22.6.0 source/x86_64 prompt/off command/s3.ls");
        requestHeaders.putSingle("Accept-Encoding", "identity");

        MultivaluedMap<String, String> queryParameters = new MultivaluedHashMap<>();
        queryParameters.putSingle("list-type", "2");
        queryParameters.putSingle("prefix", "foo/bar");
        queryParameters.putSingle("delimiter", "/");
        queryParameters.putSingle("encoding-type", "url");

        String signature = signingController.signRequest(
                new SigningMetadata(SigningServiceType.S3, CREDENTIALS, Optional.empty(), "us-east-1"),
                Credentials::emulated,
                URI.create("http://localhost:10064/mybucket"),
                requestHeaders,
                queryParameters,
                "GET",
                Optional.empty());

        assertThat(signature).isEqualTo("AWS4-HMAC-SHA256 Credential=THIS_IS_AN_ACCESS_KEY/20240516/us-east-1/s3/aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date;x-amz-security-token, Signature=222d7b7fcd4d5560c944e8fecd9424ee3915d131c3ad9e000d65db93e87946c4");
    }

    @Test
    public void testLegacy()
    {
        // values discovered from a Spark/Hive request sent to proxy LocalServer
        MultivaluedMap<String, String> requestHeaders = new MultivaluedHashMap<>();
        requestHeaders.putSingle("x-amz-content-sha256", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        requestHeaders.putSingle("X-Amz-Date", "20240609T072007Z");
        requestHeaders.putSingle("User-Agent", "Hadoop 3.3.4, aws-sdk-java/1.12.262 Linux/6.5.11-linuxkit OpenJDK_64-Bit_Server_VM/11.0.23+9 java/11.0.23 scala/2.12.18 vendor/Eclipse_Adoptium cfg/retry-mode/legacy");
        requestHeaders.putSingle("Host", "host.docker.internal:57579");
        requestHeaders.putSingle("amz-sdk-invocation-id", "fbb83f4e-a245-cc19-a318-001245fd3bc8");
        requestHeaders.putSingle("amz-sdk-request", "attempt=1;max=21");
        requestHeaders.putSingle("amz-sdk-retry", "0/0/500");
        requestHeaders.putSingle("Content-Type", "application/octet-stream");

        String signature = signingController.internalSignRequest(
                SigningControllerImpl.Mode.UNADJUSTED_HEADERS,
                true,
                false,
                new SigningMetadata(SigningServiceType.S3, CREDENTIALS, Optional.empty(), "us-east-1"),
                Credentials::emulated,
                URI.create("http://host.docker.internal:57579/api/v1/s3Proxy/s3/sparkbyexamples/csv/text01.txt"),
                requestHeaders,
                new MultivaluedHashMap<>(),
                "HEAD",
                Optional.empty());

        assertThat(signature).isEqualTo("AWS4-HMAC-SHA256 Credential=THIS_IS_AN_ACCESS_KEY/20240609/us-east-1/s3/aws4_request, SignedHeaders=amz-sdk-invocation-id;amz-sdk-request;amz-sdk-retry;content-type;host;user-agent;x-amz-content-sha256;x-amz-date, Signature=40d64a548e32b70a3b2a606b716ff8f16953f146b5365b6efd3a91125e304fd7");
    }
}