/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.http.apache5;

import static software.amazon.awssdk.utils.ProxyConfigProvider.fromSystemEnvironmentSettings;
import static software.amazon.awssdk.utils.StringUtils.isEmpty;

import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import software.amazon.awssdk.annotations.SdkPreviewApi;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.utils.ProxyConfigProvider;
import software.amazon.awssdk.utils.ProxySystemSetting;
import software.amazon.awssdk.utils.ToString;
import software.amazon.awssdk.utils.Validate;
import software.amazon.awssdk.utils.builder.CopyableBuilder;
import software.amazon.awssdk.utils.builder.ToCopyableBuilder;

/**
 * Configuration that defines how to communicate via an HTTP or HTTPS proxy.
 */
@SdkPreviewApi
@SdkPublicApi
public final class ProxyConfiguration implements ToCopyableBuilder<ProxyConfiguration.Builder, ProxyConfiguration> {
    private final URI endpoint;
    private final String username;
    private final String password;
    private final String ntlmDomain;
    private final String ntlmWorkstation;
    private final Set<String> nonProxyHosts;
    private final Boolean preemptiveBasicAuthenticationEnabled;
    private final Boolean useSystemPropertyValues;
    private final String host;
    private final int port;
    private final String scheme;
    private final Boolean useEnvironmentVariablesValues;

    /**
     * Initialize this configuration. Private to require use of {@link #builder()}.
     */
    private ProxyConfiguration(DefaultClientProxyConfigurationBuilder builder) {
        this.endpoint = builder.endpoint;
        String resolvedScheme = getResolvedScheme(builder);
        this.scheme = resolvedScheme;
        ProxyConfigProvider proxyConfiguration = fromSystemEnvironmentSettings(builder.useSystemPropertyValues,
                                                                               builder.useEnvironmentVariableValues,
                                                                               resolvedScheme);
        this.username = resolveUsername(builder, proxyConfiguration);
        this.password = resolvePassword(builder, proxyConfiguration);
        this.ntlmDomain = builder.ntlmDomain;
        this.ntlmWorkstation = builder.ntlmWorkstation;
        this.nonProxyHosts = resolveNonProxyHosts(builder, proxyConfiguration);
        this.preemptiveBasicAuthenticationEnabled = builder.preemptiveBasicAuthenticationEnabled == null ? Boolean.FALSE :
                                                    builder.preemptiveBasicAuthenticationEnabled;
        this.useSystemPropertyValues = builder.useSystemPropertyValues;
        this.useEnvironmentVariablesValues = builder.useEnvironmentVariableValues;

        if (this.endpoint != null) {
            this.host = endpoint.getHost();
            this.port = endpoint.getPort();
        } else {
            this.host = proxyConfiguration != null ? proxyConfiguration.host() : null;
            this.port = proxyConfiguration != null ? proxyConfiguration.port() : 0;
        }
    }

    private static String resolvePassword(DefaultClientProxyConfigurationBuilder builder,
                                          ProxyConfigProvider proxyConfiguration) {
        return !isEmpty(builder.password) || proxyConfiguration == null ? builder.password :
               proxyConfiguration.password().orElseGet(() -> builder.password);
    }

    private static String resolveUsername(DefaultClientProxyConfigurationBuilder builder,
                                          ProxyConfigProvider proxyConfiguration) {
        return !isEmpty(builder.username) || proxyConfiguration == null ? builder.username :
               proxyConfiguration.userName().orElseGet(() -> builder.username);
    }


    private static Set<String> resolveNonProxyHosts(DefaultClientProxyConfigurationBuilder builder,
                                                    ProxyConfigProvider proxyConfiguration) {
        if (builder.nonProxyHosts != null || proxyConfiguration == null) {
            return builder.nonProxyHosts;
        }
        return proxyConfiguration.nonProxyHosts();
    }

    private String getResolvedScheme(DefaultClientProxyConfigurationBuilder builder) {
        return endpoint != null ? endpoint.getScheme() : builder.scheme;
    }

    /**
     * Returns the proxy host name from the configured endpoint if set, else from the "https.proxyHost" or "http.proxyHost" system
     * property, based on the scheme used, if {@link Builder#useSystemPropertyValues(Boolean)} is set to true.
     */
    public String host() {
        return host;
    }

    /**
     * Returns the proxy port from the configured endpoint if set, else from the "https.proxyPort" or "http.proxyPort" system
     * property, based on the scheme used, if {@link Builder#useSystemPropertyValues(Boolean)} is set to true.
     * If no value is found in none of the above options, the default value of 0 is returned.
     */
    public int port() {
        return port;
    }

    /**
     * Returns the {@link URI#scheme} from the configured endpoint. Otherwise return null.
     */
    public String scheme() {
        return scheme;
    }

    /**
     * The username to use when connecting through a proxy.
     *
     * @see Builder#password(String)
     */
    public String username() {
        return username;
    }

    /**
     * The password to use when connecting through a proxy.
     *
     * @see Builder#password(String)
     */
    public String password() {

        return password;
    }

