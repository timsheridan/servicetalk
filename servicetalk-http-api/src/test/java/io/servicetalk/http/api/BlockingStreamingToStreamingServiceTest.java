/*
 * Copyright © 2018-2019 Apple Inc. and the ServiceTalk project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.servicetalk.http.api;

import io.servicetalk.buffer.api.Buffer;
import io.servicetalk.concurrent.Cancellable;
import io.servicetalk.concurrent.PublisherSource.Subscriber;
import io.servicetalk.concurrent.PublisherSource.Subscription;
import io.servicetalk.concurrent.SingleSource;
import io.servicetalk.concurrent.api.Executor;
import io.servicetalk.concurrent.api.ExecutorRule;
import io.servicetalk.concurrent.api.Publisher;
import io.servicetalk.concurrent.internal.ServiceTalkTestTimeout;
import io.servicetalk.http.api.HttpApiConversions.ServiceAdapterHolder;
import io.servicetalk.oio.api.PayloadWriter;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

import static io.servicetalk.buffer.netty.BufferAllocators.DEFAULT_ALLOCATOR;
import static io.servicetalk.concurrent.api.ExecutorRule.newRule;
import static io.servicetalk.concurrent.api.Publisher.failed;
import static io.servicetalk.concurrent.api.Publisher.from;
import static io.servicetalk.concurrent.api.SourceAdapters.toSource;
import static io.servicetalk.concurrent.internal.DeliberateException.DELIBERATE_EXCEPTION;
import static io.servicetalk.http.api.HttpApiConversions.toStreamingHttpService;
import static io.servicetalk.http.api.HttpHeaderNames.TRAILER;
import static io.servicetalk.http.api.HttpProtocolVersion.HTTP_1_1;
import static io.servicetalk.http.api.HttpResponseStatus.NO_CONTENT;
import static io.servicetalk.http.api.HttpResponseStatus.OK;
import static io.servicetalk.http.api.HttpSerializationProviders.textDeserializer;
import static io.servicetalk.http.api.HttpSerializationProviders.textSerializer;
import static io.servicetalk.utils.internal.PlatformDependent.throwException;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class BlockingStreamingToStreamingServiceTest {

    private static final String X_TOTAL_LENGTH = "x-total-length";
    private static final String HELLO_WORLD = "Hello\nWorld\n";

    @Rule
    public final Timeout timeout = new ServiceTalkTestTimeout();

    @Rule
    public final ExecutorRule<Executor> executorRule = newRule();

    @Mock
    private HttpExecutionContext mockExecutionCtx;

    private final StreamingHttpRequestResponseFactory reqRespFactory = new DefaultStreamingHttpRequestResponseFactory(
            DEFAULT_ALLOCATOR, DefaultHttpHeadersFactory.INSTANCE, HTTP_1_1);
    private HttpServiceContext mockCtx;

    @Before
    public void setup() {
        when(mockExecutionCtx.bufferAllocator()).thenReturn(DEFAULT_ALLOCATOR);

        mockCtx = new TestHttpServiceContext(DefaultHttpHeadersFactory.INSTANCE, reqRespFactory, mockExecutionCtx);
    }

    @Test
    public void defaultResponseStatusNoPayload() throws Exception {
        BlockingStreamingHttpService syncService = (ctx, request, response) -> response.sendMetaData().close();

        List<Object> response = invokeService(syncService, reqRespFactory.get("/"));
        assertMetaData(OK, response);
        assertPayloadBody("", response);
        assertEmptyTrailers(response);
    }

    @Test
    public void customResponseStatusNoPayload() throws Exception {
        BlockingStreamingHttpService syncService = (ctx, request, response) ->
                response.status(NO_CONTENT).sendMetaData().close();

        List<Object> response = invokeService(syncService, reqRespFactory.get("/"));
        assertMetaData(NO_CONTENT, response);
        assertPayloadBody("", response);
        assertEmptyTrailers(response);
    }

    @Test
    public void receivePayloadBody() throws Exception {
        StringBuilder receivedPayload = new StringBuilder();
        BlockingStreamingHttpService syncService = (ctx, request, response) -> {
            request.payloadBody().forEach(chunk -> receivedPayload.append(chunk.toString(US_ASCII)));
            response.sendMetaData().close();
        };

        List<Object> response = invokeService(syncService, reqRespFactory.post("/")
                .payloadBody(from("Hello\n", "World\n"), textSerializer()));
        assertMetaData(OK, response);
        assertPayloadBody("", response);
        assertEmptyTrailers(response);

        assertThat(receivedPayload.toString(), is(HELLO_WORLD));
    }

    @Test
    public void respondWithPayloadBody() throws Exception {
        BlockingStreamingHttpService syncService = (ctx, request, response) -> {
            try (PayloadWriter<Buffer> pw = response.sendMetaData()) {
                pw.write(ctx.executionContext().bufferAllocator().fromAscii("Hello\n"));
                pw.write(ctx.executionContext().bufferAllocator().fromAscii("World\n"));
            }
        };

        List<Object> response = invokeService(syncService, reqRespFactory.get("/"));
        assertMetaData(OK, response);
        assertPayloadBody(HELLO_WORLD, response);
        assertEmptyTrailers(response);
    }

    @Test
    public void echoServiceUsingPayloadWriterWithTrailers() throws Exception {
        echoService((ctx, request, response) -> {
            response.setHeader(TRAILER, X_TOTAL_LENGTH);
            try (HttpPayloadWriter<Buffer> pw = response.sendMetaData()) {
                AtomicInteger totalLength = new AtomicInteger();
                request.payloadBody().forEach(chunk -> {
                    try {
                        totalLength.addAndGet(chunk.readableBytes());
                        pw.write(chunk);
                    } catch (IOException e) {
                        throwException(e);
                    }
                });
                pw.setTrailer(X_TOTAL_LENGTH, totalLength.toString());
            }
        });
    }

    @Test
    public void echoServiceUsingPayloadWriterWithSerializerWithTrailers() throws Exception {
        echoService((ctx, request, response) -> {
            response.setHeader(TRAILER, X_TOTAL_LENGTH);
            try (HttpPayloadWriter<String> pw = response.sendMetaData(textSerializer())) {
                AtomicInteger totalLength = new AtomicInteger();
                request.payloadBody(textDeserializer()).forEach(chunk -> {
                    try {
                        totalLength.addAndGet(chunk.length());
                        pw.write(chunk);
                    } catch (IOException e) {
                        throwException(e);
                    }
                });
                pw.setTrailer(X_TOTAL_LENGTH, totalLength.toString());
            }
        });
    }

    @Test
    public void echoServiceUsingInputOutputStreamWithTrailers() throws Exception {
        echoService((ctx, request, response) -> {
            response.setHeader(TRAILER, X_TOTAL_LENGTH);
            try (HttpOutputStream out = response.sendMetaDataOutputStream();
                 InputStream in = request.payloadBodyInputStream()) {
                AtomicInteger totalLength = new AtomicInteger();
                int ch;
                while ((ch = in.read()) != -1) {
                    totalLength.incrementAndGet();
                    out.write(ch);
                }
                out.setTrailer(X_TOTAL_LENGTH, totalLength.toString());
            }
        });
    }

    private void echoService(BlockingStreamingHttpService syncService) throws Exception {
        List<Object> response = invokeService(syncService, reqRespFactory.post("/")
                .payloadBody(from("Hello\n", "World\n"), textSerializer()));
        assertMetaData(OK, response);
        assertHeader(TRAILER, X_TOTAL_LENGTH, response);
        assertPayloadBody(HELLO_WORLD, response);
        assertTrailer(X_TOTAL_LENGTH, String.valueOf(HELLO_WORLD.length()), response);
    }

    @Test
    public void closeAsync() throws Exception {
        final AtomicBoolean closedCalled = new AtomicBoolean();
        BlockingStreamingHttpService syncService = new BlockingStreamingHttpService() {
            @Override
            public void handle(final HttpServiceContext ctx,
                               final BlockingStreamingHttpRequest request,
                               final BlockingStreamingHttpServerResponse response) {
                throw new IllegalStateException("shouldn't be called!");
            }

            @Override
            public void close() {
                closedCalled.set(true);
            }
        };
        StreamingHttpService asyncService = toStreamingHttpService(syncService, strategy -> strategy).adaptor();
        asyncService.closeAsync().toFuture().get();
        assertThat(closedCalled.get(), is(true));
    }

    @Test
    public void cancelBeforeSendMetaDataPropagated() throws Exception {
        CountDownLatch handleLatch = new CountDownLatch(1);
        AtomicReference<Cancellable> cancellableRef = new AtomicReference<>();
        CountDownLatch onErrorLatch = new CountDownLatch(1);
        AtomicReference<Throwable> throwableRef = new AtomicReference<>();

        BlockingStreamingHttpService syncService = (ctx, request, response) -> {
            handleLatch.countDown();
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (Throwable t) {
                throwableRef.set(t);
                onErrorLatch.countDown();
            }
        };
        StreamingHttpService asyncService = toStreamingHttpService(syncService, strategy -> strategy).adaptor();
        toSource(asyncService.handle(mockCtx, reqRespFactory.get("/"), reqRespFactory)
                // Use subscribeOn(Executor) instead of HttpExecutionStrategy#invokeService which returns a flatten
                // Publisher<Object> to verify that cancellation of Single<StreamingHttpResponse> interrupts the thread
                // of handle method
                .subscribeOn(executorRule.executor()))
                .subscribe(new SingleSource.Subscriber<StreamingHttpResponse>() {

                    @Override
                    public void onSubscribe(final Cancellable cancellable) {
                        cancellableRef.set(cancellable);
                    }

                    @Override
                    public void onSuccess(@Nullable final StreamingHttpResponse result) {
                    }

                    @Override
                    public void onError(final Throwable t) {
                    }
                });
        handleLatch.await();
        Cancellable cancellable = cancellableRef.get();
        assertThat(cancellable, is(notNullValue()));
        cancellable.cancel();
        onErrorLatch.await();
        assertThat(throwableRef.get(), instanceOf(InterruptedException.class));
    }

    @Test
    public void cancelAfterSendMetaDataPropagated() throws Exception {
        CountDownLatch cancelLatch = new CountDownLatch(1);
        CountDownLatch serviceTerminationLatch = new CountDownLatch(1);
        CountDownLatch onErrorLatch = new CountDownLatch(1);
        AtomicReference<Throwable> throwableRef = new AtomicReference<>();

        BlockingStreamingHttpService syncService = (ctx, request, response) -> {
            response.sendMetaData();
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (Throwable t) {
                throwableRef.set(t);
                onErrorLatch.countDown();
            } finally {
                serviceTerminationLatch.countDown();
            }
        };
        StreamingHttpService asyncService = toStreamingHttpService(syncService, strategy -> strategy).adaptor();
        StreamingHttpResponse asyncResponse = asyncService.handle(mockCtx, reqRespFactory.get("/"), reqRespFactory)
                // Use subscribeOn(Executor) instead of HttpExecutionStrategy#invokeService which returns a flatten
                // Publisher<Object> to verify that cancellation of Publisher<Buffer> interrupts the thread of handle
                // method
                .subscribeOn(executorRule.executor()).toFuture().get();
        assertMetaData(OK, asyncResponse);
        toSource(asyncResponse.payloadBody()).subscribe(new Subscriber<Buffer>() {
            @Override
            public void onSubscribe(final Subscription s) {
                s.cancel();
                cancelLatch.countDown();
            }

            @Override
            public void onNext(final Buffer s) {
            }

            @Override
            public void onError(final Throwable t) {
            }

            @Override
            public void onComplete() {
            }
        });
        cancelLatch.await();
        onErrorLatch.await();
        assertThat(throwableRef.get(), instanceOf(InterruptedException.class));
        serviceTerminationLatch.await();
    }

    @Test
    public void sendMetaDataTwice() throws Exception {
        BlockingStreamingHttpService syncService = (ctx, request, response) -> {
            response.sendMetaData();
            response.sendMetaData();
        };

        try {
            invokeService(syncService, reqRespFactory.get("/"));
            fail("Payload body should complete with an error");
        } catch (ExecutionException e) {
            assertThat(e.getCause(), instanceOf(IllegalStateException.class));
        }
    }

    @Test
    public void modifyMetaDataAfterSend() throws Exception {
        BlockingStreamingHttpService syncService = (ctx, request, response) -> {
            response.sendMetaData();
            response.status(NO_CONTENT);
        };

        try {
            invokeService(syncService, reqRespFactory.get("/"));
            fail("Payload body should complete with an error");
        } catch (ExecutionException e) {
            assertThat(e.getCause(), instanceOf(IllegalStateException.class));
            assertThat(e.getCause().getMessage(), is("Response meta-data is already sent"));
        }
    }

    @Test
    public void throwBeforeSendMetaData() throws Exception {
        CountDownLatch onErrorLatch = new CountDownLatch(1);
        AtomicReference<Throwable> throwableRef = new AtomicReference<>();

        BlockingStreamingHttpService syncService = (ctx, request, response) -> {
            throw DELIBERATE_EXCEPTION;
        };
        StreamingHttpService asyncService = toStreamingHttpService(syncService, strategy -> strategy).adaptor();
        toSource(asyncService.handle(mockCtx, reqRespFactory.get("/"), reqRespFactory)
                // Use subscribeOn(Executor) instead of HttpExecutionStrategy#invokeService which returns a flatten
                // Publisher<Object> to verify that the Single<StreamingHttpResponse> of response meta-data terminates
                // with an error
                .subscribeOn(executorRule.executor()))
                .subscribe(new SingleSource.Subscriber<StreamingHttpResponse>() {

                    @Override
                    public void onSubscribe(final Cancellable cancellable) {
                    }

                    @Override
                    public void onSuccess(@Nullable final StreamingHttpResponse result) {
                    }

                    @Override
                    public void onError(final Throwable t) {
                        throwableRef.set(t);
                        onErrorLatch.countDown();
                    }
                });
        onErrorLatch.await();
        assertThat(throwableRef.get(), is(DELIBERATE_EXCEPTION));
    }

    @Test
    public void throwAfterSendMetaData() throws Exception {
        CountDownLatch onErrorLatch = new CountDownLatch(1);
        AtomicReference<Throwable> throwableRef = new AtomicReference<>();

        BlockingStreamingHttpService syncService = (ctx, request, response) -> {
            response.sendMetaData();
            throw DELIBERATE_EXCEPTION;
        };
        StreamingHttpService asyncService = toStreamingHttpService(syncService, strategy -> strategy).adaptor();
        StreamingHttpResponse asyncResponse = asyncService.handle(mockCtx, reqRespFactory.get("/"), reqRespFactory)
                // Use subscribeOn(Executor) instead of HttpExecutionStrategy#invokeService which returns a flatten
                // Publisher<Object> to verify that the Publisher<Buffer> of payload body terminates with an error
                .subscribeOn(executorRule.executor()).toFuture().get();
        assertMetaData(OK, asyncResponse);
        toSource(asyncResponse.payloadBody()).subscribe(new Subscriber<Buffer>() {
            @Override
            public void onSubscribe(final Subscription s) {
            }

            @Override
            public void onNext(final Buffer s) {
            }

            @Override
            public void onError(final Throwable t) {
                throwableRef.set(t);
                onErrorLatch.countDown();
            }

            @Override
            public void onComplete() {
            }
        });
        onErrorLatch.await();
        assertThat(throwableRef.get(), is(DELIBERATE_EXCEPTION));
    }

    @Test
    public void throwAfterPayloadWriterClosed() {
        BlockingStreamingHttpService syncService = (ctx, request, response) -> {
            response.sendMetaData().close();
            throw DELIBERATE_EXCEPTION;
        };

        assertThat(assertThrows(ExecutionException.class, () -> invokeService(syncService, reqRespFactory.get("/")))
                        .getCause(), is(DELIBERATE_EXCEPTION));
    }

    private List<Object> invokeService(BlockingStreamingHttpService syncService,
                                       StreamingHttpRequest request) throws Exception {
        ServiceAdapterHolder holder = toStreamingHttpService(syncService, strategy -> strategy);

        Collection<Object> responseCollection = holder.serviceInvocationStrategy()
                .invokeService(executorRule.executor(), request,
                        req -> holder.adaptor().handle(mockCtx, req, reqRespFactory)
                                .flatMapPublisher(response -> Publisher.<Object>from(response)
                                        .concat(response.messageBody())), (t, e) -> failed(t))
                .toFuture().get();

        return new ArrayList<>(responseCollection);
    }

    private static void assertMetaData(HttpResponseStatus expectedStatus, HttpResponseMetaData metaData) {
        assertThat(metaData, is(notNullValue()));
        assertThat(metaData.version(), is(HTTP_1_1));
        assertThat(metaData.status(), is(expectedStatus));
    }

    private static void assertMetaData(HttpResponseStatus expectedStatus, List<Object> response) {
        HttpResponseMetaData metaData = (HttpResponseMetaData) response.get(0);
        assertThat(metaData.version(), is(HTTP_1_1));
        assertThat(metaData.status(), is(expectedStatus));
    }

    private static void assertHeader(CharSequence expectedHeader, CharSequence expectedValue, List<Object> response) {
        HttpResponseMetaData metaData = (HttpResponseMetaData) response.get(0);
        assertThat(metaData, is(notNullValue()));
        assertThat(metaData.headers().contains(expectedHeader, expectedValue), is(true));
    }

    private static void assertPayloadBody(String expectedPayloadBody, List<Object> response) {
        String payloadBody = response.stream()
                .filter(obj -> obj instanceof Buffer)
                .map(obj -> ((Buffer) obj).toString(US_ASCII))
                .collect(Collectors.joining());
        assertThat(payloadBody, is(expectedPayloadBody));
    }

    private static void assertEmptyTrailers(List<Object> response) {
        HttpHeaders trailers = (HttpHeaders) response.get(response.size() - 1);
        assertThat(trailers, is(notNullValue()));
        assertThat(trailers.isEmpty(), is(true));
    }

    private static void assertTrailer(CharSequence expectedTrailer, CharSequence expectedValue, List<Object> response) {
        Object lastItem = response.get(response.size() - 1);
        assertThat("Unexpected item in the flattened response.", lastItem, is(instanceOf(HttpHeaders.class)));
        HttpHeaders trailers = (HttpHeaders) lastItem;
        assertThat(trailers, is(notNullValue()));
        assertThat(trailers.contains(expectedTrailer, expectedValue), is(true));
    }
}
