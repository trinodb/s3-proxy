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
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.glassfish.jersey.server.ContainerRequest;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.trino.s3.proxy.server.credentials.Signer.AMZ_DATE_FORMAT;
import static io.trino.s3.proxy.server.credentials.Signer.RESPONSE_DATE_FORMAT;
import static io.trino.s3.proxy.server.credentials.Signer.ZONE;
import static io.trino.s3.proxy.server.credentials.SigningController.Mode.UNADJUSTED_HEADERS;
import static java.util.Objects.requireNonNull;

public class SigningController
{
    private final Duration maxClockDrift;
    private final CredentialsController credentialsController;

    @Inject
    public SigningController(CredentialsController credentialsController, SigningControllerConfig signingControllerConfig)
    {
        this.credentialsController = requireNonNull(credentialsController, "credentialsController is null");
        maxClockDrift = signingControllerConfig.getMaxClockDrift().toJavaTime();
    }

    public static String formatRequestInstant(Instant instant)
    {
        return instant.atZone(ZONE).format(AMZ_DATE_FORMAT);
    }

    public static String formatResponseInstant(Instant instant)
    {
        return instant.atZone(ZONE).format(RESPONSE_DATE_FORMAT);
    }

    public enum Mode
    {
        ADJUSTED_HEADERS,
        UNADJUSTED_HEADERS,
    }

    public String signRequest(
            SigningMetadata metadata,
            Function<Credentials, Credential> credentialsSupplier,
            URI requestURI,
            MultivaluedMap<String, String> requestHeaders,
            MultivaluedMap<String, String> queryParameters,
            String httpMethod,
            Optional<byte[]> entity)
    {
        return internalSignRequest(
                UNADJUSTED_HEADERS,
                metadata,
                credentialsSupplier,
                requestURI,
                requestHeaders,
                queryParameters,
                httpMethod,
                entity);
    }

    public SigningMetadata validateAndParseAuthorization(ContainerRequest request, SigningServiceType signingServiceType, Optional<byte[]> entity)
    {
        return signingMetadataFromRequest(
                signingServiceType,
                Credentials::emulated,
                request.getRequestUri(),
                request.getRequestHeaders(),
                request.getUriInfo().getQueryParameters(),
                request.getMethod(),
                entity)
                .orElseThrow(() -> new WebApplicationException(Response.Status.UNAUTHORIZED));
    }

    private String internalSignRequest(
            Mode mode,
            SigningMetadata metadata,
            Function<Credentials, Credential> credentialsSupplier,
            URI requestURI,
            MultivaluedMap<String, String> requestHeaders,
            MultivaluedMap<String, String> queryParameters,
            String httpMethod,
            Optional<byte[]> entity)
    {
        Credential credential = credentialsSupplier.apply(metadata.credentials());

        return Signer.sign(
                mode,
                metadata.signingServiceType().asServiceName(),
                requestURI,
                requestHeaders,
                queryParameters,
                httpMethod,
                metadata.region(),
                credential.accessKey(),
                credential.secretKey(),
                maxClockDrift,
                entity);
    }

    private Optional<SigningMetadata> signingMetadataFromRequest(
            SigningServiceType signingServiceType,
            Function<Credentials, Credential> credentialsSupplier,
            URI requestURI,
            MultivaluedMap<String, String> requestHeaders,
            MultivaluedMap<String, String> queryParameters,
            String httpMethod,
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

        return credentialsController.withCredentials(emulatedAccessKey, session, credentials -> {
            SigningMetadata metadata = new SigningMetadata(signingServiceType, credentials, session, region);
            if (isValidAuthorization(metadata, credentialsSupplier, authorization, requestURI, requestHeaders, queryParameters, httpMethod, entity)) {
                return Optional.of(metadata);
            }
            return Optional.empty();
        });
    }

    private boolean isValidAuthorization(
            SigningMetadata metadata,
            Function<Credentials, Credential> credentialsSupplier,
            String authorizationHeader,
            URI requestURI,
            MultivaluedMap<String, String> requestHeaders,
            MultivaluedMap<String, String> queryParameters,
            String httpMethod,
            Optional<byte[]> entity)
    {
        // temp workaround until https://github.com/airlift/airlift/pull/1178 is accepted
        return Stream.of(Mode.values()).anyMatch(mode -> {
            String expectedAuthorization = internalSignRequest(mode, metadata, credentialsSupplier, requestURI, requestHeaders, queryParameters, httpMethod, entity);
            return authorizationHeader.equals(expectedAuthorization);
        });
    }
}
