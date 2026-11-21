package dev.gawio.platform.core;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;

import java.util.concurrent.atomic.AtomicInteger;

public class HelloVerticle extends VerticleBase {
    static final AtomicInteger STARTED = new AtomicInteger();

    @Override
    public Future<?> start() throws Exception {
        STARTED.incrementAndGet();
        return Future.succeededFuture();
    }
}
