package dev.gawio.platform.core;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;

public final class ConfigLoader {
    private ConfigLoader() {
    }

    public static Future<JsonObject> load(Vertx vertx, String profile) {
        String file = "app-" + profile + ".json";
        return vertx.fileSystem().readFile(file)
                .recover(_ -> Future.failedFuture("Config not found: " + file))
                .map(Buffer::toJsonObject);
    }
}
