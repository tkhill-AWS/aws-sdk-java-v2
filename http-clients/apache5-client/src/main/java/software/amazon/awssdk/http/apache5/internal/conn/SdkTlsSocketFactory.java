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

package software.amazon.awssdk.http.apache5.internal.conn;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.TimeValue;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.http.apache5.internal.net.SdkSocket;
import software.amazon.awssdk.utils.Logger;

@SdkInternalApi
public class SdkTlsSocketFactory extends SSLConnectionSocketFactory {

    private static final Logger log = Logger.loggerFor(SdkTlsSocketFactory.class);

    public SdkTlsSocketFactory(SSLContext sslContext, HostnameVerifier hostnameVerifier) {
        super(sslContext, hostnameVerifier);
        if (sslContext == null) {
            throw new IllegalArgumentException(
                "sslContext must not be null. " + "Use SSLContext.getDefault() if you are unsure.");
        }
    }

    @Override
    protected final void prepareSocket(SSLSocket socket) {
        log.debug(() -> String.format("socket.getSupportedProtocols(): %s, socket.getEnabledProtocols(): %s",
                                      Arrays.toString(socket.getSupportedProtocols()),
                                      Arrays.toString(socket.getEnabledProtocols())));
    }

    @Override
        public Socket connectSocket(TimeValue connectTimeout,
            Socket socket,
            HttpHost host,
            InetSocketAddress remoteAddress,
            InetSocketAddress localAddress,
            HttpContext context) throws IOException {
        log.trace(() -> String.format("Connecting to %s:%s", remoteAddress.getAddress(), remoteAddress.getPort()));

        Socket connectSocket = super.connectSocket(connectTimeout, socket, host, remoteAddress, localAddress, context);
        return new SdkSocket(connectSocket);
    }
}
