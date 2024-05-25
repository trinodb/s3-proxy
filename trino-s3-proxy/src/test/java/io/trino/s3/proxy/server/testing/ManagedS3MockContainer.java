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

import com.google.common.base.Splitter;
import com.google.inject.BindingAnnotation;
import com.google.inject.Inject;
import io.trino.s3.proxy.server.credentials.Credentials.Credential;
import jakarta.annotation.PreDestroy;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.images.builder.Transferable;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

public class ManagedS3MockContainer
{
    private final MinIOContainer container;

    private static final String CONFIG = """
            {
                "version": "10",
                "aliases": {
                    "local": {
                        "url": "http://localhost:9000",
                        "accessKey": "%s",
                        "secretKey": "%s",
                        "api": "S3v4",
                        "path": "auto"
                    }
                }
            }
            """;

    @Retention(RUNTIME)
    @Target({FIELD, PARAMETER, METHOD})
    @BindingAnnotation
    public @interface ForS3MockContainer {}

    @Inject
    public ManagedS3MockContainer(@ForS3MockContainer String initialBuckets, @ForS3MockContainer Credential credential)
    {
        Transferable transferable = Transferable.of(CONFIG.formatted(credential.accessKey(), credential.secretKey()));
        container = new MinIOContainer("minio/minio:RELEASE.2023-09-04T19-57-37Z")
                .withUserName(credential.accessKey())
                .withPassword(credential.secretKey())
                // setting this allows us to shell into the container and run "mc" commands
                .withCopyToContainer(transferable, "/root/.mc/config.json");

        container.configure();
        container.start();

        if (!initialBuckets.isEmpty()) {
            Splitter.on(',').trimResults().splitToStream(initialBuckets).forEach(bucket -> {
                ExecResult execResult;
                try {
                    execResult = container.execInContainer("mc", "mb", "local/" + bucket);
                }
                catch (Exception e) {
                    throw new RuntimeException("Could not create bucket: " + bucket, e);
                }
                if (execResult.getExitCode() != 0) {
                    throw new RuntimeException("Could not create bucket: " + bucket + " error: " + execResult.getStderr());
                }
            });
        }
    }

    public GenericContainer<?> container()
    {
        return container;
    }

    @PreDestroy
    public void shutdown()
    {
        container.stop();
    }
}
