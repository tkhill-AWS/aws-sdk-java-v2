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

package software.amazon.awssdk.http.nio.netty;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.apache.commons.lang3.RandomStringUtils.randomAlphabetic;
import static org.apache.commons.lang3.RandomStringUtils.randomAscii;
import static org.apache.commons.lang3.StringUtils.reverse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClientTestUtils.assertCanReceiveBasicRequest;
import static software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClientTestUtils.createProvider;
import static software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClientTestUtils.createRequest;
import static software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClientTestUtils.makeSimpleRequest;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslProvider;
import io.netty.util.AttributeKey;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.TrustManagerFactory;
import org.assertj.core.api.Condition;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;
import software.amazon.awssdk.http.HttpMetric;
import software.amazon.awssdk.http.HttpTestUtils;
import software.amazon.awssdk.http.ProtocolNegotiation;
import software.amazon.awssdk.http.SdkHttpConfigurationOption;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.SimpleHttpContentPublisher;
import software.amazon.awssdk.http.async.AsyncExecuteRequest;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.nio.netty.internal.NettyConfiguration;
import software.amazon.awssdk.http.nio.netty.internal.SdkChannelPool;
import software.amazon.awssdk.http.nio.netty.internal.SdkChannelPoolMap;
import software.amazon.awssdk.metrics.MetricCollection;
import software.amazon.awssdk.utils.AttributeMap;

@RunWith(MockitoJUnitRunner.class)
public class NettyNioAsyncHttpClientWireMockTest {

    private final RecordingNetworkTrafficListener wiremockTrafficListener = new RecordingNetworkTrafficListener();

    @Rule
    public WireMockRule mockServer = new WireMockRule(wireMockConfig()
            .dynamicPort()
            .dynamicHttpsPort()
            .networkTrafficListener(wiremockTrafficListener));

    private static SdkAsyncHttpClient client = NettyNioAsyncHttpClient.builder().buildWithDefaults(mapWithTrustAllCerts());

    @Before
    public void methodSetup() {
        wiremockTrafficListener.reset();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        client.close();
    }

    @Test
    public void defaultConnectionIdleTimeout() {
        try (NettyNioAsyncHttpClient client = (NettyNioAsyncHttpClient) NettyNioAsyncHttpClient.builder().build()) {
            assertThat(client.configuration().idleTimeoutMillis()).isEqualTo(5000);
        }
    }

    @Test
    public void defaultTlsHandshakeTimeout() {
        try (NettyNioAsyncHttpClient client = (NettyNioAsyncHttpClient) NettyNioAsyncHttpClient.create()) {
            assertThat(client.configuration().tlsHandshakeTimeout().toMillis()).isEqualTo(5000);
        }
    }

    @Test
    public void noTlsTimeout_hasConnectTimeout_shouldResolveToConnectTimeout() {
        Duration connectTimeout = Duration.ofSeconds(1);
        try (NettyNioAsyncHttpClient client = (NettyNioAsyncHttpClient) NettyNioAsyncHttpClient.builder()
                                                                                               .connectionTimeout(connectTimeout)
                                                                                               .build()) {
            assertThat(client.configuration().tlsHandshakeTimeout()).isEqualTo(connectTimeout);
        }
        Duration timeoutOverride = Duration.ofSeconds(2);
        try (NettyNioAsyncHttpClient client = (NettyNioAsyncHttpClient) NettyNioAsyncHttpClient.builder()
                                                                                               .connectionTimeout(timeoutOverride)
                                                                                               .build()) {
            assertThat(client.configuration().tlsHandshakeTimeout()).isEqualTo(timeoutOverride);
        }
    }


    @Test
    public void tlsTimeoutConfigured_shouldHonor() {
        Duration connectTimeout = Duration.ofSeconds(1);
        Duration tlsTimeout = Duration.ofSeconds(3);
        try (NettyNioAsyncHttpClient client = (NettyNioAsyncHttpClient) NettyNioAsyncHttpClient.builder()
                                                                                               .tlsNegotiationTimeout(tlsTimeout)
                                                                                               .connectionTimeout(connectTimeout)
                                                                                               .build()) {
            assertThat(client.configuration().tlsHandshakeTimeout()).isEqualTo(tlsTimeout);
        }

        try (NettyNioAsyncHttpClient client = (NettyNioAsyncHttpClient) NettyNioAsyncHttpClient.builder()
                                                                                               .connectionTimeout(connectTimeout)
                                                                                               .tlsNegotiationTimeout(tlsTimeout)
                                                                                               .build()) {
            assertThat(client.configuration().tlsHandshakeTimeout()).isEqualTo(tlsTimeout);
        }
    }