    /**
     * For NTLM proxies: The Windows domain name to use when authenticating with the proxy.
     *
     * @see Builder#ntlmDomain(String)
     */
    public String ntlmDomain() {
        return ntlmDomain;
    }

    /**
     * For NTLM proxies: The Windows workstation name to use when authenticating with the proxy.
     *
     * @see Builder#ntlmWorkstation(String)
     */
    public String ntlmWorkstation() {
        return ntlmWorkstation;
    }

    /**
     * The hosts that the client is allowed to access without going through the proxy.
     * If the value is not set on the object, the value represent by "http.nonProxyHosts" system property is returned.
     * If system property is also not set, an unmodifiable empty set is returned.
     *
     * @see Builder#nonProxyHosts(Set)
     */
    public Set<String> nonProxyHosts() {
        return Collections.unmodifiableSet(nonProxyHosts != null ? nonProxyHosts : Collections.emptySet());
    }

    /**
     * Whether to attempt to authenticate preemptively against the proxy server using basic authentication.
     *
     * @see Builder#preemptiveBasicAuthenticationEnabled(Boolean)
     */
    public Boolean preemptiveBasicAuthenticationEnabled() {
        return preemptiveBasicAuthenticationEnabled;
    }

    @Override
    public Builder toBuilder() {
        return builder()
                .endpoint(endpoint)
                .username(username)
                .password(password)
                .ntlmDomain(ntlmDomain)
                .ntlmWorkstation(ntlmWorkstation)
                .nonProxyHosts(nonProxyHosts)
                .preemptiveBasicAuthenticationEnabled(preemptiveBasicAuthenticationEnabled)
                .useSystemPropertyValues(useSystemPropertyValues)
                .scheme(scheme)
                .useEnvironmentVariableValues(useEnvironmentVariablesValues);
    }

    /**
     * Create a {@link Builder}, used to create a {@link ProxyConfiguration}.
     */
    public static Builder builder() {
        return new DefaultClientProxyConfigurationBuilder();
    }

    @Override
    public String toString() {
        return ToString.builder("ProxyConfiguration")
                       .add("endpoint", endpoint)
                       .add("username", username)
                       .add("ntlmDomain", ntlmDomain)
                       .add("ntlmWorkstation", ntlmWorkstation)
                       .add("nonProxyHosts", nonProxyHosts)
                       .add("preemptiveBasicAuthenticationEnabled", preemptiveBasicAuthenticationEnabled)
                       .add("useSystemPropertyValues", useSystemPropertyValues)
                       .add("useEnvironmentVariablesValues", useEnvironmentVariablesValues)
                       .add("scheme", scheme)
                       .build();
    }

    public String resolveScheme() {
        return endpoint != null ? endpoint.getScheme() : scheme;
    }

    /**
     * A builder for {@link ProxyConfiguration}.
     *
     * <p>All implementations of this interface are mutable and not thread safe.</p>
     */
    public interface Builder extends CopyableBuilder<Builder, ProxyConfiguration> {

        /**
         * Configure the endpoint of the proxy server that the SDK should connect through. Currently, the endpoint is limited to
         * a host and port. Any other URI components will result in an exception being raised.
         */
        Builder endpoint(URI endpoint);

        /**
         * Configure the username to use when connecting through a proxy.
         */
        Builder username(String username);

        /**
         * Configure the password to use when connecting through a proxy.
         */
        Builder password(String password);

        /**
         * For NTLM proxies: Configure the Windows domain name to use when authenticating with the proxy.
         */
        Builder ntlmDomain(String proxyDomain);

        /**
         * For NTLM proxies: Configure the Windows workstation name to use when authenticating with the proxy.
         */
        Builder ntlmWorkstation(String proxyWorkstation);

        /**
         * Configure the hosts that the client is allowed to access without going through the proxy.
         */
        Builder nonProxyHosts(Set<String> nonProxyHosts);

        /**
         * Add a host that the client is allowed to access without going through the proxy.
         *
         * @see ProxyConfiguration#nonProxyHosts()
         */
        Builder addNonProxyHost(String nonProxyHost);

        /**
         * Configure whether to attempt to authenticate pre-emptively against the proxy server using basic authentication.
         */
        Builder preemptiveBasicAuthenticationEnabled(Boolean preemptiveBasicAuthenticationEnabled);

        /**
         * Option whether to use system property values from {@link ProxySystemSetting} if any of the config options are missing.
         * <p>
         * This value is set to "true" by default which means SDK will automatically use system property values for options that
         * are not provided during building the {@link ProxyConfiguration} object. To disable this behavior, set this value to
         * "false".It is important to note that when this property is set to "true," all proxy settings will exclusively originate
         * from system properties, and no partial settings will be obtained from EnvironmentVariableValues.
         */
        Builder useSystemPropertyValues(Boolean useSystemPropertyValues);

