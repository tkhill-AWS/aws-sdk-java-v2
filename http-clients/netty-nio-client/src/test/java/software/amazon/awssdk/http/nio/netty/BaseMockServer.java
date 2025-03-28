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

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;

public class BaseMockServer {

    protected int httpPort;
    protected int httpsPort;

    public BaseMockServer() throws IOException {
        httpPort = getUnusedPort();
        httpsPort = getUnusedPort();
    }

    public URI getHttpUri() {
        return URI.create(String.format("http://localhost:%s", httpPort));
    }

    public URI getHttpsUri() {
        return URI.create(String.format("https://localhost:%s", httpsPort));
    }

    public static int getUnusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
