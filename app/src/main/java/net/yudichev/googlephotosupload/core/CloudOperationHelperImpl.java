package net.yudichev.googlephotosupload.core;

import net.yudichev.jiotty.common.lang.CompletableFutures;
import net.yudichev.jiotty.common.lang.Either;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkNotNull;

final class CloudOperationHelperImpl implements CloudOperationHelper {
    private static final Logger logger = LoggerFactory.getLogger(CloudOperationHelperImpl.class);
    private final BackingOffRemoteApiExceptionHandler backOffHandler;

    @Inject
    CloudOperationHelperImpl(BackingOffRemoteApiExceptionHandler backOffHandler) {
        this.backOffHandler = checkNotNull(backOffHandler);
    }

    @Override
    public <T> CompletableFuture<T> withBackOffAndRetry(String operationName, Supplier<CompletableFuture<T>> action, LongConsumer backoffEventConsumer) {
        return action.get()
                .thenApply(value -> {
                    backOffHandler.reset();
                    return Either.<T, RetryableFailure>left(value);
                })
                .exceptionally(exception -> {
                    var backoffDelayMs = backOffHandler.handle(operationName, exception);
                    return Either.right(RetryableFailure.of(exception, backoffDelayMs));
                })
                .thenCompose(eitherValueOrRetryableFailure -> eitherValueOrRetryableFailure.map(
                        CompletableFuture::completedFuture,
                        retryableFailure -> retryableFailure.backoffDelayMs()
                                .map(backoffDelayMs -> {
                                    logger.debug("Retrying operation '{}' with backoff {}ms", operationName, retryableFailure.backoffDelayMs());
                                    backoffEventConsumer.accept(backoffDelayMs);
                                    return withBackOffAndRetry(operationName, action, backoffEventConsumer);
                                })
                                .orElseGet(() -> CompletableFutures.failure(retryableFailure.exception()))
                ));
    }
}
