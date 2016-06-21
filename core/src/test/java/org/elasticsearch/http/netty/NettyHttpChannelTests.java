/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.http.netty;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelId;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelProgressivePromise;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.MockBigArrays;
import org.elasticsearch.http.HttpTransportSettings;
import org.elasticsearch.http.netty.cors.CorsHandler;
import org.elasticsearch.indices.breaker.NoneCircuitBreakerService;
import org.elasticsearch.rest.RestResponse;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.junit.After;
import org.junit.Before;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;

import static org.elasticsearch.http.HttpTransportSettings.SETTING_CORS_ALLOW_CREDENTIALS;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_CORS_ALLOW_METHODS;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_CORS_ALLOW_ORIGIN;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_CORS_ENABLED;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

public class NettyHttpChannelTests extends ESTestCase {

    private NetworkService networkService;
    private ThreadPool threadPool;
    private MockBigArrays bigArrays;
    private NettyHttpServerTransport httpServerTransport;

    @Before
    public void setup() throws Exception {
        networkService = new NetworkService(Settings.EMPTY);
        threadPool = new TestThreadPool("test");
        bigArrays = new MockBigArrays(Settings.EMPTY, new NoneCircuitBreakerService());
    }

    @After
    public void shutdown() throws Exception {
        if (threadPool != null) {
            threadPool.shutdownNow();
        }
        if (httpServerTransport != null) {
            httpServerTransport.close();
        }
    }

    public void testCorsEnabledWithoutAllowOrigins() {
        // Set up a HTTP transport with only the CORS enabled setting
        Settings settings = Settings.builder()
                .put(HttpTransportSettings.SETTING_CORS_ENABLED.getKey(), true)
                .build();
        HttpResponse response = execRequestWithCors(settings, "remote-host", "request-host");
        // inspect response and validate
        assertThat(response.headers().get(HttpHeaders.Names.ACCESS_CONTROL_ALLOW_ORIGIN), nullValue());
    }

    public void testCorsEnabledWithAllowOrigins() {
        final String originValue = "remote-host";
        // create a http transport with CORS enabled and allow origin configured
        Settings settings = Settings.builder()
                .put(SETTING_CORS_ENABLED.getKey(), true)
                .put(SETTING_CORS_ALLOW_ORIGIN.getKey(), originValue)
                .build();
        HttpResponse response = execRequestWithCors(settings, originValue, "request-host");
        // inspect response and validate
        assertThat(response.headers().get(HttpHeaders.Names.ACCESS_CONTROL_ALLOW_ORIGIN), notNullValue());
        String allowedOrigins = response.headers().get(HttpHeaders.Names.ACCESS_CONTROL_ALLOW_ORIGIN);
        assertThat(allowedOrigins, is(originValue));
    }

    public void testCorsAllowOriginWithSameHost() {
        String originValue = "remote-host";
        String host = "remote-host";
        // create a http transport with CORS enabled
        Settings settings = Settings.builder()
                                .put(SETTING_CORS_ENABLED.getKey(), true)
                                .build();
        HttpResponse response = execRequestWithCors(settings, originValue, host);
        // inspect response and validate
        assertThat(response.headers().get(HttpHeaders.Names.ACCESS_CONTROL_ALLOW_ORIGIN), notNullValue());
        String allowedOrigins = response.headers().get(HttpHeaders.Names.ACCESS_CONTROL_ALLOW_ORIGIN);
        assertThat(allowedOrigins, is(originValue));

        originValue = "http://" + originValue;
        response = execRequestWithCors(settings, originValue, host);
        assertThat(response.headers().get(HttpHeaders.Names.ACCESS_CONTROL_ALLOW_ORIGIN), notNullValue());
        allowedOrigins = response.headers().get(HttpHeaders.Names.ACCESS_CONTROL_ALLOW_ORIGIN);
        assertThat(allowedOrigins, is(originValue));

        originValue = originValue + ":5555";
        host = host + ":5555";
        response = execRequestWithCors(settings, originValue, host);
        assertThat(response.headers().get(HttpHeaders.Names.ACCESS_CONTROL_ALLOW_ORIGIN), notNullValue());
        allowedOrigins = response.headers().get(HttpHeaders.Names.ACCESS_CONTROL_ALLOW_ORIGIN);
        assertThat(allowedOrigins, is(originValue));

        originValue = originValue.replace("http", "https");
        response = execRequestWithCors(settings, originValue, host);
        assertThat(response.headers().get(HttpHeaders.Names.ACCESS_CONTROL_ALLOW_ORIGIN), notNullValue());
        allowedOrigins = response.headers().get(HttpHeaders.Names.ACCESS_CONTROL_ALLOW_ORIGIN);
        assertThat(allowedOrigins, is(originValue));
    }

