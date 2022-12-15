package com.uid2.shared.vertx;

import com.uid2.shared.Const;
import com.uid2.shared.auth.ClientKey;
import com.uid2.shared.auth.OperatorKey;
import com.uid2.shared.middleware.AuthMiddleware;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.micrometer.Label;
import io.vertx.micrometer.MicrometerMetricsOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.EnumSet;

@ExtendWith(VertxExtension.class)
public class RequestCapturingHandlerTest {
    private static final int Port = 8080;
    private SimpleMeterRegistry registry;
    private Vertx vertx;

    @BeforeEach
    public void before() {
        registry = new SimpleMeterRegistry();
        vertx = Vertx.vertx(new VertxOptions()
                .setMetricsOptions(new MicrometerMetricsOptions()
                        .setLabels(EnumSet.of(Label.HTTP_METHOD, Label.HTTP_CODE, Label.HTTP_PATH))
                        .setMicrometerRegistry(registry)
                        .setEnabled(true)));
    }

    @AfterEach
    public void after(VertxTestContext testContext) {
        if (vertx != null) {
            vertx.close(testContext.succeeding(x -> {
                testContext.completeNow();
            }));
        } else {
            testContext.completeNow();
        }
    }

    private static final Handler<RoutingContext> dummyResponseHandler = routingContext -> {
        routingContext.response().setStatusCode(200).end();
    };

    public class TestVerticle extends AbstractVerticle {
        private final Router testRouter;

        public TestVerticle(Router testRouter) {
            this.testRouter = testRouter;
        }

        @Override
        public void start() {
            vertx.createHttpServer().requestHandler(this.testRouter).listen(Port);
        }
    }

    @Test
    public void captureSimplePath(VertxTestContext testContext) {
        Router router = Router.router(vertx);
        router.route().handler(new RequestCapturingHandler());
        router.get("/v1/token/generate").handler(dummyResponseHandler);

        vertx.deployVerticle(new TestVerticle(router), testContext.succeeding(id -> {
            WebClient client = WebClient.create(vertx);
            client.get(Port, "localhost", "/v1/token/generate?email=someemail").sendJsonObject(new JsonObject(), testContext.succeeding(response -> testContext.verify(() -> {
                Assertions.assertDoesNotThrow(() ->
                        registry
                                .get("uid2.http_requests")
                                .tag("status", "200")
                                .tag("method", "GET")
                                .tag("path", "/v1/token/generate")
                                .counter());

                testContext.completeNow();
            })));
        }));
    }

    @Test
    public void captureSubRouterPath(VertxTestContext testContext) {
        Router router = Router.router(vertx);
        router.route().handler(new RequestCapturingHandler());
        Router v2Router = Router.router(vertx);
        v2Router.post("/token/generate").handler(dummyResponseHandler);
        router.mountSubRouter("/v2", v2Router);

        vertx.deployVerticle(new TestVerticle(router), testContext.succeeding(id -> {
            WebClient client = WebClient.create(vertx);
            client.post(Port, "localhost", "/v2/token/generate").sendJsonObject(new JsonObject(), testContext.succeeding(response -> testContext.verify(() -> {
                Assertions.assertEquals(1,
                        registry
                                .get("uid2.http_requests")
                                .tag("status", "200")
                                .tag("method", "POST")
                                .tag("path", "/v2/token/generate")
                                .counter().count());

                testContext.completeNow();
            })));
        }));
    }

    @Test
    public void captureStaticPath(VertxTestContext testContext) {
        Router router = Router.router(vertx);
        router.route().handler(new RequestCapturingHandler());
        router.get("/static/*").handler(dummyResponseHandler);

        vertx.deployVerticle(new TestVerticle(router), testContext.succeeding(id -> {
            WebClient client = WebClient.create(vertx);
            client.get(Port, "localhost", "/static/content").sendJsonObject(new JsonObject(), testContext.succeeding(response -> testContext.verify(() -> {
                Assertions.assertDoesNotThrow(() ->
                        registry
                                .get("uid2.http_requests")
                                .tag("status", "200")
                                .tag("method", "GET")
                                .tag("path", "/static/content")
                                .counter());

                testContext.completeNow();
            })));
        }));
    }

