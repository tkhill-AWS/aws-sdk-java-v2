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

package software.amazon.awssdk.http.crt.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static software.amazon.awssdk.http.HttpTestUtils.createProvider;
import static software.amazon.awssdk.http.crt.CrtHttpClientTestUtils.createRequest;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import javax.net.ssl.SSLHandshakeException;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import java.util.AbstractMap.SimpleEntry;
import java.util.Map.Entry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.crt.CrtRuntimeException;
import software.amazon.awssdk.crt.http.HttpClientConnection;
import software.amazon.awssdk.crt.http.HttpClientConnectionManager;
import software.amazon.awssdk.crt.http.HttpException;
import software.amazon.awssdk.crt.http.HttpRequest;
import software.amazon.awssdk.http.SdkCancellationException;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.async.AsyncExecuteRequest;
import software.amazon.awssdk.http.async.SdkAsyncHttpResponseHandler;
import software.amazon.awssdk.http.crt.internal.response.CrtResponseAdapter;
import software.amazon.awssdk.utils.CompletableFutureUtils;

@ExtendWith(MockitoExtension.class)
public class CrtAsyncRequestExecutorTest {

    private CrtAsyncRequestExecutor requestExecutor;
    @Mock
    private HttpClientConnectionManager connectionManager;

    @Mock
    private SdkAsyncHttpResponseHandler responseHandler;

    @Mock
    private HttpClientConnection httpClientConnection;

    public static Stream<Entry<Integer, Class<? extends Throwable>>> mappedExceptions() {
        return Stream.of(
            new SimpleEntry<>(0x0405, SSLHandshakeException.class), // For AWS_IO_TLS_ERROR_NEGOTIATION_FAILURE (1029)
            new SimpleEntry<>(0x0418, ConnectException.class) // For AWS_IO_SOCKET_TIMEOUT (1048)
        );
    }

    @BeforeEach
    public void setup() {
        requestExecutor = new CrtAsyncRequestExecutor();
    }

    @AfterEach
    public void teardown() {
        Mockito.reset(connectionManager, responseHandler, httpClientConnection);
    }

    @Test
    public void acquireConnectionThrowException_shouldInvokeOnError() {
        RuntimeException exception = new RuntimeException("error");
        CrtAsyncRequestContext context = CrtAsyncRequestContext.builder()
                                                               .crtConnPool(connectionManager)
                                                               .request(AsyncExecuteRequest.builder()
                                                                                 .responseHandler(responseHandler)
                                                                                 .build())
                                                               .build();
        CompletableFuture<HttpClientConnection> completableFuture = new CompletableFuture<>();

        Mockito.when(connectionManager.acquireConnection()).thenReturn(completableFuture);
        completableFuture.completeExceptionally(exception);

        CompletableFuture<Void> executeFuture = requestExecutor.execute(context);

        ArgumentCaptor<Exception> argumentCaptor = ArgumentCaptor.forClass(Exception.class);
        Mockito.verify(responseHandler).onError(argumentCaptor.capture());

        Exception actualException = argumentCaptor.getValue();
        assertThat(actualException).hasMessageContaining("An exception occurred when acquiring a connection");
        assertThat(actualException).hasCause(exception);
        assertThat(executeFuture).hasFailedWithThrowableThat().hasCause(exception).isInstanceOf(IOException.class);
    }

    @Test
    public void invalidRequest_requestConversionThrowError_shouldInvokeOnError() {
        CrtAsyncRequestContext context = CrtAsyncRequestContext.builder()
                                                               .crtConnPool(connectionManager)
                                                               .request(AsyncExecuteRequest.builder()
                                                                                           .responseHandler(responseHandler)
                                                                                           .build())
                                                               .build();
        CompletableFuture<HttpClientConnection> completableFuture = new CompletableFuture<>();

        Mockito.when(connectionManager.acquireConnection()).thenReturn(completableFuture);
        completableFuture.complete(httpClientConnection);

        CompletableFuture<Void> executeFuture = requestExecutor.execute(context);

        ArgumentCaptor<Exception> argumentCaptor = ArgumentCaptor.forClass(Exception.class);
        Mockito.verify(responseHandler).onError(argumentCaptor.capture());

        Exception actualException = argumentCaptor.getValue();
        assertThat(actualException).isInstanceOf(NullPointerException.class);
        assertThat(executeFuture).hasFailedWithThrowableThat().isInstanceOf(NullPointerException.class);
    }

