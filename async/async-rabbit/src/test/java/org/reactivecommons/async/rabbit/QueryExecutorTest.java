package org.reactivecommons.async.rabbit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.api.From;
import org.reactivecommons.async.api.handlers.QueryHandlerDelegate;
import org.reactivecommons.async.helpers.SampleClass;
import org.reactivecommons.async.helpers.TestStubs;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.QueryExecutor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Date;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class QueryExecutorTest {
    @Mock
    private QueryHandlerDelegate<Void, SampleClass> queryHandlerDelegate;
    @Mock
    private Function<Message, SampleClass> converter;
    private QueryExecutor<Void, SampleClass> executor;

    @BeforeEach
    public void setUp() {
        executor = new QueryExecutor<>(queryHandlerDelegate, converter);
    }

    @Test
    public void shouldExecute() {
        ArgumentCaptor<From> fromCaptor = ArgumentCaptor.forClass(From.class);
        ArgumentCaptor<SampleClass> sampleClassCaptor = ArgumentCaptor.forClass(SampleClass.class);
        Message message = TestStubs.mockMessage();

        when(converter.apply(any(Message.class))).thenReturn(new SampleClass("id", "name", new Date()));
        when(queryHandlerDelegate.handle(any(), any())).thenReturn(Mono.empty());

        Mono<Void> result = executor.execute(message);

        StepVerifier.create(result)
                .verifyComplete();

        verify(queryHandlerDelegate).handle(fromCaptor.capture(), sampleClassCaptor.capture());
        assertThat(fromCaptor.getValue().getReplyID()).isEqualTo("reply");
        assertThat(fromCaptor.getValue().getCorrelationID()).isEqualTo("correlation");
        assertThat(sampleClassCaptor.getValue()).extracting(SampleClass::getId, SampleClass::getName)
                .containsExactly("id", "name");
    }
}