    @Test
    public void overrideConnectionIdleTimeout_shouldHonor() {
        try (NettyNioAsyncHttpClient client = (NettyNioAsyncHttpClient) NettyNioAsyncHttpClient.builder()
                                                                                               .connectionMaxIdleTime(Duration.ofMillis(1000))
                                                                                               .build()) {
            assertThat(client.configuration().idleTimeoutMillis()).isEqualTo(1000);
        }
    }

    @Test
    public void invalidMaxPendingConnectionAcquireConfig_shouldPropagateException() {
        try (SdkAsyncHttpClient customClient = NettyNioAsyncHttpClient.builder()
                                                                 .maxConcurrency(1)
                                                                 .maxPendingConnectionAcquires(0)
                                                                 .build()) {
            assertThatThrownBy(() -> makeSimpleRequest(customClient, mockServer)).hasMessageContaining("java.lang"
                                                                                              + ".IllegalArgumentException: maxPendingAcquires: 0 (expected: >= 1)");
        }
    }

    @Test
    public void customFactoryIsUsed() throws Exception {
        ThreadFactory threadFactory = spy(new CustomThreadFactory());
        SdkAsyncHttpClient customClient =
            NettyNioAsyncHttpClient.builder()
                                   .eventLoopGroupBuilder(SdkEventLoopGroup.builder()
                                                                           .threadFactory(threadFactory))
                                   .build();

        makeSimpleRequest(customClient, mockServer);
        customClient.close();

        Mockito.verify(threadFactory, atLeastOnce()).newThread(Mockito.any());
    }

    @Test
    public void openSslBeingUsed() throws Exception {
        try (SdkAsyncHttpClient customClient =
                 NettyNioAsyncHttpClient.builder()
                                        .sslProvider(SslProvider.OPENSSL)
                                        .build()) {
            makeSimpleRequest(customClient, mockServer);
        }
    }

    @Test
    public void defaultJdkSslProvider() throws Exception {
        try (SdkAsyncHttpClient customClient =
                 NettyNioAsyncHttpClient.builder()
                                        .sslProvider(SslProvider.JDK)
                                        .build()) {
            makeSimpleRequest(customClient, mockServer);
            customClient.close();
        }
    }

    @Test
    public void defaultThreadFactoryUsesHelpfulName() throws Exception {
        // Make a request to ensure a thread is primed
        makeSimpleRequest(client, mockServer);

        String expectedPattern = "aws-java-sdk-NettyEventLoop-\\d+-\\d+";
        assertThat(Thread.getAllStackTraces().keySet())
                .areAtLeast(1, new Condition<>(t -> t.getName().matches(expectedPattern),
                                               "Matches default thread pattern: `%s`", expectedPattern));
    }

    @Test
    public void customThreadCountIsRespected() throws Exception {
        final int threadCount = 10;
        ThreadFactory threadFactory = spy(new CustomThreadFactory());
        SdkAsyncHttpClient customClient =
                NettyNioAsyncHttpClient.builder()
                                       .eventLoopGroupBuilder(SdkEventLoopGroup.builder()
                                                                               .threadFactory(threadFactory)
                                                                               .numberOfThreads(threadCount))
                                       .build();

        // Have to make enough requests to prime the threads
        for (int i = 0; i < threadCount + 1; i++) {
            makeSimpleRequest(customClient, mockServer);
        }
        customClient.close();

        Mockito.verify(threadFactory, times(threadCount)).newThread(Mockito.any());
    }

    @Test
    public void customEventLoopGroup_NotClosedWhenClientIsClosed() throws Exception {

        ThreadFactory threadFactory = spy(new CustomThreadFactory());
        // Cannot use DefaultEventLoopGroupFactory because the concrete
        // implementation it creates is platform-dependent and could be a final
        // (i.e. non-spyable) class.
        EventLoopGroup eventLoopGroup = spy(new NioEventLoopGroup(0, threadFactory));
        SdkAsyncHttpClient customClient =
                NettyNioAsyncHttpClient.builder()
                                       .eventLoopGroup(SdkEventLoopGroup.create(eventLoopGroup, NioSocketChannel::new))
                                       .build();

        makeSimpleRequest(customClient, mockServer);
        customClient.close();

        Mockito.verify(threadFactory, atLeastOnce()).newThread(Mockito.any());
        Mockito.verify(eventLoopGroup, never()).shutdownGracefully();
    }

