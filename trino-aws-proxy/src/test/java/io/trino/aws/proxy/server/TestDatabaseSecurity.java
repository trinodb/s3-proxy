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
package io.trino.aws.proxy.server;

import com.google.inject.Inject;
import com.google.inject.Scopes;
import io.trino.aws.proxy.server.testing.TestingTrinoAwsProxyServer;
import io.trino.aws.proxy.server.testing.containers.PySparkContainer;
import io.trino.aws.proxy.server.testing.harness.TrinoAwsProxyTest;
import io.trino.aws.proxy.server.testing.harness.TrinoAwsProxyTestCommonModules.WithAllContainers;
import io.trino.aws.proxy.spi.rest.ParsedS3Request;
import io.trino.aws.proxy.spi.security.S3DatabaseSecurityFacade;
import io.trino.aws.proxy.spi.security.S3DatabaseSecurityFacadeProvider;
import io.trino.aws.proxy.spi.security.SecurityResponse;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.trino.aws.proxy.server.TestPySparkSql.createDatabaseAndTable;
import static io.trino.aws.proxy.server.testing.containers.DockerAttachUtil.clearInputStreamAndClose;
import static io.trino.aws.proxy.server.testing.containers.DockerAttachUtil.inputToContainerStdin;
import static io.trino.aws.proxy.spi.TrinoAwsProxyBinder.trinoAwsProxyBinder;
import static java.util.Objects.requireNonNull;

@TrinoAwsProxyTest(filters = TestDatabaseSecurity.Filter.class)
public class TestDatabaseSecurity
{
    public static final String DATABASE_NAME = "db";
    public static final String TABLE_NAME = "people";

    public static class Filter
            extends WithAllContainers
    {
        @Override
        public TestingTrinoAwsProxyServer.Builder filter(TestingTrinoAwsProxyServer.Builder builder)
        {
            return super.filter(builder).addModule(binder -> {
                trinoAwsProxyBinder(binder).bindS3DatabaseSecurity(binding -> binding.to(FacadeProvider.class).in(Scopes.SINGLETON));
                binder.bind(FacadeProvider.class).in(Scopes.SINGLETON);
            });
        }
    }

    public static class FacadeProvider
            implements S3DatabaseSecurityFacadeProvider
    {
        final AtomicBoolean disallowGets = new AtomicBoolean();

        @Override
        public S3DatabaseSecurityFacade securityFacadeForRequest(ParsedS3Request request)
                throws WebApplicationException
        {
            return new S3DatabaseSecurityFacade()
            {
                @Override
                public Optional<String> tableName(Optional<String> lowercaseAction)
                {
                    return Optional.of(TABLE_NAME);
                }

                @Override
                public SecurityResponse tableOperation(String tableName, Optional<String> lowercaseAction)
                {
                    if (disallowGets.get() && request.httpVerb().equalsIgnoreCase("GET")) {
                        return new SecurityResponse(false, Optional.empty());
                    }
                    return S3DatabaseSecurityFacade.super.tableOperation(tableName, lowercaseAction);
                }
            };
        }
    }

    private final S3Client s3Client;
    private final PySparkContainer pySparkContainer;
    private final FacadeProvider facadeProvider;

    @Inject
    public TestDatabaseSecurity(S3Client s3Client, PySparkContainer pySparkContainer, FacadeProvider facadeProvider)
    {
        this.s3Client = requireNonNull(s3Client, "s3Client is null");
        this.pySparkContainer = requireNonNull(pySparkContainer, "pySparkContainer is null");
        this.facadeProvider = requireNonNull(facadeProvider, "facadeProvider is null");
    }

    @Test
    public void testDatabaseSecurity()
            throws Exception
    {
        createDatabaseAndTable(s3Client, pySparkContainer);

        clearInputStreamAndClose(inputToContainerStdin(pySparkContainer.containerId(), "spark.sql(\"select * from %s.%s\").show()".formatted(DATABASE_NAME, TABLE_NAME)), line -> line.equals("|    John Galt| 28|"));

        try {
            facadeProvider.disallowGets.set(true);
            clearInputStreamAndClose(inputToContainerStdin(pySparkContainer.containerId(), "spark.sql(\"select * from %s.%s\").show()".formatted(DATABASE_NAME, TABLE_NAME)), line -> line.contains("Error Code: 401 Unauthorized"));
        }
        finally {
            facadeProvider.disallowGets.set(false);
        }
    }
}
