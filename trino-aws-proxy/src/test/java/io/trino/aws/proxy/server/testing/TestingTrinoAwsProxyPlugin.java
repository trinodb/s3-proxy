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
package io.trino.aws.proxy.server.testing;

import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import io.trino.aws.proxy.server.testing.TestingTrinoAwsProxyServerModule.ForTestingRemoteCredentials;
import io.trino.aws.proxy.server.testing.containers.S3Container;
import io.trino.aws.proxy.spi.TrinoAwsProxyServerPlugin;
import io.trino.aws.proxy.spi.credentials.Credential;
import io.trino.aws.proxy.spi.credentials.Credentials;
import io.trino.aws.proxy.spi.remote.RemoteSessionRole;

import java.util.Optional;
import java.util.UUID;

public class TestingTrinoAwsProxyPlugin
        extends AbstractModule
        implements TrinoAwsProxyServerPlugin
{
    @Override
    public Module module()
    {
        return this;
    }

    @ForTestingRemoteCredentials
    @Provides
    @Singleton
    public Credentials provideRemoteCredentials(S3Container s3MockContainer, TestingCredentialsRolesProvider credentialsController)
    {
        Credential policyUserCredential = s3MockContainer.policyUserCredential();

        RemoteSessionRole remoteSessionRole = new RemoteSessionRole("us-east-1", "minio-doesnt-care", Optional.empty());
        Credentials remoteCredentials = Credentials.build(new Credential(UUID.randomUUID().toString(), UUID.randomUUID().toString()), policyUserCredential, remoteSessionRole);
        credentialsController.addCredentials(remoteCredentials);

        return remoteCredentials;
    }
}