    public void testThatStringLiteralWorksOnMatch() {
        final String originValue = "remote-host";
        Settings settings = Settings.builder()
                                .put(SETTING_CORS_ENABLED.getKey(), true)
                                .put(SETTING_CORS_ALLOW_ORIGIN.getKey(), originValue)
                                .put(SETTING_CORS_ALLOW_METHODS.getKey(), "get, options, post")
                                .put(SETTING_CORS_ALLOW_CREDENTIALS.getKey(), true)
                                .build();
        HttpResponse response = execRequestWithCors(settings, originValue, "request-host");
        // inspect response and validate
        assertThat(response.headers().get(HttpHeaders.Names.ACCESS_CONTROL_ALLOW_ORIGIN), notNullValue());
        String allowedOrigins = response.headers().get(HttpHeaders.Names.ACCESS_CONTROL_ALLOW_ORIGIN);
        assertThat(allowedOrigins, is(originValue));
        assertThat(response.headers().get(HttpHeaders.Names.ACCESS_CONTROL_ALLOW_CREDENTIALS), equalTo("true"));
    }

    public void testThatAnyOriginWorks() {
        final String originValue = CorsHandler.ANY_ORIGIN;
        Settings settings = Settings.builder()
                                .put(SETTING_CORS_ENABLED.getKey(), true)
                                .put(SETTING_CORS_ALLOW_ORIGIN.getKey(), originValue)
                                .build();
        HttpResponse response = execRequestWithCors(settings, originValue, "request-host");
        // inspect response and validate
        assertThat(response.headers().get(HttpHeaders.Names.ACCESS_CONTROL_ALLOW_ORIGIN), notNullValue());
        String allowedOrigins = response.headers().get(HttpHeaders.Names.ACCESS_CONTROL_ALLOW_ORIGIN);
        assertThat(allowedOrigins, is(originValue));
        assertThat(response.headers().get(HttpHeaders.Names.ACCESS_CONTROL_ALLOW_CREDENTIALS), nullValue());
    }

    public void testHeadersSet() {
        Settings settings = Settings.builder().build();
        httpServerTransport = new NettyHttpServerTransport(settings, networkService, bigArrays, threadPool);
        FullHttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        httpRequest.headers().add(HttpHeaders.Names.ORIGIN, "remote");
        WriteCapturingChannel writeCapturingChannel = new WriteCapturingChannel();
        NettyHttpRequest request = new NettyHttpRequest(httpRequest, writeCapturingChannel);

        // send a response
        NettyHttpChannel channel = new NettyHttpChannel(httpServerTransport, request, null, randomBoolean());
        TestReponse resp = new TestReponse();
        final String customHeader = "custom-header";
        final String customHeaderValue = "xyz";
        resp.addHeader(customHeader, customHeaderValue);
        channel.sendResponse(resp);

        // inspect what was written
        List<Object> writtenObjects = writeCapturingChannel.getWrittenObjects();
        assertThat(writtenObjects.size(), is(1));
        HttpResponse response = (HttpResponse) writtenObjects.get(0);
        assertThat(response.headers().get("non-existent-header"), nullValue());
        assertThat(response.headers().get(customHeader), equalTo(customHeaderValue));
        assertThat(response.headers().get(HttpHeaders.Names.CONTENT_LENGTH), equalTo(Integer.toString(resp.content().length())));
        assertThat(response.headers().get(HttpHeaders.Names.CONTENT_TYPE), equalTo(resp.contentType()));
    }

    private HttpResponse execRequestWithCors(final Settings settings, final String originValue, final String host) {
        // construct request and send it over the transport layer
        httpServerTransport = new NettyHttpServerTransport(settings, networkService, bigArrays, threadPool);
        FullHttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        httpRequest.headers().add(HttpHeaders.Names.ORIGIN, originValue);
        httpRequest.headers().add(HttpHeaders.Names.HOST, host);
        WriteCapturingChannel writeCapturingChannel = new WriteCapturingChannel();
        NettyHttpRequest request = new NettyHttpRequest(httpRequest, writeCapturingChannel);

        NettyHttpChannel channel = new NettyHttpChannel(httpServerTransport, request, null, randomBoolean());
        channel.sendResponse(new TestReponse());

        // get the response
        List<Object> writtenObjects = writeCapturingChannel.getWrittenObjects();
        assertThat(writtenObjects.size(), is(1));
        return (HttpResponse) writtenObjects.get(0);
    }