    @Test
    public void customChannelFactoryIsUsed() throws Exception {

        ChannelFactory channelFactory = mock(ChannelFactory.class);

        when(channelFactory.newChannel()).thenAnswer((Answer<NioSocketChannel>) invocationOnMock -> new NioSocketChannel());
        EventLoopGroup customEventLoopGroup = new NioEventLoopGroup();

        SdkAsyncHttpClient customClient =
            NettyNioAsyncHttpClient.builder()
                                   .eventLoopGroup(SdkEventLoopGroup.create(customEventLoopGroup, channelFactory))
                                   .build();

        makeSimpleRequest(customClient, mockServer);
        customClient.close();

        Mockito.verify(channelFactory, atLeastOnce()).newChannel();
        assertThat(customEventLoopGroup.isShuttingDown()).isFalse();
        customEventLoopGroup.shutdownGracefully().awaitUninterruptibly();
    }

    @Test
    public void closeClient_shouldCloseUnderlyingResources() {
        SdkEventLoopGroup eventLoopGroup = SdkEventLoopGroup.builder().build();
        SdkChannelPool channelPool = mock(SdkChannelPool.class);
        SdkChannelPoolMap<URI, SdkChannelPool> sdkChannelPoolMap = new SdkChannelPoolMap<URI, SdkChannelPool>() {
            @Override
            protected SdkChannelPool newPool(URI key) {
                return channelPool;
            }
        };

        sdkChannelPoolMap.get(URI.create("http://blah"));
        NettyConfiguration nettyConfiguration = new NettyConfiguration(AttributeMap.empty());

        SdkAsyncHttpClient customerClient =
            new NettyNioAsyncHttpClient(eventLoopGroup, sdkChannelPoolMap, nettyConfiguration, ProtocolNegotiation.ASSUME_PROTOCOL);

        customerClient.close();
        assertThat(eventLoopGroup.eventLoopGroup().isShuttingDown()).isTrue();
        assertThat(eventLoopGroup.eventLoopGroup().isTerminated()).isTrue();
        assertThat(sdkChannelPoolMap).isEmpty();
        Mockito.verify(channelPool).close();
    }

    @Test
    public void responseConnectionReused_shouldReleaseChannel() throws Exception {

        ChannelFactory channelFactory = mock(ChannelFactory.class);
        EventLoopGroup customEventLoopGroup = new NioEventLoopGroup(1);
        NioSocketChannel channel = new NioSocketChannel();

        when(channelFactory.newChannel()).thenAnswer((Answer<NioSocketChannel>) invocationOnMock -> channel);
        SdkEventLoopGroup eventLoopGroup = SdkEventLoopGroup.create(customEventLoopGroup, channelFactory);

        NettyNioAsyncHttpClient customClient =
            (NettyNioAsyncHttpClient) NettyNioAsyncHttpClient.builder()
                                                             .eventLoopGroup(eventLoopGroup)
                                                             .maxConcurrency(1)
                                                             .build();

        makeSimpleRequest(customClient, mockServer);
        verifyChannelRelease(channel);
        assertThat(channel.isShutdown()).isFalse();

        customClient.close();
        eventLoopGroup.eventLoopGroup().shutdownGracefully().awaitUninterruptibly();
    }