        /**
         * Option whether to use environment variable values for proxy configuration if any of the config options are missing.
         * <p>
         * This value is set to "true" by default, which means the SDK will automatically use environment variable values for
         * proxy configuration options that are not provided during the building of the {@link ProxyConfiguration} object. To
         * disable this behavior, set this value to "false". It is important to note that when this property is set to "true," all
         * proxy settings will exclusively originate from environment variableValues, and no partial settings will be obtained
         * from SystemPropertyValues.
         * <p>Comma-separated host names in the NO_PROXY environment variable indicate multiple hosts to exclude from
         * proxy settings.
         *
         * @param useEnvironmentVariableValues The option whether to use environment variable values.
         * @return This object for method chaining.
         */
        Builder useEnvironmentVariableValues(Boolean useEnvironmentVariableValues);

        /**
         * The HTTP scheme to use for connecting to the proxy. Valid values are {@code http} and {@code https}.
         * <p>
         * The client defaults to {@code http} if none is given.
         *
         * @param scheme The proxy scheme.
         * @return This object for method chaining.
         */
        Builder scheme(String scheme);

    }

    /**
     * An SDK-internal implementation of {@link Builder}.
     */
    private static final class DefaultClientProxyConfigurationBuilder implements Builder {

        private URI endpoint;
        private String username;
        private String password;
        private String ntlmDomain;
        private String ntlmWorkstation;
        private Set<String> nonProxyHosts;
        private Boolean preemptiveBasicAuthenticationEnabled;
        private Boolean useSystemPropertyValues = Boolean.TRUE;
        private Boolean useEnvironmentVariableValues = Boolean.TRUE;
        private String scheme = "http";

        @Override
        public Builder endpoint(URI endpoint) {
            if (endpoint != null) {
                Validate.isTrue(isEmpty(endpoint.getUserInfo()), "Proxy endpoint user info is not supported.");
                Validate.isTrue(isEmpty(endpoint.getPath()), "Proxy endpoint path is not supported.");
                Validate.isTrue(isEmpty(endpoint.getQuery()), "Proxy endpoint query is not supported.");
                Validate.isTrue(isEmpty(endpoint.getFragment()), "Proxy endpoint fragment is not supported.");
            }

            this.endpoint = endpoint;
            return this;
        }

        public void setEndpoint(URI endpoint) {
            endpoint(endpoint);
        }

        @Override
        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public void setUsername(String username) {
            username(username);
        }

        @Override
        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public void setPassword(String password) {
            password(password);
        }

        @Override
        public Builder ntlmDomain(String proxyDomain) {
            this.ntlmDomain = proxyDomain;
            return this;
        }

        public void setNtlmDomain(String ntlmDomain) {
            ntlmDomain(ntlmDomain);
        }

        @Override
        public Builder ntlmWorkstation(String proxyWorkstation) {
            this.ntlmWorkstation = proxyWorkstation;
            return this;
        }

        public void setNtlmWorkstation(String ntlmWorkstation) {
            ntlmWorkstation(ntlmWorkstation);
        }

        @Override
        public Builder nonProxyHosts(Set<String> nonProxyHosts) {
            this.nonProxyHosts = nonProxyHosts != null ? new HashSet<>(nonProxyHosts) : null;
            return this;
        }

        @Override
        public Builder addNonProxyHost(String nonProxyHost) {
            if (this.nonProxyHosts == null) {
                this.nonProxyHosts = new HashSet<>();
            }
            this.nonProxyHosts.add(nonProxyHost);
            return this;
        }

        public void setNonProxyHosts(Set<String> nonProxyHosts) {
            nonProxyHosts(nonProxyHosts);
        }

        @Override
        public Builder preemptiveBasicAuthenticationEnabled(Boolean preemptiveBasicAuthenticationEnabled) {
            this.preemptiveBasicAuthenticationEnabled = preemptiveBasicAuthenticationEnabled;
            return this;
        }

        public void setPreemptiveBasicAuthenticationEnabled(Boolean preemptiveBasicAuthenticationEnabled) {
            preemptiveBasicAuthenticationEnabled(preemptiveBasicAuthenticationEnabled);
        }

        @Override
        public Builder useSystemPropertyValues(Boolean useSystemPropertyValues) {
            this.useSystemPropertyValues = useSystemPropertyValues;
            return this;
        }

        public void setUseSystemPropertyValues(Boolean useSystemPropertyValues) {
            useSystemPropertyValues(useSystemPropertyValues);
        }


        @Override
        public Builder useEnvironmentVariableValues(Boolean useEnvironmentVariableValues) {
            this.useEnvironmentVariableValues = useEnvironmentVariableValues;
            return this;
        }

        @Override
        public Builder scheme(String scheme) {
            this.scheme = scheme;
            return this;
        }

        public void setUseEnvironmentVariableValues(Boolean useEnvironmentVariableValues) {
            useEnvironmentVariableValues(useEnvironmentVariableValues);
        }

        @Override
        public ProxyConfiguration build() {
            return new ProxyConfiguration(this);
        }
    }

}
