package org.reactivecommons.async.kafka.apicurio;

import com.networknt.schema.JsonSchema;
import io.apicurio.registry.resolver.SchemaResolver;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@SuppressWarnings("unchecked")
class SharedSchemaResolversTest {

    private final AtomicInteger created = new AtomicInteger();
    private final SharedSchemaResolvers resolvers =
            new SharedSchemaResolvers(config -> {
                created.incrementAndGet();
                return mock(SchemaResolver.class);
            });

    private Map<String, Object> registry(String url) {
        Map<String, Object> config = new HashMap<>();
        config.put("apicurio.registry.url", url);
        return config;
    }

    @Test
    void shouldCreateOneResolverOnFirstUse() {
        SchemaResolver<JsonSchema, Object> resolver = resolvers.forRegistry(registry("http://registry:8080"));

        assertThat(resolver).isNotNull();
        assertThat(resolvers.count()).isOne();
        assertThat(created).hasValue(1);
    }

    @Test
    void shouldReuseTheResolverOfTheSameRegistry() {
        SchemaResolver<JsonSchema, Object> first = resolvers.forRegistry(registry("http://registry:8080"));
        SchemaResolver<JsonSchema, Object> second = resolvers.forRegistry(registry("http://registry:8080"));

        assertThat(second).isSameAs(first);
        assertThat(resolvers.count()).isOne();
        assertThat(created).hasValue(1);
    }

    @Test
    void shouldCreateADistinctResolverPerRegistryConfiguration() {
        resolvers.forRegistry(registry("http://registry-a:8080"));
        resolvers.forRegistry(registry("http://registry-b:8080"));

        assertThat(resolvers.count()).isEqualTo(2);
    }

    @Test
    void shouldTellApartRegistriesThatOnlyDifferInCredentials() {
        Map<String, Object> app = registry("http://registry:8080");
        app.put("apicurio.registry.auth.client.id", "app-client");
        Map<String, Object> accounts = registry("http://registry:8080");
        accounts.put("apicurio.registry.auth.client.id", "accounts-client");

        resolvers.forRegistry(app);
        resolvers.forRegistry(accounts);

        assertThat(resolvers.count()).isEqualTo(2);
    }

    @Test
    void shouldNotLetALaterChangeOfTheCallersMapCorruptTheLookup() {
        Map<String, Object> config = registry("http://registry:8080");
        SchemaResolver<JsonSchema, Object> first = resolvers.forRegistry(config);

        config.put("apicurio.registry.auth.client.id", "added-afterwards");

        // The key was copied when the entry was stored, so the original configuration still finds its resolver
        assertThat(resolvers.forRegistry(registry("http://registry:8080"))).isSameAs(first);
        assertThat(resolvers.count()).isOne();
    }

    @Test
    void shouldReleaseEveryResolverItCreated() throws Exception {
        SchemaResolver<JsonSchema, Object> first = mock(SchemaResolver.class);
        SchemaResolver<JsonSchema, Object> second = mock(SchemaResolver.class);
        var owned = new SharedSchemaResolvers(fixed(first, second));
        owned.forRegistry(registry("http://registry-a:8080"));
        owned.forRegistry(registry("http://registry-b:8080"));

        owned.close();

        verify(first).close();
        verify(second).close();
    }

    @Test
    void shouldKeepReleasingTheRemainingResolversWhenOneFails() throws Exception {
        SchemaResolver<JsonSchema, Object> failing = mock(SchemaResolver.class);
        doThrow(new IOException("boom")).when(failing).close();
        SchemaResolver<JsonSchema, Object> healthy = mock(SchemaResolver.class);
        SharedSchemaResolvers owned = new SharedSchemaResolvers(fixed(failing, healthy));
        owned.forRegistry(registry("http://registry-a:8080"));
        owned.forRegistry(registry("http://registry-b:8080"));

        assertThatCode(owned::close).doesNotThrowAnyException();
        verify(healthy).close();
    }

    private Function<Map<String, Object>, SchemaResolver<JsonSchema, Object>> fixed(
            SchemaResolver<JsonSchema, Object> first, SchemaResolver<JsonSchema, Object> second) {
        AtomicInteger calls = new AtomicInteger();
        return config -> calls.getAndIncrement() == 0 ? first : second;
    }
}