    @Test
    public void connectionInactive_shouldReleaseChannel() throws Exception {

        ChannelFactory channelFactory = mock(ChannelFactory.class);
        EventLoopGroup customEventLoopGroup = new NioEventLoopGroup(1);
        NioSocketChannel channel = new NioSocketChannel();

        when(channelFactory.newChannel()).thenAnswer((Answer<NioSocketChannel>) invocationOnMock -> channel);
        SdkEventLoopGroup eventLoopGroup = SdkEventLoopGroup.create(customEventLoopGroup, channelFactory);

        NettyNioAsyncHttpClient customClient =
            (NettyNioAsyncHttpClient) NettyNioAsyncHttpClient.builder()
                                                             .eventLoopGroup(eventLoopGroup)
                                                             .maxConcurrency(1)
                                                             .build();


        String body = randomAlphabetic(10);
        URI uri = URI.create("http://localhost:" + mockServer.port());
        SdkHttpRequest request = createRequest(uri);
        RecordingResponseHandler recorder = new RecordingResponseHandler();


        stubFor(any(urlPathEqualTo("/")).willReturn(aResponse().withBody(body)
                                                               .withStatus(500)
                                                               .withFault(Fault.RANDOM_DATA_THEN_CLOSE)));

        customClient.execute(AsyncExecuteRequest.builder()
                                                .request(request)
                                                .requestContentPublisher(createProvider(""))
                                                .responseHandler(recorder).build());

        verifyChannelRelease(channel);
        assertThat(channel.isShutdown()).isTrue();

        customClient.close();
        eventLoopGroup.eventLoopGroup().shutdownGracefully().awaitUninterruptibly();
    }

    @Test
    public void responseConnectionClosed_shouldCloseAndReleaseChannel() throws Exception {

        ChannelFactory channelFactory = mock(ChannelFactory.class);
        EventLoopGroup customEventLoopGroup = new NioEventLoopGroup(1);
        NioSocketChannel channel = new NioSocketChannel();

        when(channelFactory.newChannel()).thenAnswer((Answer<NioSocketChannel>) invocationOnMock -> channel);

        URI uri = URI.create("http://localhost:" + mockServer.port());
        SdkHttpRequest request = createRequest(uri);
        RecordingResponseHandler recorder = new RecordingResponseHandler();

        SdkEventLoopGroup eventLoopGroup = SdkEventLoopGroup.create(customEventLoopGroup, channelFactory);

        NettyNioAsyncHttpClient customClient =
            (NettyNioAsyncHttpClient) NettyNioAsyncHttpClient.builder()
                                                             .eventLoopGroup(eventLoopGroup)
                                                             .maxConcurrency(1)
                                                             .build();

        String body = randomAlphabetic(10);

        stubFor(any(urlPathEqualTo("/")).willReturn(aResponse().withBody(body)
                                                               .withStatus(500)
                                                               .withHeader("Connection", "close")
        ));

        customClient.execute(AsyncExecuteRequest.builder()
                                                .request(request)
                                                .requestContentPublisher(createProvider(""))
                                                .responseHandler(recorder).build());
        recorder.completeFuture.get(5, TimeUnit.SECONDS);

        verifyChannelRelease(channel);
        assertThat(channel.isShutdown()).isTrue();

        customClient.close();
        eventLoopGroup.eventLoopGroup().shutdownGracefully().awaitUninterruptibly();
    }

    @Test
    public void builderUsesProvidedTrustManagersProvider() throws Exception {
        WireMockServer selfSignedServer = HttpTestUtils.createSelfSignedServer();

        TrustManagerFactory managerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managerFactory.init(HttpTestUtils.getSelfSignedKeyStore());

        try (SdkAsyncHttpClient netty = NettyNioAsyncHttpClient.builder()
                                                               .tlsTrustManagersProvider(managerFactory::getTrustManagers)
                                                               .build()) {
            selfSignedServer.start();
            URI uri = URI.create("https://localhost:" + selfSignedServer.httpsPort());

            SdkHttpRequest request = createRequest(uri);
            RecordingResponseHandler recorder = new RecordingResponseHandler();
            netty.execute(AsyncExecuteRequest.builder().request(request).requestContentPublisher(createProvider("")).responseHandler(recorder).build());

            recorder.completeFuture.get(5, TimeUnit.SECONDS);
        } finally {
            selfSignedServer.stop();
        }
    }

    @Test
    public void canMakeBasicRequestOverHttp() throws Exception {
        String smallBody = randomAlphabetic(10);
        URI uri = URI.create("http://localhost:" + mockServer.port());

        assertCanReceiveBasicRequest(client, uri, smallBody);
    }

    @Test
    public void canMakeBasicRequestOverHttps() throws Exception {
        String smallBody = randomAlphabetic(10);
        URI uri = URI.create("https://localhost:" + mockServer.httpsPort());

        assertCanReceiveBasicRequest(client, uri, smallBody);
    }

    @Test
    public void canHandleLargerPayloadsOverHttp() throws Exception {
        String largishBody = randomAlphabetic(25000);

        URI uri = URI.create("http://localhost:" + mockServer.port());

        assertCanReceiveBasicRequest(client, uri, largishBody);
    }

