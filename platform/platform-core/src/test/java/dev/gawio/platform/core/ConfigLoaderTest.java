package dev.gawio.platform.core;

import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(VertxExtension.class)
class ConfigLoaderTest {

    @Test
    void loadsProfileConfig(Vertx vertx, VertxTestContext ctx) {
        ConfigLoader.load(vertx, "test")
                .onComplete(ctx.succeeding(json -> ctx.verify(() -> {
                    Assertions.assertEquals("payhub-test", json.getString("name"));
                    ctx.completeNow();
                })));
    }

    @Test
    void missingProfileFails(Vertx vertx, VertxTestContext ctx) {
        ConfigLoader.load(vertx, "nope")
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    Assertions.assertTrue(err.getMessage().contains("app-nope.json"),
                            "message should mention missing file, got: " + err.getMessage());
                    ctx.completeNow();
                })));
    }
}
