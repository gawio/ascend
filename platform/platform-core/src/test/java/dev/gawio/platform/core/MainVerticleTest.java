package dev.gawio.platform.core;

import com.google.inject.Guice;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

@ExtendWith(VertxExtension.class)
class MainVerticleTest {

    @BeforeEach
    void reset() {
        HelloVerticle.STARTED.set(0);
    }

    @Test
    void deploysOnlyEnableVerticles(Vertx vertx, VertxTestContext ctx) {
        var list = List.of(
                new VerticleDescriptor("hello", HelloVerticle.class.getName(), 1, true),
                new VerticleDescriptor("skip", BrokenVerticle.class.getName(), 1, false)
        );

        vertx.deployVerticle(new MainVerticle(Guice.createInjector(), list))
                .onComplete(ctx.succeeding(_ -> ctx.verify(() -> {
                    Assertions.assertEquals(1, HelloVerticle.STARTED.get());
                    ctx.completeNow();
                })));
    }

    @Test
    void failsFastWhenAVerticleFails(Vertx vertx, VertxTestContext ctx) {
        var list = List.of(
                new VerticleDescriptor("broken", BrokenVerticle.class.getName(), 1, true),
                new VerticleDescriptor("hello", HelloVerticle.class.getName(), 1, true)
        );

        vertx.deployVerticle(new MainVerticle(Guice.createInjector(), list))
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    Assertions.assertEquals(0, HelloVerticle.STARTED.get());
                    ctx.completeNow();
                })));
    }

    @Test
    void runsOneObjectPerInstance(Vertx vertx, VertxTestContext ctx) {
        VerticleDescriptor descriptor = new VerticleDescriptor("hello", HelloVerticle.class.getName(), 3, true);

        vertx.deployVerticle(new MainVerticle(Guice.createInjector(), List.of(descriptor)))
                .onComplete(ctx.succeeding(_ -> ctx.verify(() -> {
                    Assertions.assertEquals(3, HelloVerticle.STARTED.get());
                    ctx.completeNow();
                })));
    }
}
