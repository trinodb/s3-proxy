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
package io.trino.s3.proxy.server;

import com.google.inject.Key;
import io.airlift.http.server.testing.TestingHttpServer;
import io.airlift.log.Logger;
import io.trino.s3.proxy.server.rest.TrinoS3ProxyRestConstants;
import io.trino.s3.proxy.server.testing.TestingTrinoS3ProxyServer;
import io.trino.s3.proxy.server.testing.TestingUtil.ForTesting;
import io.trino.s3.proxy.spi.credentials.Credentials;

public final class LocalServer
{
    private static final Logger log = Logger.get(LocalServer.class);

    private LocalServer() {}

    @SuppressWarnings("resource")
    public static void main(String[] args)
    {
        TestingTrinoS3ProxyServer trinoS3ProxyServer = TestingTrinoS3ProxyServer.builder()
                .withMockS3Container()
                .buildAndStart();

        log.info("======== TESTING SERVER STARTED ========");

        TestingHttpServer httpServer = trinoS3ProxyServer.getInjector().getInstance(TestingHttpServer.class);
        Credentials testingCredentials = trinoS3ProxyServer.getInjector().getInstance(Key.get(Credentials.class, ForTesting.class));

        log.info("");
        log.info("Endpoint:   %s", httpServer.getBaseUrl().resolve(TrinoS3ProxyRestConstants.S3_PATH));
        log.info("Access Key: %s", testingCredentials.emulated().accessKey());
        log.info("Secret Key: %s", testingCredentials.emulated().secretKey());
        log.info("");
    }
}
