/*
 * Copyright © 2020 Apple Inc. and the ServiceTalk project authors
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
package io.servicetalk.transport.netty.internal;

import io.servicetalk.concurrent.PublisherSource;
import io.servicetalk.transport.netty.internal.CloseHandler.CloseEventObservedException;

import org.junit.Test;

import java.nio.channels.ClosedChannelException;

import static io.servicetalk.concurrent.api.Processors.newPublisherProcessor;
import static io.servicetalk.concurrent.api.SourceAdapters.fromSource;
import static io.servicetalk.concurrent.api.test.Verifiers.stepVerifier;
import static io.servicetalk.transport.netty.internal.CloseHandler.CloseEvent.CHANNEL_CLOSED_INBOUND;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

public class SslCloseNotifyAlertServerHandlingTest extends AbstractSslCloseNotifyAlertHandlingTest {

    public SslCloseNotifyAlertServerHandlingTest() throws Exception {
        super(false);
    }

    @Test
    public void afterExchangeIdleConnection() {
        receiveRequest();
        PublisherSource.Processor<String, String> writeSource = newPublisherProcessor();
        stepVerifier(conn.write(fromSource(writeSource)))
                .then(() -> {
                    writeMsg(writeSource, BEGIN);
                    writeMsg(writeSource, END);
                    closeNotifyAndVerifyClosing();
                })
                .expectErrorConsumed(cause -> {
                    assertThat("Unexpected write failure cause", cause, instanceOf(CloseEventObservedException.class));
                    CloseEventObservedException ceoe = (CloseEventObservedException) cause;
                    assertThat("Unexpected close event", ceoe.event(), is(CHANNEL_CLOSED_INBOUND));
                })
                .verify();
    }

    @Test
    public void afterRequestBeforeSendingResponse() {
        receiveRequest();

        PublisherSource.Processor<String, String> writeSource = newPublisherProcessor();
        stepVerifier(conn.write(fromSource(writeSource)))
                .then(this::closeNotifyAndVerifyClosing)
                .expectError(RetryableClosureException.class)
                .verify();
    }

    @Test
    public void afterRequestWhileSendingResponse() {
        receiveRequest();

        PublisherSource.Processor<String, String> writeSource = newPublisherProcessor();
        stepVerifier(conn.write(fromSource(writeSource)))
                .then(() -> {
                    writeMsg(writeSource, BEGIN);
                    closeNotifyAndVerifyClosing();
                })
                .expectError(ClosedChannelException.class)
                .verify();
    }

    @Test
    public void whileReadingRequestBeforeSendingResponse() {
        stepVerifier(conn.write(fromSource(newPublisherProcessor())).merge(conn.read()))
                .then(() -> {
                    // Start reading request
                    channel.writeInbound(BEGIN);
                    closeNotifyAndVerifyClosing();
                })
                .expectNext(BEGIN)
                .expectError(RetryableClosureException.class)
                .verify();
    }

    @Test
    public void whileReadingRequestAndSendingResponse() {
        PublisherSource.Processor<String, String> writeSource = newPublisherProcessor();
        stepVerifier(conn.write(fromSource(writeSource)).merge(conn.read()))
                .then(() -> {
                    // Start reading request
                    channel.writeInbound(BEGIN);
                    // Start writing response
                    writeMsg(writeSource, BEGIN);
                })
                .expectNext(BEGIN)
                .then(this::closeNotifyAndVerifyClosing)
                .expectError(ClosedChannelException.class)
                .verify();
    }

    @Test
    public void whileReadingRequestAfterSendingResponse() {
        PublisherSource.Processor<String, String> writeSource = newPublisherProcessor();
        stepVerifier(conn.write(fromSource(writeSource)).merge(conn.read()))
                .then(() -> {
                    // Start reading request
                    channel.writeInbound(BEGIN);
                    // Send response
                    writeMsg(writeSource, BEGIN);
                    writeMsg(writeSource, END);
                })
                .expectNext(BEGIN)
                .then(this::closeNotifyAndVerifyClosing)
                .expectError(ClosedChannelException.class)
                .verify();
    }

    private void receiveRequest() {
        stepVerifier(conn.read())
                .then(() -> channel.writeInbound(BEGIN))
                .expectNext(BEGIN)
                .then(() -> channel.writeInbound(END))
                .expectNext(END)
                .expectComplete()
                .verify();
    }
}
