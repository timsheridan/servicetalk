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
package io.servicetalk.concurrent.api.test;

import io.servicetalk.concurrent.SingleSource;
import io.servicetalk.concurrent.api.AsyncContext;
import io.servicetalk.concurrent.api.AsyncContextMap;
import io.servicetalk.concurrent.api.Completable;
import io.servicetalk.concurrent.api.Executor;
import io.servicetalk.concurrent.api.ExecutorRule;
import io.servicetalk.concurrent.internal.DeliberateException;

import org.junit.ClassRule;
import org.junit.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

import static io.servicetalk.concurrent.api.Processors.newSingleProcessor;
import static io.servicetalk.concurrent.api.Single.failed;
import static io.servicetalk.concurrent.api.Single.never;
import static io.servicetalk.concurrent.api.Single.succeeded;
import static io.servicetalk.concurrent.api.test.Verifiers.stepVerifier;
import static io.servicetalk.concurrent.api.test.Verifiers.stepVerifierForSource;
import static io.servicetalk.concurrent.internal.DeliberateException.DELIBERATE_EXCEPTION;
import static java.time.Duration.ofDays;
import static java.time.Duration.ofMillis;
import static java.time.Duration.ofNanos;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SingleStepVerifierTest {
    private static final AsyncContextMap.Key<Integer> ASYNC_KEY = AsyncContextMap.Key.newKey();
    @ClassRule
    public static final ExecutorRule<Executor> EXECUTOR_RULE = ExecutorRule.newRule();

    @Test
    public void expectCancellable() {
        stepVerifier(succeeded("foo"))
                .expectCancellable()
                .expectSuccess("foo")
                .verify();
    }

    @Test
    public void expectCancellableTimeout() {
        CountDownLatch latch = new CountDownLatch(1);
        try {
            verifyException(() -> stepVerifier(succeeded("foo").publishAndSubscribeOn(EXECUTOR_RULE.executor()))
                    .expectCancellableConsumed(cancellable -> {
                        try {
                            latch.await();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .expectSuccess("foo")
                    .verify(ofNanos(10)), "expectCancellableConsumed");
        } finally {
            latch.countDown();
        }
    }

    @Test
    public void onSuccessDuplicateVerify() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(2);
        StepVerifier verifier = stepVerifier(succeeded("foo"))
                .expectCancellableConsumed(cancellable -> {
                    assertNotNull(cancellable);
                    latch.countDown();
                })
                .expectSuccess("foo");
        verifier.verify();
        verifier.verify();
        assertTrue(latch.await(10, SECONDS));
    }

    @Test
    public void onSuccessIgnore() {
        stepVerifier(succeeded("foo"))
                .expectSuccess()
                .verify();
    }

    @Test
    public void onSuccessIgnoreFail() {
        verifyException(() -> stepVerifier(failed(DELIBERATE_EXCEPTION))
                .expectSuccess()
                .verify(), "expectSuccess");
    }

    @Test
    public void onSuccess() {
        assertNotNull(stepVerifier(succeeded("foo"))
                .expectSuccess("foo")
                .verify());
    }

    @Test
    public void onSuccessFail() {
        verifyException(() -> stepVerifier(succeeded("foo"))
                .expectSuccess("bar")
                .verify(), "expectSuccess");
    }

    @Test
    public void onSuccessPredicate() {
        stepVerifier(succeeded("foo"))
                .expectSuccessMatches("foo"::equals)
                .verify();
    }

    @Test
    public void onSuccessPredicateFail() {
        verifyException(() -> stepVerifier(succeeded("foo"))
                .expectSuccessMatches("bar"::equals)
                .verify(), "expectSuccessMatches");
    }

    @Test
    public void onSuccessConsumer() {
        stepVerifier(succeeded("foo"))
                .expectSuccessConsumed(t -> assertEquals("foo", t))
                .verify();
    }

    @Test
    public void onSuccessConsumerFail() {
        verifyException(() -> stepVerifier(succeeded("foo"))
                .expectSuccessConsumed(t -> assertEquals("bar", t))
                .verify(), "expectSuccessConsumed");
    }

    @Test
    public void onSuccessNull() {
        assertNotNull(stepVerifier(succeeded(null))
                .expectSuccess(null)
                .verify());
    }

    @Test
    public void onSuccessLargeTimeout() {
        assertNotNull(stepVerifier(succeeded("foo"))
                .expectSuccess("foo")
                .verify(ofDays(1)));
    }

    @Test
    public void onSuccessTimeout() {
        verifyException(() -> stepVerifier(never())
                .expectSuccess("foo")
                .verify(ofNanos(10)), "expectSuccess");
    }

    @Test
    public void onError() {
        stepVerifier(failed(DELIBERATE_EXCEPTION))
                .expectError()
                .verify();
    }

    @Test
    public void onErrorFail() {
        verifyException(() -> stepVerifier(succeeded("foo"))
                .expectError()
                .verify(), "expectError");
    }

    @Test
    public void onErrorClass() {
        stepVerifier(failed(DELIBERATE_EXCEPTION))
                .expectError(DeliberateException.class)
                .verify();
    }

    @Test
    public void onErrorClassFail() {
        verifyException(() -> stepVerifier(succeeded("foo"))
                .expectError(DeliberateException.class)
                .verify(), "expectError");
    }

    @Test
    public void onErrorPredicate() {
        stepVerifier(failed(DELIBERATE_EXCEPTION))
                .expectErrorMatches(error -> error instanceof DeliberateException)
                .verify();
    }

    @Test
    public void onErrorPredicateFail() {
        verifyException(() -> stepVerifier(succeeded("foo"))
                .expectErrorMatches(error -> error instanceof DeliberateException)
                .verify(), "expectErrorMatches");
    }

    @Test
    public void onErrorConsumer() {
        stepVerifier(failed(DELIBERATE_EXCEPTION))
                .expectErrorConsumed(error -> assertThat(error, is(DELIBERATE_EXCEPTION)))
                .verify();
    }

    @Test
    public void onErrorConsumerFail() {
        verifyException(() -> stepVerifier(succeeded("foo"))
                .expectErrorConsumed(error -> assertThat(error, is(DELIBERATE_EXCEPTION)))
                .verify(), "expectErrorConsumed");
    }

    @Test
    public void expectOnSuccessWhenOnError() {
        verifyException(() -> stepVerifier(failed(DELIBERATE_EXCEPTION))
                    .expectSuccess("foo")
                    .verify(), "expectSuccess");
    }

    @Test
    public void noSignalsSubscriptionCancelSucceeds() {
        // expectNoSignals and subscription event are dequeued/processed sequentially on the Subscriber thread
        // and the scenario isn't instructed to expect the subscription so we pass the test.
        stepVerifier(never())
                .expectNoSignals(ofDays(1))
                .thenCancel()
                .verify();
    }

    @Test
    public void noSignalsSuccessFail() {
        verifyException(() -> stepVerifier(succeeded("foo"))
                .expectCancellable()
                .expectNoSignals(ofDays(1))
                .expectSuccess("foo")
                .verify(), "expectNoSignals");
    }

    @Test
    public void noSignalsErrorFails() {
        verifyException(() -> stepVerifier(failed(DELIBERATE_EXCEPTION))
                .expectCancellable()
                .expectNoSignals(ofDays(1))
                .expectError(DeliberateException.class)
                .verify(), "expectNoSignals");
    }

    @Test
    public void noSignalsAfterSubscriptionSucceeds() {
        stepVerifier(never())
                .expectCancellable()
                .expectNoSignals(ofMillis(100))
                .thenCancel()
                .verify();
    }

    @Test
    public void thenCancelSucceeded() {
        stepVerifier(succeeded("foo"))
                .thenCancel()
                .verify();
    }

    @Test
    public void thenCancelFailed() {
        stepVerifier(Completable.failed(DELIBERATE_EXCEPTION))
                .thenCancel()
                .verify();
    }

    @Test
    public void thenRun() {
        SingleSource.Processor<String, String> processor = newSingleProcessor();
        stepVerifierForSource(processor)
                .then(() -> processor.onSuccess("foo"))
                .expectSuccess("foo")
                .verify();
    }

    @Test(expected = DeliberateException.class)
    public void thenRunThrows() {
        stepVerifier(succeeded("foo"))
                .then(() -> {
                    throw DELIBERATE_EXCEPTION;
                })
                .expectSuccess("foo")
                .verify();
    }

    @Test
    public void asyncContextOnSuccess() {
        stepVerifier(succeeded("foo").publishAndSubscribeOn(EXECUTOR_RULE.executor()))
                .expectCancellableConsumed(s -> {
                    assertNotNull(s);
                    AsyncContext.put(ASYNC_KEY, 10);
                })
                .expectSuccessConsumed(next -> {
                    assertEquals("foo", next);
                    assertThat(AsyncContext.get(ASYNC_KEY), is(10));
                })
                .verify();
    }

    @Test
    public void asyncContextOnError() {
        stepVerifier(failed(DELIBERATE_EXCEPTION).publishAndSubscribeOn(EXECUTOR_RULE.executor()))
                .expectCancellableConsumed(s -> {
                    assertNotNull(s);
                    AsyncContext.put(ASYNC_KEY, 10);
                })
                .expectErrorConsumed(error -> {
                    assertSame(DELIBERATE_EXCEPTION, error);
                    assertThat(AsyncContext.get(ASYNC_KEY), is(10));
                })
                .verify();
    }

    @Test
    public void thenAwaitRespectsDelaysComplete() {
        SingleSource.Processor<String, String> processor = newSingleProcessor();
        new InlineSingleFirstStep<>(processor, new DefaultModifiableTimeSource())
                .expectCancellable()
                .expectNoSignals(ofDays(500))
                .thenAwait(ofDays(1000))
                .then(() -> processor.onSuccess("foo"))
                .expectSuccess("foo")
                .verify();
    }

    @Test
    public void thenAwaitRespectsDelaysEqualsFail() {
        thenAwaitRespectsDelaysFail(true);
    }

    @Test
    public void thenAwaitRespectsDelaysGTFail() {
        thenAwaitRespectsDelaysFail(false);
    }

    private static void thenAwaitRespectsDelaysFail(boolean equals) {
        SingleSource.Processor<String, String> processor = newSingleProcessor();
        verifyException(() -> new InlineSingleFirstStep<>(processor, new DefaultModifiableTimeSource())
                .expectCancellable()
                .expectNoSignals(ofDays(equals ? 1000 : 1001))
                .thenAwait(ofDays(1000))
                .then(() -> processor.onSuccess("foo"))
                .expectSuccess("foo")
                .verify(), "expectNoSignals");
    }

    private static void verifyException(Supplier<Duration> verifier, String failedTestMethod) {
        PublisherStepVerifierTest.verifyException(verifier, SingleStepVerifierTest.class.getName(), failedTestMethod);
    }
}