    @Test
    public void canHandleLargerPayloadsOverHttps() throws Exception {
        String largishBody = randomAlphabetic(25000);

        URI uri = URI.create("https://localhost:" + mockServer.httpsPort());

        assertCanReceiveBasicRequest(client, uri, largishBody);
    }

    @Test
    public void canSendContentAndGetThatContentBack() throws Exception {
        String body = randomAlphabetic(50);
        stubFor(any(urlEqualTo("/echo?reversed=true"))
                        .withRequestBody(equalTo(body))
                        .willReturn(aResponse().withBody(reverse(body))));
        URI uri = URI.create("http://localhost:" + mockServer.port());

        SdkHttpRequest request = createRequest(uri, "/echo", body, SdkHttpMethod.POST, singletonMap("reversed", "true"));

        RecordingResponseHandler recorder = new RecordingResponseHandler();
        client.execute(AsyncExecuteRequest.builder().request(request).requestContentPublisher(createProvider(body)).responseHandler(recorder).build());

        recorder.completeFuture.get(5, TimeUnit.SECONDS);

        verify(1, postRequestedFor(urlEqualTo("/echo?reversed=true")));

        assertThat(recorder.fullResponseAsString()).isEqualTo(reverse(body));
    }

    @Test
    public void requestContentOnlyEqualToContentLengthHeaderFromProvider() throws InterruptedException, ExecutionException, TimeoutException, IOException {
        final String content = randomAlphabetic(32);
        final String streamContent = content + reverse(content);
        stubFor(any(urlEqualTo("/echo?reversed=true"))
                .withRequestBody(equalTo(content))
                .willReturn(aResponse().withBody(reverse(content))));
        URI uri = URI.create("http://localhost:" + mockServer.port());

        SdkHttpFullRequest request = createRequest(uri, "/echo", streamContent, SdkHttpMethod.POST, singletonMap("reversed", "true"));
        request = request.toBuilder().putHeader("Content-Length", Integer.toString(content.length())).build();
        RecordingResponseHandler recorder = new RecordingResponseHandler();

        client.execute(AsyncExecuteRequest.builder().request(request).requestContentPublisher(createProvider(streamContent)).responseHandler(recorder).build());

        recorder.completeFuture.get(5, TimeUnit.SECONDS);

        // HTTP servers will stop processing the request as soon as it reads
        // bytes equal to 'Content-Length' so we need to inspect the raw
        // traffic to ensure that there wasn't anything after that.
        assertThat(wiremockTrafficListener.requests().toString()).endsWith(content);
    }

    @Test
    public void closeMethodClosesOpenedChannels() throws InterruptedException, TimeoutException, ExecutionException {
        String body = randomAlphabetic(10);
        URI uri = URI.create("https://localhost:" + mockServer.httpsPort());
        stubFor(any(urlPathEqualTo("/")).willReturn(aResponse().withHeader("Some-Header", "With Value").withBody(body)));

        SdkHttpFullRequest request = createRequest(uri, "/", body, SdkHttpMethod.POST, Collections.emptyMap());
        RecordingResponseHandler recorder = new RecordingResponseHandler();

        CompletableFuture<Boolean> channelClosedFuture = new CompletableFuture<>();
        ChannelFactory<NioSocketChannel> channelFactory = new ChannelFactory<NioSocketChannel>() {
            @Override
            public NioSocketChannel newChannel() {
                return new NioSocketChannel() {
                    @Override
                    public ChannelFuture close() {
                        ChannelFuture cf = super.close();
                        channelClosedFuture.complete(true);
                        return cf;
                    }
                };
            }
        };

        SdkAsyncHttpClient customClient = NettyNioAsyncHttpClient.builder()
                .eventLoopGroup(new SdkEventLoopGroup(new NioEventLoopGroup(1), channelFactory))
                .buildWithDefaults(mapWithTrustAllCerts());

        try {
            customClient.execute(AsyncExecuteRequest.builder()
                    .request(request)
                    .requestContentPublisher(createProvider(body))
                    .responseHandler(recorder).build())
                    .join();
        } finally {
            customClient.close();
        }

        assertThat(channelClosedFuture.get(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    public void execute_requestByteBufferWithNonZeroPosition_shouldHonor() throws Exception {
        String body = randomAlphabetic(70);
        byte[] content = randomAscii(100).getBytes();
        ByteBuffer requestContent = ByteBuffer.wrap(content);
        requestContent.position(95);
        String expected = new String(content, 95, 5);

        URI uri = URI.create("http://localhost:" + mockServer.port());
        stubFor(post(urlPathEqualTo("/"))
                    .withRequestBody(equalTo(expected)).willReturn(aResponse().withBody(body)));
        SdkHttpRequest request = createRequest(uri, "/", expected, SdkHttpMethod.POST, emptyMap());
        RecordingResponseHandler recorder = new RecordingResponseHandler();

        client.execute(AsyncExecuteRequest.builder().request(request).requestContentPublisher(new SimpleHttpContentPublisher(requestContent)).responseHandler(recorder).build());
        recorder.completeFuture.get(5, TimeUnit.SECONDS);
        verify(postRequestedFor(urlPathEqualTo("/")).withRequestBody(equalTo(expected)));
    }

    // Needs to be a non-anon class in order to spy
    public static class CustomThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r);
        }
    }

