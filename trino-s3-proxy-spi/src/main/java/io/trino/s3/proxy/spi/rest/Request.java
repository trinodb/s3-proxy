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
package io.trino.s3.proxy.spi.rest;

import io.trino.s3.proxy.spi.collections.ImmutableMultiMap;
import io.trino.s3.proxy.spi.collections.MultiMap;
import io.trino.s3.proxy.spi.signing.RequestAuthorization;

import java.net.URI;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

public record Request(
        UUID requestId,
        RequestAuthorization requestAuthorization,
        String requestDate,
        URI requestUri,
        MultiMap requestHeaders,
        MultiMap requestQueryParameters,
        String httpVerb,
        RequestContent requestContent)
{
    public Request
    {
        requireNonNull(requestId, "requestId is null");
        requireNonNull(requestAuthorization, "requestAuthorization is null");
        requireNonNull(requestDate, "requestDate is null");
        requireNonNull(requestUri, "requestUri is null");
        requestHeaders = ImmutableMultiMap.copyOfCaseInsensitive(requestHeaders);
        requestQueryParameters = ImmutableMultiMap.copyOf(requestQueryParameters);
        requireNonNull(httpVerb, "httpVerb is null");
        requireNonNull(requestContent, "requestContent is null");
    }
}
