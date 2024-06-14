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
package io.trino.s3.proxy.server.testing;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Closer;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.TypeLiteral;
import io.airlift.bootstrap.Bootstrap;
import io.airlift.bootstrap.LifeCycleManager;
import io.airlift.event.client.EventModule;
import io.airlift.http.server.testing.TestingHttpServerModule;
import io.airlift.jaxrs.JaxrsModule;
import io.airlift.json.JsonModule;
import io.airlift.log.Logger;
import io.airlift.node.testing.TestingNodeModule;
import io.trino.s3.proxy.server.remote.RemoteS3Facade;
import io.trino.s3.proxy.server.testing.TestingUtil.ForTesting;
import io.trino.s3.proxy.server.testing.containers.MetastoreContainer;
import io.trino.s3.proxy.server.testing.containers.PostgresContainer;
import io.trino.s3.proxy.server.testing.containers.PySparkContainer;
import io.trino.s3.proxy.server.testing.containers.S3Container;
import io.trino.s3.proxy.spi.credentials.Credentials;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static com.google.inject.multibindings.OptionalBinder.newOptionalBinder;
import static io.trino.s3.proxy.server.testing.TestingUtil.TESTING_CREDENTIALS;
import static java.util.Objects.requireNonNull;

public final class TestingTrinoS3ProxyServer
        implements Closeable
{
    private static final Logger log = Logger.get(TestingTrinoS3ProxyServer.class);

    private final Injector injector;
    private final Closer closer;

    private TestingTrinoS3ProxyServer(Injector injector, Closer closer)
    {
        this.injector = requireNonNull(injector, "injector is null");
        this.closer = requireNonNull(closer, "closer is null");
    }

    @Override
    public void close()
    {
        try {
            injector.getInstance(LifeCycleManager.class).stop();
        }
        finally {
            try {
                closer.close();
            }
            catch (IOException e) {
                log.error(e, "closer.close()");
            }
        }
    }

    public Injector getInjector()
    {
        return injector;
    }

    public TestingCredentialsRolesProvider getCredentialsController()
    {
        return injector.getInstance(TestingCredentialsRolesProvider.class);
    }

    public static Builder builder()
    {
        return new Builder();
    }

    public static class Builder
    {
        private final Closer closer = Closer.create();
        private final ImmutableSet.Builder<Module> modules = ImmutableSet.builder();
        private final ImmutableMap.Builder<String, String> properties = ImmutableMap.builder();
        private PostgresContainer bespokePostgresContainer;
        private boolean addS3Container;
        private boolean addPostgresContainer;
        private boolean addMetastoreContainer;
        private boolean addPySparkContainer;

        public Builder addModule(Module module)
        {
            modules.add(module);
            return this;
        }

        public Builder registerCloseable(Closeable closeable)
        {
            closer.register(closeable);
            return this;
        }

        public Builder withS3Container()
        {
            addS3Container = true;
            return this;
        }

        public Builder withPostgresContainer(PostgresContainer container)
        {
            addPostgresContainer = true;
            bespokePostgresContainer = container;
            return this;
        }

        public Builder withPostgresContainer()
        {
            addPostgresContainer = true;
            return this;
        }

        public Builder withMetastoreContainer()
        {
            // metastore requires postgres and S3
            withPostgresContainer().withS3Container();
            addMetastoreContainer = true;
            return this;
        }

        public Builder withPySparkContainer()
        {
            // pyspark requires metastore and S3
            withMetastoreContainer().withS3Container();
            addPySparkContainer = true;
            return this;
        }

        public Builder withServerHostName(String serverHostName)
        {
            properties.put("s3proxy.hostname", serverHostName);
            return this;
        }

        public Builder withProperty(String key, String value)
        {
            properties.put(key, value);
            return this;
        }

        public Builder withProperties(Map<String, String> properties)
        {
            this.properties.putAll(properties);
            return this;
        }

        public TestingTrinoS3ProxyServer buildAndStart()
        {
            addContainers();

            return start(modules.build(), properties.buildKeepingLast(), closer);
        }

        private void addContainers()
        {
            if (addS3Container) {
                modules.add(binder -> {
                    binder.bind(TestingCredentialsInitializer.class).asEagerSingleton();

                    binder.bind(S3Container.class).asEagerSingleton();
                    binder.bind(Credentials.class).annotatedWith(ForTesting.class).toInstance(TESTING_CREDENTIALS);
                    newOptionalBinder(binder, Key.get(new TypeLiteral<List<String>>() {}, S3Container.ForS3Container.class)).setDefault().toInstance(ImmutableList.of());

                    newOptionalBinder(binder, Key.get(RemoteS3Facade.class, ForTesting.class))
                            .setDefault()
                            .to(ContainerS3Facade.PathStyleContainerS3Facade.class)
                            .asEagerSingleton();
                });
            }

            if (addPostgresContainer) {
                if (bespokePostgresContainer != null) {
                    modules.add(binder -> binder.bind(PostgresContainer.class).toInstance(bespokePostgresContainer));
                    registerCloseable(bespokePostgresContainer);
                }
                else {
                    modules.add(binder -> binder.bind(PostgresContainer.class).asEagerSingleton());
                }
            }

            if (addMetastoreContainer) {
                modules.add(binder -> binder.bind(MetastoreContainer.class).asEagerSingleton());
            }

            if (addPySparkContainer) {
                modules.add(binder -> binder.bind(PySparkContainer.class).asEagerSingleton());
            }
        }
    }

    static class TestingCredentialsInitializer
    {
        @Inject
        TestingCredentialsInitializer(TestingCredentialsRolesProvider credentialsController)
        {
            credentialsController.addCredentials(TESTING_CREDENTIALS);
        }
    }

    private static TestingTrinoS3ProxyServer start(Collection<Module> extraModules, Map<String, String> properties, Closer closer)
    {
        ImmutableList.Builder<Module> modules = ImmutableList.<Module>builder()
                .add(new TestingTrinoS3ProxyServerModule())
                .add(new TestingNodeModule())
                .add(new EventModule())
                .add(new TestingHttpServerModule())
                .add(new JsonModule())
                .add(new JaxrsModule());

        extraModules.forEach(modules::add);

        Bootstrap app = new Bootstrap(modules.build());
        Injector injector = app.setOptionalConfigurationProperties(properties).initialize();
        return new TestingTrinoS3ProxyServer(injector, closer);
    }
}