    @Test
    public void executeAsyncRequest_CrtRuntimeException_shouldInvokeOnError() {
        CrtRuntimeException exception = new CrtRuntimeException("");
        CrtAsyncRequestContext context = crtAsyncRequestContext();
        CompletableFuture<HttpClientConnection> completableFuture = new CompletableFuture<>();

        Mockito.when(connectionManager.acquireConnection()).thenReturn(completableFuture);
        completableFuture.complete(httpClientConnection);

        Mockito.when(httpClientConnection.makeRequest(Mockito.any(HttpRequest.class), Mockito.any(CrtResponseAdapter.class)))
               .thenThrow(exception);

        CompletableFuture<Void> executeFuture = requestExecutor.execute(context);

        ArgumentCaptor<Exception> argumentCaptor = ArgumentCaptor.forClass(Exception.class);
        Mockito.verify(responseHandler).onError(argumentCaptor.capture());

        Exception actualException = argumentCaptor.getValue();
        assertThat(actualException).hasMessageContaining("An exception occurred when making the request");
        assertThat(actualException).hasCause(exception);
        assertThat(executeFuture).hasFailedWithThrowableThat().hasCause(exception).isInstanceOf(IOException.class);
    }

    @Test
    public void cancelRequest_shouldInvokeOnError() {
        CrtAsyncRequestContext context = CrtAsyncRequestContext.builder()
                                                               .crtConnPool(connectionManager)
                                                               .request(AsyncExecuteRequest.builder()
                                                                                 .responseHandler(responseHandler)
                                                                                 .build())
                                                               .build();
        CompletableFuture<HttpClientConnection> completableFuture = new CompletableFuture<>();

        Mockito.when(connectionManager.acquireConnection()).thenReturn(completableFuture);

        CompletableFuture<Void> executeFuture = requestExecutor.execute(context);
        executeFuture.cancel(true);

        ArgumentCaptor<Exception> argumentCaptor = ArgumentCaptor.forClass(Exception.class);
        Mockito.verify(responseHandler).onError(argumentCaptor.capture());

        Exception actualException = argumentCaptor.getValue();
        assertThat(actualException).hasMessageContaining("The request was cancelled");
        assertThat(actualException).isInstanceOf(SdkCancellationException.class);
    }

    @Test
    public void execute_AcquireConnectionFailure_shouldAlwaysWrapIOException() {
        CrtAsyncRequestContext context = crtAsyncRequestContext();
        RuntimeException exception = new RuntimeException("some failure");
        CompletableFuture<HttpClientConnection> completableFuture = CompletableFutureUtils.failedFuture(exception);

        Mockito.when(connectionManager.acquireConnection()).thenReturn(completableFuture);

        CompletableFuture<Void> executeFuture = requestExecutor.execute(context);
        assertThatThrownBy(executeFuture::join).hasCauseInstanceOf(IOException.class).hasRootCause(exception);
    }

    @ParameterizedTest
    @MethodSource("mappedExceptions")
    public void execute_AcquireConnectionFailure_shouldAlwaysBeInstanceOfIOException(Entry<Integer, Class<? extends Throwable>> entry) {
        int errorCode = entry.getKey();
        Class<? extends Throwable> ioExceptionSubclass = entry.getValue();

        CrtAsyncRequestContext context = crtAsyncRequestContext();
        HttpException exception = new HttpException(errorCode);
        CompletableFuture<HttpClientConnection> completableFuture = CompletableFutureUtils.failedFuture(exception);

        Mockito.when(connectionManager.acquireConnection()).thenReturn(completableFuture);

        CompletableFuture<Void> executeFuture = requestExecutor.execute(context);
        assertThatThrownBy(executeFuture::join).hasCauseInstanceOf(IOException.class).hasMessageContaining(exception.getMessage());
        assertThatThrownBy(executeFuture::join).hasCauseInstanceOf(ioExceptionSubclass);
    }

