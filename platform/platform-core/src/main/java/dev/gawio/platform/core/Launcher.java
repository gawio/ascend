package dev.gawio.platform.core;

import com.google.inject.Guice;
import com.google.inject.Module;
import com.google.inject.name.Names;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;

public final class Launcher {
    private Launcher() {
    }

    private static final System.Logger LOG = System.getLogger(Launcher.class.getName());

    public static void launch(String[] args, Function<JsonObject, List<Module>> modules) {
        run(args, modules)
                .onSuccess(vertx -> {
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> vertx.close().await()));
                    LOG.log(INFO, "started");
                })
                .onFailure(err -> {
                    LOG.log(ERROR, "startup failed: " + err.getMessage(), err);
                    System.exit(1);
                });
    }

    public static Future<Vertx> run(String[] args, Function<JsonObject, List<Module>> modules) {
        String profile = System.getenv().getOrDefault("APP_PROFILE", "local");
        Vertx vertx = Vertx.vertx();

        return ConfigLoader.load(vertx, profile)
                .compose(config -> vertx.fileSystem().readFile("verticle.json")
                        .map(buf -> {
                            List<Module> all = new ArrayList<>();
                            all.add(coreModule(vertx, config));
                            all.addAll(modules.apply(config));
                            return new MainVerticle(Guice.createInjector(all), VerticleDescriptor.parse(buf.toJsonArray()));
                        })
                        .compose(main -> vertx.deployVerticle(main, new DeploymentOptions().setConfig(config)))
                        .map(_ -> vertx)
                        .recover(err -> vertx.close().transform(ignored -> Future.failedFuture(err)))
                );
    }

    private static Module coreModule(Vertx vertx, JsonObject config) {
        return binder -> {
            binder.bind(Vertx.class).toInstance(vertx);
            binder.bind(JsonObject.class).annotatedWith(Names.named("config")).toInstance(config);
        };
    }

}
