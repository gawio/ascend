package dev.gawio.platform.core;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;

public class BrokenVerticle extends VerticleBase {
    @Override
    public Future<?> start() throws Exception {
        return Future.failedFuture("boom");
    }
}
