package dev.gawio.platform.core;

import com.google.inject.Injector;
import io.vertx.core.Deployable;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;

import java.util.List;

import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;

public class MainVerticle extends VerticleBase {
    private static final System.Logger LOG = System.getLogger(MainVerticle.class.getName());

    private final Injector injector;
    private final List<VerticleDescriptor> verticles;

    public MainVerticle(Injector injector, List<VerticleDescriptor> verticles) {
        this.injector = injector;
        this.verticles = verticles;
    }

    @Override
    public Future<?> start() {
        Future<Void> chain = Future.succeededFuture();
        for (VerticleDescriptor descriptor : verticles) {
            if (!descriptor.isDeploy()) {
                LOG.log(INFO, "skip {0} (isDeploy=false)", descriptor.name());
                continue;
            }
            chain = chain.compose(v -> deployOne(descriptor));
        }
        return chain;
    }

    private Future<Void> deployOne(VerticleDescriptor descriptor) {
        Class<? extends Deployable> clazz;
        try {
            clazz = Class.forName(descriptor.address()).asSubclass(Deployable.class);
        } catch (ClassNotFoundException | ClassCastException e) {
            return Future.failedFuture(new IllegalArgumentException("Bad verticle address: " + descriptor.address(), e));
        }
        var options = new DeploymentOptions().setInstances(descriptor.instances()).setConfig(config());
        return vertx.deployVerticle(() -> injector.getInstance(clazz), options)
                .onSuccess(_ -> LOG.log(INFO, "deploy {0} success x{1}", descriptor.name(), descriptor.instances()))
                .onFailure(e -> LOG.log(ERROR, "deploy {0} failed: {1}", descriptor.name(), e.getMessage()))
                .mapEmpty();
    }
}