    private static class WriteCapturingChannel implements Channel {

        private List<Object> writtenObjects = new ArrayList<>();

        public List<Object> getWrittenObjects() {
            return writtenObjects;
        }

        @Override
        public ChannelId id() {
            return null;
        }

        @Override
        public EventLoop eventLoop() {
            return null;
        }

        @Override
        public Channel parent() {
            return null;
        }

        @Override
        public ChannelConfig config() {
            return null;
        }

        @Override
        public boolean isOpen() {
            return false;
        }

        @Override
        public boolean isRegistered() {
            return false;
        }

        @Override
        public boolean isActive() {
            return false;
        }

        @Override
        public ChannelMetadata metadata() {
            return null;
        }

        @Override
        public SocketAddress localAddress() {
            return null;
        }

        @Override
        public SocketAddress remoteAddress() {
            return null;
        }

        @Override
        public ChannelFuture write(Object message) {
            writtenObjects.add(message);
            return null;
        }

        @Override
        public ChannelFuture write(Object message, ChannelPromise promise) {
            writtenObjects.add(message);
            return null;
        }

        @Override
        public ChannelFuture bind(SocketAddress localAddress) {
            return null;
        }

        @Override
        public ChannelFuture connect(SocketAddress remoteAddress) {
            return null;
        }

        @Override
        public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
            return null;
        }

        @Override
        public ChannelFuture disconnect() {
            return null;
        }

        @Override
        public ChannelFuture close() {
            return null;
        }

        @Override
        public ChannelFuture deregister() {
            return null;
        }

        @Override
        public ChannelFuture bind(SocketAddress localAddress, ChannelPromise promise) {
            return null;
        }

        @Override
        public ChannelFuture connect(SocketAddress remoteAddress, ChannelPromise promise) {
            return null;
        }

        @Override
        public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
            return null;
        }

        @Override
        public ChannelFuture disconnect(ChannelPromise promise) {
            return null;
        }

        @Override
        public ChannelFuture close(ChannelPromise promise) {
            return null;
        }

        @Override
        public ChannelFuture deregister(ChannelPromise promise) {
            return null;
        }

        @Override
        public ChannelFuture closeFuture() {
            return null;
        }

        @Override
        public boolean isWritable() {
            return false;
        }

        @Override
        public long bytesBeforeUnwritable() {
            return 0;
        }

        @Override
        public long bytesBeforeWritable() {
            return 0;
        }

        @Override
        public Unsafe unsafe() {
            return null;
        }

        @Override
        public ChannelPipeline pipeline() {
            return null;
        }

        @Override
        public ByteBufAllocator alloc() {
            return null;
        }

        @Override
        public Channel read() {
            return null;
        }

        @Override
        public Channel flush() {
            return null;
        }

        @Override
        public ChannelFuture writeAndFlush(Object msg, ChannelPromise promise) {
            return null;
        }

        @Override
        public ChannelFuture writeAndFlush(Object msg) {
            return null;
        }

        @Override
        public ChannelPromise newPromise() {
            return null;
        }

        @Override
        public ChannelProgressivePromise newProgressivePromise() {
            return null;
        }

        @Override
        public ChannelFuture newSucceededFuture() {
            return null;
        }

        @Override
        public ChannelFuture newFailedFuture(Throwable cause) {
            return null;
        }

        @Override
        public ChannelPromise voidPromise() {
            return null;
        }

        @Override
        public <T> Attribute<T> attr(AttributeKey<T> key) {
            return null;
        }

        @Override
        public <T> boolean hasAttr(AttributeKey<T> key) {
            return false;
        }

        @Override
        public int compareTo(Channel o) {
            return 0;
        }
    }

    private static class TestReponse extends RestResponse {

        @Override
        public String contentType() {
            return "text";
        }

        @Override
        public BytesReference content() {
            return BytesArray.EMPTY;
        }

        @Override
        public RestStatus status() {
            return RestStatus.OK;
        }
    }
}
