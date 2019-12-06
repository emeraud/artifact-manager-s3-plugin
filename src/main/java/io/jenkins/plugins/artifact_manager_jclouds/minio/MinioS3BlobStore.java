/*
 * The MIT License
 *
 * Copyright 2018 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.jenkins.plugins.artifact_manager_jclouds.minio;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.google.common.base.Supplier;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import io.jenkins.plugins.artifact_manager_jclouds.BlobStoreProvider;
import io.jenkins.plugins.artifact_manager_jclouds.BlobStoreProviderDescriptor;
import io.jenkins.plugins.aws.global_configuration.CredentialsAwsGlobalConfiguration;
import org.apache.commons.lang.StringUtils;
import org.jclouds.ContextBuilder;
import org.jclouds.aws.s3.AWSS3ProviderMetadata;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.domain.Credentials;
import org.jclouds.osgi.ProviderRegistry;
import org.jclouds.s3.reference.S3Constants;
import org.jenkinsci.Symbol;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Date;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Extension that customizes JCloudsBlobStore for AWS S3. Credentials are fetched from the environment, env vars, aws
 * profiles,...
 */
@Restricted(NoExternalUse.class)
public class MinioS3BlobStore extends BlobStoreProvider {

    private static final Logger LOGGER = Logger.getLogger(MinioS3BlobStore.class.getName());

    private static final long serialVersionUID = -8864075675579867370L;

    @DataBoundConstructor
    public MinioS3BlobStore() {
    }

    @Override
    public String getPrefix() {
        return getConfiguration().getPrefix();
    }

    @Override
    public String getContainer() {
        return getConfiguration().getContainer();
    }

    public String getEndpoint() {
        return getConfiguration().getEndpoint();
    }

    public MinioS3BlobStoreConfig getConfiguration(){
        return MinioS3BlobStoreConfig.get();
    }

    @Override
    public boolean isDeleteArtifacts() {
        return getConfiguration().getDeleteArtifacts();
    }

    @Override
    public boolean isDeleteStashes() {
        return getConfiguration().getDeleteStashes();
    }

    @Override
    public BlobStoreContext getContext() throws IOException {
        LOGGER.log(Level.FINEST, "Building context");
        ProviderRegistry.registerProvider(AWSS3ProviderMetadata.builder().build());
        try {
            Properties props = new Properties();

            // By default, jCloud does not encode buckets as PATH but as subdomain which is not compatible with minio
            props.setProperty(S3Constants.PROPERTY_S3_VIRTUAL_HOST_BUCKETS, "false");

            return ContextBuilder.newBuilder("aws-s3")
                    .credentialsSupplier(getCredentialsSupplier())
                    .endpoint(getEndpoint())
                    .overrides(props)
                    .buildView(BlobStoreContext.class);
        } catch (NoSuchElementException x) {
            throw new IOException(x);
        }
    }


    /**
     *
     * @return the proper credential supplier using the configuration settings.
     * @throws IOException in case of error.
     */
    private Supplier<Credentials> getCredentialsSupplier() throws IOException {
        final AWSCredentials credentials = CredentialsAwsGlobalConfiguration.get().getCredentials().getCredentials();
        return () -> new Credentials(credentials.getAWSAccessKeyId(), credentials.getAWSSecretKey());
    }

    @Nonnull
    @Override
    public URI toURI(@NonNull String container, @NonNull String key) {
        assert container != null;
        assert key != null;
        try {
            // TODO proper encoding
            final URI uri = new URI(String.format("%s/minio/%s/%s",
                    getConfiguration().getEndpoint(),
                    container,
                    URLEncoder.encode(key, "UTF-8").replaceAll("%2F", "/").replaceAll("%3A", ":")));
            return uri;
        } catch (URISyntaxException | UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/dev/ShareObjectPreSignedURLJavaSDK.html">Generate a
     *      Pre-signed Object URL using AWS SDK for Java</a>
     */
    @Override
    public URL toExternalURL(@NonNull Blob blob, @NonNull HttpMethod httpMethod) throws IOException {
        assert blob != null;
        assert httpMethod != null;

        Date expiration = new Date(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1));
        String container = blob.getMetadata().getContainer();
        String name = blob.getMetadata().getName();
        LOGGER.log(Level.FINE, "Generating presigned URL for {0} / {1} for method {2}",
                new Object[] { container, name, httpMethod });
        com.amazonaws.HttpMethod awsMethod;
        switch (httpMethod) {
        case PUT:
            awsMethod = com.amazonaws.HttpMethod.PUT;
            break;
        case GET:
            awsMethod = com.amazonaws.HttpMethod.GET;
            break;
        default:
            throw new IOException("HTTP Method " + httpMethod + " not supported for S3");
        }

        final AmazonS3 client = getConfiguration().createS3Client();
        final URL url = client.generatePresignedUrl(container, name, expiration, awsMethod);
        return url;
    }

    @Symbol("s3")
    @Extension
    public static final class DescriptorImpl extends BlobStoreProviderDescriptor {

        @Override
        public String getDisplayName() {
            return "Minio S3";
        }

        /**
         *
         * @return true if a container is configured.
         */
        public boolean isConfigured(){
            return StringUtils.isNotBlank(MinioS3BlobStoreConfig.get().getContainer());
        }

    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("MinioS3BlobStore{");
        sb.append("container='").append(getContainer()).append('\'');
        sb.append(", prefix='").append(getPrefix()).append('\'');
        sb.append(", endpoint='").append(getEndpoint()).append('\'');
        sb.append(", deleteArtifacts='").append(isDeleteArtifacts()).append('\'');
        sb.append(", deleteStashes='").append(isDeleteStashes()).append('\'');
        sb.append('}');
        return sb.toString();
    }

}
