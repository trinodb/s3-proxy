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
package io.trino.s3.proxy.server.credentials;

import com.google.common.base.Splitter;
import com.google.inject.Inject;
import jakarta.ws.rs.core.MultivaluedMap;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

public class SigningController
{
    private final CredentialsController credentialsController;
    private final Duration maxClockDrift;

    @Inject
    public SigningController(CredentialsController credentialsController, SigningControllerConfig signingControllerConfig)
    {
        this.credentialsController = requireNonNull(credentialsController, "credentialsController is null");
        maxClockDrift = signingControllerConfig.getMaxClockDrift().toJavaTime();
    }

    public Optional<SigningMetadata> signingMetadataFromRequest(
            SigningService signingService,
            Function<Credentials, Credentials.Credential> credentialsSupplier,
            URI requestURI,
            MultivaluedMap<String, String> requestHeaders,
            MultivaluedMap<String, String> queryParameters,
            String httpMethod,
            String encodedPath,
            Optional<byte[]> entity)
    {
        String authorization = requestHeaders.getFirst("Authorization");
        if (authorization == null) {
            return Optional.empty();
        }

        List<String> authorizationParts = Splitter.on(",").trimResults().splitToList(authorization);
        if (authorizationParts.isEmpty()) {
            return Optional.empty();
        }

        String credential = authorizationParts.getFirst();
        List<String> credentialParts = Splitter.on("=").splitToList(credential);
        if (credentialParts.size() < 2) {
            return Optional.empty();
        }

        String credentialValue = credentialParts.get(1);
        List<String> credentialValueParts = Splitter.on("/").splitToList(credentialValue);
        if (credentialValueParts.size() < 3) {
            return Optional.empty();
        }

        String emulatedAccessKey = credentialValueParts.getFirst();
        String region = credentialValueParts.get(2);

        Optional<String> session = Optional.ofNullable(requestHeaders.getFirst("x-amz-security-token"));

        return credentialsController.credentials(emulatedAccessKey, session)
                .map(credentials -> new SigningMetadata(credentials, session, region))
                .filter(metadata -> isValidAuthorization(signingService, metadata, credentialsSupplier, authorization, requestURI, requestHeaders, queryParameters, httpMethod, encodedPath, entity));
    }

    public String signRequest(
            SigningService signingService,
            Function<Credentials, Credentials.Credential> credentialsSupplier, SigningMetadata metadata,
            URI requestURI,
            MultivaluedMap<String, String> requestHeaders,
            MultivaluedMap<String, String> queryParameters,
            String httpMethod,
            String encodedPath,
            Optional<byte[]> entity)
    {
        Credentials.Credential credential = credentialsSupplier.apply(metadata.credentials());

        return Signer.sign(
                signingService.asServiceName(),
                requestURI,
                requestHeaders,
                queryParameters,
                httpMethod,
                encodedPath,
                metadata.region(),
                credential.accessKey(),
                credential.secretKey(),
                maxClockDrift,
                entity);
    }

    private boolean isValidAuthorization(
            SigningService signingService,
            SigningMetadata metadata,
            Function<Credentials, Credentials.Credential> credentialsSupplier,
            String authorizationHeader,
            URI requestURI,
            MultivaluedMap<String, String> requestHeaders,
            MultivaluedMap<String, String> queryParameters,
            String httpMethod,
            String encodedPath,
            Optional<byte[]> entity)
    {
        String expectedAuthorization = signRequest(signingService, credentialsSupplier, metadata, requestURI, requestHeaders, queryParameters, httpMethod, encodedPath, entity);
        return authorizationHeader.equals(expectedAuthorization);
    }
}