    @Test
    public void executeRequest_failedOfIllegalStateException_shouldWrapIOException() {
        IllegalStateException exception = new IllegalStateException("connection closed");
        CrtAsyncRequestContext context = crtAsyncRequestContext();
        CompletableFuture<HttpClientConnection> completableFuture = new CompletableFuture<>();

        Mockito.when(connectionManager.acquireConnection()).thenReturn(completableFuture);
        completableFuture.complete(httpClientConnection);

        Mockito.when(httpClientConnection.makeRequest(Mockito.any(HttpRequest.class), Mockito.any(CrtResponseAdapter.class)))
               .thenThrow(exception);

        CompletableFuture<Void> executeFuture = requestExecutor.execute(context);

        ArgumentCaptor<Exception> argumentCaptor = ArgumentCaptor.forClass(Exception.class);
        Mockito.verify(responseHandler).onError(argumentCaptor.capture());

        Exception actualException = argumentCaptor.getValue();
        assertThat(actualException).hasMessageContaining("An exception occurred when making the request").hasCause(exception);
        assertThatThrownBy(executeFuture::join).hasCauseInstanceOf(IOException.class).hasRootCause(exception);
    }

    @Test
    public void executeRequest_failedOfRetryableHttpException_shouldWrapIOException() {
        HttpException exception = new HttpException(0x080a); // AWS_ERROR_HTTP_CONNECTION_CLOSED
        CrtAsyncRequestContext context = crtAsyncRequestContext();
        CompletableFuture<HttpClientConnection> completableFuture = new CompletableFuture<>();

        Mockito.when(connectionManager.acquireConnection()).thenReturn(completableFuture);
        completableFuture.complete(httpClientConnection);

        Mockito.when(httpClientConnection.makeRequest(Mockito.any(HttpRequest.class), Mockito.any(CrtResponseAdapter.class)))
               .thenThrow(exception);

        CompletableFuture<Void> executeFuture = requestExecutor.execute(context);

        ArgumentCaptor<Exception> argumentCaptor = ArgumentCaptor.forClass(Exception.class);
        Mockito.verify(responseHandler).onError(argumentCaptor.capture());

        Exception actualException = argumentCaptor.getValue();
        assertThat(actualException).hasCause(exception);
        assertThatThrownBy(executeFuture::join).hasCauseInstanceOf(IOException.class).hasRootCause(exception);
    }

    @Test
    public void executeRequest_failedOfNonRetryableHttpException_shouldNotWrapIOException() {
        HttpException exception = new HttpException(0x0801); // AWS_ERROR_HTTP_HEADER_NOT_FOUND
        CrtAsyncRequestContext context = crtAsyncRequestContext();
        CompletableFuture<HttpClientConnection> completableFuture = new CompletableFuture<>();

        Mockito.when(connectionManager.acquireConnection()).thenReturn(completableFuture);
        completableFuture.complete(httpClientConnection);

        Mockito.when(httpClientConnection.makeRequest(Mockito.any(HttpRequest.class), Mockito.any(CrtResponseAdapter.class)))
               .thenThrow(exception);

        CompletableFuture<Void> executeFuture = requestExecutor.execute(context);

        ArgumentCaptor<Exception> argumentCaptor = ArgumentCaptor.forClass(Exception.class);
        Mockito.verify(responseHandler).onError(argumentCaptor.capture());

        Exception actualException = argumentCaptor.getValue();
        assertThat(actualException).isEqualTo(exception);
        assertThatThrownBy(executeFuture::join).hasCause(exception);
    }

    private CrtAsyncRequestContext crtAsyncRequestContext() {
        SdkHttpFullRequest request = createRequest(URI.create("http://localhost"));
        return CrtAsyncRequestContext.builder()
                                     .readBufferSize(2000)
                                     .crtConnPool(connectionManager)
                                     .request(AsyncExecuteRequest.builder()
                                                            .request(request)
                                                            .requestContentPublisher(createProvider(""))
                                                            .responseHandler(responseHandler)
                                                            .build())
                                     .build();
    }
}
