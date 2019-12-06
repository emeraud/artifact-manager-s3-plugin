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

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.Bucket;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Failure;
import hudson.util.FormValidation;
import io.jenkins.plugins.artifact_manager_jclouds.JCloudsVirtualFile;
import io.jenkins.plugins.aws.global_configuration.AbstractAwsGlobalConfiguration;
import io.jenkins.plugins.aws.global_configuration.CredentialsAwsGlobalConfiguration;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.regex.Pattern;

/**
 * Store the MinioS3BlobStore configuration to save it on a separate file. This make that
 * the change of container does not affected to the Artifact functionality, you could change the container
 * and it would still work if both container contains the same data.
 *
 *
 *
 * NOTE: SEE https://github.com/minio/cookbook/blob/master/docs/aws-sdk-for-java-with-minio.md
 *
 *
 */
@Symbol("s3")
@Extension
public class MinioS3BlobStoreConfig extends AbstractAwsGlobalConfiguration {

    private static final String BUCKET_REGEXP = "^([a-z]|(\\d(?!\\d{0,2}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})))([a-z\\d]|(\\.(?!(\\.|-)))|(-(?!\\.))){1,61}[a-z\\d\\.]$";

    private static final Pattern bucketPattern = Pattern.compile(BUCKET_REGEXP);

    /**
     * Minio endpoint address
     */
    private String endpoint;

    /**
     * Name of the S3 Bucket.
     */
    private String container;
    /**
     * Prefix to use for files, use to be a folder.
     */
    private String prefix;

    /**
     * When a build is deleted, should the associated artifacts be deleted as well?
     */
    private boolean deleteArtifacts;

    /**
     * When a build is done, should the stashed objects be deleted?
     */
    private boolean deleteStashes;


    /**
     * class to test configuration against Amazon S3 Bucket.
     */
    private static class MinioS3BlobStoreTester extends MinioS3BlobStore {
        private static final long serialVersionUID = -3645770416235883487L;
        private transient MinioS3BlobStoreConfig config;

        MinioS3BlobStoreTester(String endpoint, String container, String prefix) {
            config = new MinioS3BlobStoreConfig();
            config.setEndpoint(endpoint);
            config.setContainer(container);
            config.setPrefix(prefix);
        }

        @Override
        public MinioS3BlobStoreConfig getConfiguration() {
            return config;
        }
    }

    public MinioS3BlobStoreConfig() {
        load();
    }

    public String getEndpoint() {
        return endpoint;
    }

    @DataBoundSetter
    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
        checkValue(doCheckEndpoint(endpoint));
        save();
    }

    public String getContainer() {
        return container;
    }

    @DataBoundSetter
    public void setContainer(String container) {
        this.container = container;
        checkValue(doCheckContainer(container));
        save();
    }

    public String getPrefix() {
        return prefix;
    }

    @DataBoundSetter
    public void setPrefix(String prefix){
        this.prefix = prefix;
        checkValue(doCheckPrefix(prefix));
        save();
    }

    public boolean getDeleteArtifacts() {
        return deleteArtifacts;
    }

    @DataBoundSetter
    public void setDeleteArtifacts(boolean deleteArtifacts) {
        this.deleteArtifacts = deleteArtifacts;
        save();
    }

    public boolean getDeleteStashes() {
        return deleteStashes;
    }

    @DataBoundSetter
    public void setDeleteStashes(boolean deleteStashes) {
        this.deleteStashes = deleteStashes;
        save();
    }

    private void checkValue(@NonNull FormValidation formValidation) {
        if (formValidation.kind == FormValidation.Kind.ERROR) {
            throw new Failure(formValidation.getMessage());
        }
    }

    @Nonnull
    @Override
    public String getDisplayName() {
        return "Minio S3 Bucket Access settings";
    }

    @Nonnull
    public static MinioS3BlobStoreConfig get() {
        return ExtensionList.lookupSingleton(MinioS3BlobStoreConfig.class);
    }

    public FormValidation doCheckEndpoint(@QueryParameter String endpoint) {
        try {
            URL u = new URL(endpoint); // this would check for the protocol
            u.toURI(); // does the extra checking required for validation of URI
        } catch (MalformedURLException e) {
            return FormValidation.error("The provided endpoint does not seem valid: malformed");
        } catch (URISyntaxException e) {
            return FormValidation.error("The provided endpoint does not seem valid: wrong syntax");
        }
        return FormValidation.ok();
    }

    public FormValidation doCheckContainer(@QueryParameter String container){
        FormValidation ret = FormValidation.ok();
        if (StringUtils.isBlank(container)){
            ret = FormValidation.warning("The container name cannot be empty");
        } else if (!bucketPattern.matcher(container).matches()){
            ret = FormValidation.error("The S3 Bucket name does not match S3 bucket rules");
        }
        return ret;
    }

    public FormValidation doCheckPrefix(@QueryParameter String prefix){
        FormValidation ret;
        if (StringUtils.isBlank(prefix)) {
            ret = FormValidation.ok("Artifacts will be stored in the root folder of the S3 Bucket.");
        } else if (prefix.endsWith("/")) {
            ret = FormValidation.ok();
        } else {
            ret = FormValidation.error("A prefix must end with a slash.");
        }
        return ret;
    }

    /**
     * create an S3 Bucket.
     * @param name name of the S3 Bucket.
     * @return return the Bucket created.
     * @throws IOException in case of error obtaining the credentials, in other kind of errors it will throw the
     * runtime exceptions are thrown by createBucket method.
     */
    public Bucket createS3Bucket(String name) throws IOException {
        final AmazonS3 client = createS3Client();
        return client.createBucket(name);
    }

    @RequirePOST
    public FormValidation doCreateS3Bucket(@QueryParameter String endpoint, @QueryParameter String container, @QueryParameter String prefix) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        FormValidation ret = FormValidation.ok("success");
        try {
            createS3Bucket(container);
        } catch (Throwable t){
            String msg = processExceptionMessage(t);
            ret = FormValidation.error(StringUtils.abbreviate(msg, 200));
        }
        return ret;
    }

    void checkGetBucketLocation(String container) throws IOException {
        final AmazonS3 client = createS3Client();
        client.getBucketLocation(container);
    }


    AmazonS3 createS3Client() throws IOException {
        ClientConfiguration clientConfiguration = new ClientConfiguration();
        clientConfiguration.setSignerOverride("AWSS3V4SignerType");

        AWSCredentials credentials = CredentialsAwsGlobalConfiguration.get().getCredentials().getCredentials();

        AmazonS3 s3Client = AmazonS3ClientBuilder
                .standard()
                .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(endpoint, Regions.US_EAST_1.name()))
                .withPathStyleAccessEnabled(true)
                .withClientConfiguration(clientConfiguration)
                .withCredentials(new AWSStaticCredentialsProvider(credentials))
                .build();

        return s3Client;
    }

    @RequirePOST
    public FormValidation doValidateS3BucketConfig(@QueryParameter String endpoint, @QueryParameter String container, @QueryParameter String prefix) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        FormValidation ret = FormValidation.ok("success");
        try {
            MinioS3BlobStore provider = new MinioS3BlobStoreTester(endpoint, container, prefix);
            JCloudsVirtualFile jc = new JCloudsVirtualFile(provider, container, prefix);
            jc.list();
        } catch (Throwable t){
            String msg = processExceptionMessage(t);
            ret = FormValidation.error(StringUtils.abbreviate(msg, 200));
        }
        try {
            checkGetBucketLocation(container);
        } catch (Throwable t){
            ret = FormValidation.warning(t, "GetBucketLocation failed");
        }
        return ret;
    }

}
