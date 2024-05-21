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

import com.google.inject.Inject;
import jakarta.ws.rs.core.MultivaluedMap;

import java.net.URI;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public class SigningController
{
    private final CredentialsController credentialsController;

    @Inject
    public SigningController(CredentialsController credentialsController)
    {
        this.credentialsController = requireNonNull(credentialsController, "credentialsController is null");
    }

    public String signRequest(
            URI requestURI,
            MultivaluedMap<String, String> requestHeaders,
            MultivaluedMap<String, String> queryParameters,
            String httpMethod,
            String encodedPath,
            String region,
            String accessKey)
    {
        // TODO
        Credentials credentials = credentialsController.credentials(accessKey).orElseThrow();

        return Signer.sign(
                "s3",
                requestURI,
                requestHeaders,
                queryParameters,
                httpMethod,
                encodedPath,
                region,
                accessKey,
                credentials.emulated().secretKey(),
                Optional.empty());
    }
}