    @Test
    public void testExceptionMessageChanged_WhenPendingAcquireQueueIsFull() throws Exception {
        String expectedErrorMsg = "Maximum pending connection acquisitions exceeded.";

        SdkAsyncHttpClient customClient = NettyNioAsyncHttpClient.builder()
                                                                 .maxConcurrency(1)
                                                                 .maxPendingConnectionAcquires(1)
                                                                 .build();

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            futures.add(makeSimpleRequestAndReturnResponseHandler(customClient, 1000).completeFuture);
        }

        assertThatThrownBy(() -> CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join())
            .hasMessageContaining(expectedErrorMsg);

        customClient.close();
    }


    @Test
    public void testExceptionMessageChanged_WhenConnectionTimeoutErrorEncountered() throws Exception {
        String expectedErrorMsg = "Acquire operation took longer than the configured maximum time. This indicates that a request "
                                  + "cannot get a connection from the pool within the specified maximum time.";

        SdkAsyncHttpClient customClient = NettyNioAsyncHttpClient.builder()
                                                                 .maxConcurrency(1)
                                                                 .connectionTimeout(Duration.ofMillis(1))
                                                                 .connectionAcquisitionTimeout(Duration.ofMillis(1))
                                                                 .build();

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(makeSimpleRequestAndReturnResponseHandler(customClient, 1000).completeFuture);
        }

        assertThatThrownBy(() -> CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join())
            .hasMessageContaining(expectedErrorMsg);

        customClient.close();
    }

    @Test
    public void createNettyClient_ReadWriteTimeoutCanBeZero() throws Exception {
        SdkAsyncHttpClient customClient = NettyNioAsyncHttpClient.builder()
                .readTimeout(Duration.ZERO)
                .writeTimeout(Duration.ZERO)
                .build();

        makeSimpleRequest(customClient, mockServer);

        customClient.close();
    }

    @Test
    public void createNettyClient_tlsNegotiationTimeoutNotPositive_shouldThrowException() throws Exception {
        assertThatThrownBy(() -> NettyNioAsyncHttpClient.builder()
                                                        .tlsNegotiationTimeout(Duration.ZERO)
                                                        .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be positive");
        assertThatThrownBy(() -> NettyNioAsyncHttpClient.builder()
                                                        .tlsNegotiationTimeout(Duration.ofSeconds(-1))
                                                        .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be positive");
    }


    @Test
    public void metricsAreCollectedWhenMaxPendingConnectionAcquisitionsAreExceeded() throws Exception {
        SdkAsyncHttpClient customClient = NettyNioAsyncHttpClient.builder()
                                                                 .maxConcurrency(1)
                                                                 .maxPendingConnectionAcquires(1)
                                                                 .build();

        List<RecordingResponseHandler> handlers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            handlers.add(makeSimpleRequestAndReturnResponseHandler(customClient, 1000));
        }

        for (RecordingResponseHandler handler : handlers) {
            try {
                handler.executionFuture.join();
            } catch (Exception e) {
                // Ignored.
            }

            MetricCollection metrics = handler.collector.collect();
            assertThat(metrics.metricValues(HttpMetric.HTTP_CLIENT_NAME)).containsExactly("NettyNio");
            assertThat(metrics.metricValues(HttpMetric.MAX_CONCURRENCY)).containsExactly(1);
            assertThat(metrics.metricValues(HttpMetric.PENDING_CONCURRENCY_ACQUIRES)).allSatisfy(a -> assertThat(a).isBetween(0, 9));
            assertThat(metrics.metricValues(HttpMetric.LEASED_CONCURRENCY)).allSatisfy(a -> assertThat(a).isBetween(0, 1));
            assertThat(metrics.metricValues(HttpMetric.AVAILABLE_CONCURRENCY)).allSatisfy(a -> assertThat(a).isBetween(0, 1));
        }

        customClient.close();
    }

    @Test
    public void metricsAreCollectedForSuccessfulCalls() throws Exception {
        SdkAsyncHttpClient customClient = NettyNioAsyncHttpClient.builder()
                                                                 .maxConcurrency(10)
                                                                 .build();

        RecordingResponseHandler handler = makeSimpleRequestAndReturnResponseHandler(customClient);

        handler.executionFuture.get(10, TimeUnit.SECONDS);

        Thread.sleep(5_000);
        MetricCollection metrics = handler.collector.collect();
        assertThat(metrics.metricValues(HttpMetric.HTTP_CLIENT_NAME)).containsExactly("NettyNio");
        assertThat(metrics.metricValues(HttpMetric.MAX_CONCURRENCY)).containsExactly(10);
        assertThat(metrics.metricValues(HttpMetric.PENDING_CONCURRENCY_ACQUIRES).get(0)).isBetween(0, 1);
        assertThat(metrics.metricValues(HttpMetric.LEASED_CONCURRENCY).get(0)).isBetween(0, 1);
        assertThat(metrics.metricValues(HttpMetric.AVAILABLE_CONCURRENCY).get(0)).isBetween(0, 1);

        customClient.close();
    }

    @Test
    public void metricsAreCollectedForClosedClientCalls() throws Exception {
        SdkAsyncHttpClient customClient = NettyNioAsyncHttpClient.builder()
                                                                 .maxConcurrency(10)
                                                                 .build();
        customClient.close();

        RecordingResponseHandler handler = makeSimpleRequestAndReturnResponseHandler(customClient);

        try {
            handler.executionFuture.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Expected
        }

        MetricCollection metrics = handler.collector.collect();
        assertThat(metrics.metricValues(HttpMetric.HTTP_CLIENT_NAME)).containsExactly("NettyNio");
        assertThat(metrics.metricValues(HttpMetric.MAX_CONCURRENCY)).containsExactly(10);
        assertThat(metrics.metricValues(HttpMetric.PENDING_CONCURRENCY_ACQUIRES)).containsExactly(0);
        assertThat(metrics.metricValues(HttpMetric.LEASED_CONCURRENCY)).containsExactly(0);
        assertThat(metrics.metricValues(HttpMetric.AVAILABLE_CONCURRENCY).get(0)).isBetween(0, 1);
    }

    private void verifyChannelRelease(Channel channel) throws InterruptedException {
        Thread.sleep(1000);
        assertThat(channel.attr(AttributeKey.valueOf("channelPool")).get()).isNull();
    }

    private RecordingResponseHandler makeSimpleRequestAndReturnResponseHandler(SdkAsyncHttpClient client) throws Exception {
        return makeSimpleRequestAndReturnResponseHandler(client, null);
    }

    private RecordingResponseHandler makeSimpleRequestAndReturnResponseHandler(SdkAsyncHttpClient client, Integer delayInMillis)
        throws Exception {
        String body = randomAlphabetic(10);
        URI uri = URI.create("http://localhost:" + mockServer.port());
        stubFor(any(urlPathEqualTo("/")).willReturn(aResponse().withBody(body).withFixedDelay(delayInMillis)));
        SdkHttpRequest request = createRequest(uri);
        RecordingResponseHandler recorder = new RecordingResponseHandler();
        recorder.executionFuture = client.execute(AsyncExecuteRequest.builder()
                                                                     .request(request)
                                                                     .requestContentPublisher(createProvider(""))
                                                                     .responseHandler(recorder)
                                                                     .metricCollector(recorder.collector)
                                                                     .build());
        return recorder;
    }

    private static AttributeMap mapWithTrustAllCerts() {
        return AttributeMap.builder()
                           .put(SdkHttpConfigurationOption.TRUST_ALL_CERTIFICATES, true)
                           .build();
    }
}