    @Test
    public void captureUnknownPath(VertxTestContext testContext) {
        Router router = Router.router(vertx);
        router.route().handler(new RequestCapturingHandler());

        vertx.deployVerticle(new TestVerticle(router), testContext.succeeding(id -> {
            WebClient client = WebClient.create(vertx);
            client.get(Port, "localhost", "/randomPath").sendJsonObject(new JsonObject(), testContext.succeeding(response -> testContext.verify(() -> {
                Assertions.assertDoesNotThrow(() ->
                        registry
                                .get("uid2.http_requests")
                                .tag("status", "404")
                                .tag("method", "GET")
                                .tag("path", "unknown")
                                .counter());

                testContext.completeNow();
            })));
        }));
    }

    @Test
    public void getSiteIdFromRoutingContextData(VertxTestContext testContext) {
        final int siteId = 123;
        Router router = Router.router(vertx);
        router.route().handler(new RequestCapturingHandler());
        router.get("/test").handler(ctx -> {
            ctx.put(Const.RoutingContextData.SiteId, siteId);
            ctx.response().end();
        });

        vertx.deployVerticle(new TestVerticle(router), testContext.succeeding(id -> {
            WebClient client = WebClient.create(vertx);
            client.get(Port, "localhost", "/test")
                    .send(testContext.succeeding(response -> testContext.verify(() -> {
                        final double actual = registry
                                .get("uid2.http_requests")
                                .tag("site_id", String.valueOf(siteId))
                                .counter()
                                .count();
                        Assertions.assertEquals(1, actual);
                        testContext.completeNow();
                    })));
        }));
    }

    @Test
    public void getSiteIdFromClientKey(VertxTestContext testContext) {
        final int siteId = 123;
        Router router = Router.router(vertx);
        router.route().handler(new RequestCapturingHandler());
        router.get("/test").handler(ctx -> {
            ctx.put(AuthMiddleware.API_CLIENT_PROP, new ClientKey("key", "secret").withSiteId(siteId));
            ctx.response().end();
        });

        vertx.deployVerticle(new TestVerticle(router), testContext.succeeding(id -> {
            WebClient client = WebClient.create(vertx);
            client.get(Port, "localhost", "/test")
                    .send(testContext.succeeding(response -> testContext.verify(() -> {
                        final double actual = registry
                                .get("uid2.http_requests")
                                .tag("site_id", String.valueOf(siteId))
                                .counter()
                                .count();
                        Assertions.assertEquals(1, actual);
                        testContext.completeNow();
                    })));
        }));
    }

    @Test
    public void getSiteIdFromOperatorKey(VertxTestContext testContext) {
        final int siteId = 123;
        final Router router = Router.router(vertx);
        router.route().handler(new RequestCapturingHandler());
        router.get("/test").handler(ctx -> {
            ctx.put(AuthMiddleware.API_CLIENT_PROP, new OperatorKey("key", "name", "contact", "protocol", 0, false, siteId));
            ctx.response().end();
        });

        vertx.deployVerticle(new TestVerticle(router), testContext.succeeding(id -> {
            WebClient client = WebClient.create(vertx);
            client.get(Port, "localhost", "/test")
                    .send(testContext.succeeding(response -> testContext.verify(() -> {
                        final double actual = registry
                                .get("uid2.http_requests")
                                .tag("site_id", String.valueOf(siteId))
                                .counter()
                                .count();
                        Assertions.assertEquals(1, actual);
                        testContext.completeNow();
                    })));
        }));
    }

    @Test
    public void noSiteId(Vertx vertx, VertxTestContext testContext) {
        final Router router = Router.router(vertx);
        router.route().handler(new RequestCapturingHandler());
        router.get("/test").handler(ctx -> {
            ctx.response().end();
        });

        vertx.deployVerticle(new TestVerticle(router), testContext.succeeding(id -> {
            WebClient client = WebClient.create(vertx);
            client.get(Port, "localhost", "/test")
                    .send(testContext.succeeding(response -> testContext.verify(() -> {
                        final double actual = registry
                                .get("uid2.http_requests")
                                .tag("site_id", "null")
                                .counter()
                                .count();
                        Assertions.assertEquals(1, actual);
                        testContext.completeNow();
                    })));
        }));
    }
}
